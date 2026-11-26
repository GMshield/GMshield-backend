package io.github.gmshield.scope.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** Story 4.6: a question's spans ({@link TermSpans}), without Spring. */
class TermSpansTest {

    @Test
    void aSpanHoldsAtMostSixWords() {
        assertThat(TermSpans.of("um dois três quatro cinco seis sete")).contains("um dois três quatro cinco seis")
                .doesNotContain("um dois três quatro cinco seis sete")
                .contains("dois três quatro cinco seis sete");
    }

    @Test
    void aSpanPastTheLongestNameEndsItsRun() {
        String long1 = "a".repeat(150);
        String long2 = "b".repeat(60);
        assertThat(TermSpans.of(long1 + " " + long2)).contains(long1, long2).doesNotContain(long1 + " " + long2);
    }

    @Test
    void punctuationIsTrimmedAtEitherEndOrBoth() {
        assertThat(TermSpans.of("Qual o alcance de Fireball?")).contains("fireball?", "fireball",
                "qual o alcance de fireball");
        assertThat(TermSpans.of("Quantos (PV)")).contains("pv", "(pv)");
        assertThat(TermSpans.of("A +1 Sword?")).contains("+1 sword?", "+1 sword", "1 sword?", "1 sword");
        assertThat(TermSpans.of("Bigby's Hand.")).contains("bigby's hand");
    }

    @Test
    void anyUnicodeSpaceSplitsWords() {
        assertThat(TermSpans.of("Bola de Fogo")).contains("bola", "de", "fogo", "bola de fogo");
    }

    @Test
    void aQuestionOfPunctuationOnlyHasNoSpan() {
        assertThat(TermSpans.of("?! ... -- ()")).isEmpty();
        assertThat(TermSpans.of("   ")).isEmpty();
    }

    @Test
    void aQuestionInNfdKeepsItsFinalAccent() {
        String decomposed = Normalizer.normalize("Quem é o Pajé?", Normalizer.Form.NFD);
        assertThat(TermSpans.of(decomposed)).contains("pajé", "o pajé").doesNotContain("paje");
    }

    @Test
    void aLongQuestionGivesAtMostTheCapInItsOrder() {
        StringBuilder question = new StringBuilder();
        for (int i = 0; i < 2_000; i++) {
            question.append("w").append(i).append(' ');
        }
        var spans = TermSpans.of(question.toString());
        assertThat(spans).hasSize(TermSpans.MAX_SPANS).startsWith("w0").doesNotContain("w1999");
        assertThat(spans).doesNotHaveDuplicates();
        assertThat(Collections.frequency(spans, "w0")).isOne();
    }
}

package io.github.gmshield.materials.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Story 4.4: a quote is compared with its passage in NFC, whitespace collapsed. */
class QuotesTest {

    @Test
    void everyRunOfWhitespaceIsOneSpaceAndNoneIsLeftAtEitherEnd() {
        assertThat(Quotes.collapsed("  Pontos\n de\t\tVida (PV)  ")).isEqualTo("Pontos de Vida (PV)");
        assertThat(Quotes.collapsed("\n \t")).isEmpty();
        assertThat(Quotes.collapsed("")).isEmpty();
        // U+0085 too, as the worker's str.split() counts it.
        assertThat(Quotes.collapsed("Pontos\u0085de Vida")).isEqualTo("Pontos de Vida");
    }

    @Test
    void aDecomposedAccentMatchesAComposedOne() {
        assertThat(Quotes.collapsed("Saúde")).isEqualTo("Saúde");
    }
}

package io.github.gmshield.conversations.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Story 2.4: a question's language by its common short words, or unsure. */
class LanguagesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Quanto dano causa Fire Bolt? | pt-BR",
        "Qual é o alcance da Espada Vorpal? | pt-BR",
        "Como posso evitar um ataque de oportunidade? | pt-BR",
        "O QUE ACONTECE QUANDO A EXAUSTÃO CHEGA A SEIS? | pt-BR",
        "How much damage does Fire Bolt deal? | en",
        "What is the range of a Vorpal Sword? | en",
        "Where can I find the rules for falling? | en",
    })
    void aQuestionWithMoreOfOneLanguagesShortWordsIsInThatLanguage(String question, String language) {
        assertThat(Languages.detect(question)).isEqualTo(language);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "Fire Bolt?", "Vorpal Sword", "1d10", "a do no me", "what que"})
    void aQuestionWithoutAClearMajorityIsUnsure(String question) {
        assertThat(Languages.detect(question)).isNull();
    }
}

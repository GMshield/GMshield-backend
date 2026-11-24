package io.github.gmshield.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Story 4.4: an approved record type's key is a slug of its approved name. */
class ProposalKeysTest {

    @Test
    void aKeyKeepsLettersWithoutAccentsAndDigitsInLowerCaseAndJoinsTheRestWithOneUnderscore() {
        assertThat(Proposals.slug("Feat")).isEqualTo("feat");
        assertThat(Proposals.slug("  Pontos de Vida (PV)! ")).isEqualTo("pontos_de_vida_pv");
        assertThat(Proposals.slug("Ação Bônus 2")).isEqualTo("acao_bonus_2");
        assertThat(Proposals.slug("Façanha")).isEqualTo("facanha");
    }

    @Test
    void aKeyIsAtMostTwentyEightCharactersAndANameWithoutLettersOrDigitsGetsTheFallback() {
        assertThat(Proposals.slug("a".repeat(40))).hasSize(Proposals.KEY_BASE);
        assertThat(Proposals.slug("abc " + "d".repeat(30))).isEqualTo("abc_" + "d".repeat(24));
        assertThat(Proposals.slug("魔法")).isEqualTo(Proposals.KEY_FALLBACK);
        assertThat(Proposals.slug("!!!")).isEqualTo(Proposals.KEY_FALLBACK);
    }

    @Test
    void aSeparatorIsWrittenOnlyWithRoomForALetterSoAKeyAndItsSuffixFitThirtyTwo() {
        assertThat(Proposals.slug("a".repeat(27) + " b")).isEqualTo("a".repeat(27));
        assertThat(Proposals.slug("a".repeat(26) + " bc")).isEqualTo("a".repeat(26) + "_b");
        assertThat(Proposals.slug("a".repeat(26) + " bc")).hasSize(Proposals.KEY_BASE);
        assertThat(Proposals.slug("a".repeat(28) + "_")).isEqualTo("a".repeat(28));
        // The longest suffix a key takes is _999: 28 + 4.
        assertThat((Proposals.slug("x".repeat(60)) + "_999").length()).isLessThanOrEqualTo(32);
    }

    @Test
    void aKeyNeverStartsWithADigitSoASlugThatWouldGetsTPrefixAndStillFits() {
        assertThat(Proposals.slug("3d6 Tabela")).isEqualTo("t_3d6_tabela");
        assertThat(Proposals.slug("  12 Talentos")).isEqualTo("t_12_talentos");
        assertThat(Proposals.slug("Tabela 3d6")).isEqualTo("tabela_3d6");
        assertThat(Proposals.slug("9".repeat(40))).isEqualTo("t_" + "9".repeat(Proposals.KEY_BASE - 2));
        assertThat(Proposals.slug("9".repeat(40))).hasSize(Proposals.KEY_BASE);
        assertThat(Proposals.slug("1 " + "a".repeat(40))).hasSizeLessThanOrEqualTo(Proposals.KEY_BASE)
                .matches("[a-z][a-z0-9_]{0,27}");
    }
}

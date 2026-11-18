package io.github.gmshield.library.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The form whose fingerprint makes a system's name unique per owner: trimmed, NFC, lower case. */
class UniqueFormTest {

    @Test
    void namesThatDifferOnlyInSpacingAtTheEndsCaseOrCompositionHaveOneForm() {
        assertThat(Library.uniqueForm("  Untitled System \t")).isEqualTo("untitled system");
        assertThat(Library.uniqueForm("UNTITLED SYSTEM")).isEqualTo(Library.uniqueForm("untitled system"));
        // "é" composed (U+00E9) and decomposed (e + U+0301) are one name after NFC.
        assertThat(Library.uniqueForm("Ope\u0301ra")).isEqualTo("op\u00e9ra");
        assertThat(Library.uniqueForm(" OP\u00c9RA ")).isEqualTo("op\u00e9ra");
        assertThat(Library.uniqueForm("SISTEMA SEM NOME")).isEqualTo("sistema sem nome");
        // Spaces inside the name are kept.
        assertThat(Library.uniqueForm("D&D  5e")).isEqualTo("d&d  5e");
    }
}

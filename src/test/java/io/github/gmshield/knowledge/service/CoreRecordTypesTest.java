package io.github.gmshield.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Story 4.6: the core names a question's span may name ({@link CoreRecordTypes#keyAsked}), without Spring. */
class CoreRecordTypesTest {

    @Test
    void theCoreNamesMatchInPtAndEnSingularAndPluralAndNothingElse() {
        assertThat(CoreRecordTypes.keyAsked("magias")).contains("spell");
        assertThat(CoreRecordTypes.keyAsked("fichas de personagem")).contains("character_sheet");
        assertThat(CoreRecordTypes.keyAsked("creature")).contains("creature");
        assertThat(CoreRecordTypes.keyAsked("iniciativa")).isEmpty();
        assertThat(CoreRecordTypes.keyAsked(null)).isEmpty();
    }
}

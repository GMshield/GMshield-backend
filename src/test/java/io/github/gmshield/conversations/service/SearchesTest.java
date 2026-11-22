package io.github.gmshield.conversations.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Story 3.9: a search result's first words. */
class SearchesTest {

    @Test
    void aShortTextIsKeptWholeWithItsWhiteSpaceMadeSingleSpaces() {
        assertThat(Searches.firstWords("  Fire Bolt\n\tdeals   1d10  ")).isEqualTo("Fire Bolt deals 1d10");
        assertThat(Searches.firstWords("")).isEmpty();
        // Unicode white space too: a no-break space, an ideographic space and a line separator.
        assertThat(Searches.firstWords("Fire\u00A0Bolt\u3000deals\u2028 1d10\u00A0")).isEqualTo("Fire Bolt deals 1d10");
    }

    @Test
    void aLongTextIsCutAt160CodePointsBackToTheLastWholeWord() {
        String words = "palavra ".repeat(30);

        String cut = Searches.firstWords(words);

        assertThat(cut.codePointCount(0, cut.length())).isLessThanOrEqualTo(160);
        assertThat(cut).endsWith("palavra").doesNotEndWith(" ");
        assertThat(cut.split(" ")).allMatch("palavra"::equals);
        // A cut that falls just before a space keeps the whole last word.
        String exact = "a".repeat(160) + " b";
        assertThat(Searches.firstWords(exact)).isEqualTo("a".repeat(160));
    }

    @Test
    void codePointsAreCountedNotCharsAndOneLongWordIsCutWhereTheLimitEnds() {
        String emoji = "😀".repeat(200);
        String cut = Searches.firstWords(emoji);
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(160);
        assertThat(Searches.firstWords("ação ".repeat(40))).endsWith("ação");
    }
}

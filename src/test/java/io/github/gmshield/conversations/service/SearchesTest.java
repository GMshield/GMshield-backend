package io.github.gmshield.conversations.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.knowledge.service.CoreRecordTypes;
import io.github.gmshield.scope.service.ScopedRecord;
import io.github.gmshield.scope.service.Tier;
import io.github.gmshield.shared.service.Ids;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Story 3.9: a search result's first words. Story 4.3 part B: the record-type filter's keys and the order a done
 * search's results are stored in.
 */
class SearchesTest {

    @Test
    void aRecordTypeFilterTakesExactlyTheCoreKeys() {
        assertThat(Searches.RECORD_TYPES).isEqualTo(CoreRecordTypes.KEYS);
    }

    @Test
    void exactNameRecordsLeadInTheWorkersOrderThenTheOthersByNameThenEveryOtherIdInTheWorkersOrder() {
        UUID passageA = Ids.newId();
        UUID passageB = Ids.newId();
        UUID other = Ids.newId();
        ScopedRecord luzOfTwo = record("Luz");
        ScopedRecord luzOfOne = record("Luz");
        ScopedRecord lower = record("luz");
        ScopedRecord upper = record("LUZ");
        // The worker found a passage, the second "Luz", another record and the first "Luz", in that order.
        List<UUID> worker = List.of(passageA, luzOfTwo.recordId(), other, luzOfOne.recordId(), passageB);

        List<UUID> ranked = SearchJobs.ranked(worker, List.of(luzOfOne, luzOfTwo, upper, lower));

        assertThat(ranked).containsExactly(luzOfTwo.recordId(), luzOfOne.recordId(), lower.recordId(),
                upper.recordId(), passageA, other, passageB);
        // No exact name: the worker's order, untouched.
        assertThat(SearchJobs.ranked(worker, List.of())).isEqualTo(worker);
        // At most 20 are kept, the exact names first.
        List<UUID> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(Ids.newId());
        }
        List<UUID> cut = SearchJobs.ranked(many, List.of(luzOfOne));
        assertThat(cut).hasSize(Searches.MAX_RESULTS).startsWith(luzOfOne.recordId())
                .doesNotContain(many.getLast());
    }

    private static ScopedRecord record(String name) {
        return new ScopedRecord(Ids.newId(), Ids.newId(), Ids.newId(), "Livro", List.of("Magias"), 1,
                List.of(Ids.newId()), "spell", name, Tier.SYSTEM);
    }

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

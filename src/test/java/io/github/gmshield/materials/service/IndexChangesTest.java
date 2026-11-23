package io.github.gmshield.materials.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Story 4.2 part B: how a version's records are cut into records runs, by first page. */
class IndexChangesTest {

    @Test
    void aRunTakes200RecordsThenTheRestOfItsLastPageSoNoPageIsSplit() {
        // Matrix row "Run boundary": 210 records, 15 a page; the 200th falls on page 14, whose last record is the 210th.
        List<Integer> pages = IntStream.range(0, 210).map(i -> 1 + i / 15).boxed().toList();
        assertThat(pages.get(199)).isEqualTo(14);
        assertThat(pages.stream().filter(page -> page == 14)).hasSize(15);

        assertThat(IndexChanges.recordRuns(pages)).containsExactly(new int[] {1, 14});

        // With 20 more records, the run ends at page 14 all the same, and the next takes the rest.
        List<Integer> more = IntStream.range(0, 230).map(i -> 1 + i / 15).boxed().toList();
        assertThat(IndexChanges.recordRuns(more)).containsExactly(new int[] {1, 14}, new int[] {15, 16});
    }

    @Test
    void aRunEndsAtThe200thRecordWhenItsPageEndsThereAndPagesMayBeSkipped() {
        List<Integer> pages = new ArrayList<>();
        IntStream.range(0, 200).forEach(i -> pages.add(3 + i / 10));
        IntStream.range(0, 5).forEach(i -> pages.add(40));
        IntStream.range(0, 200).forEach(i -> pages.add(41));
        IntStream.range(0, 3).forEach(i -> pages.add(900));

        // The 200th record is the last of page 22: the run ends there. A page of 200 records fills one run alone.
        assertThat(IndexChanges.recordRuns(pages)).containsExactly(new int[] {3, 22}, new int[] {40, 41},
                new int[] {900, 900});
        assertThat(IndexChanges.recordRuns(List.of())).isEmpty();
        assertThat(IndexChanges.recordRuns(List.of(7))).containsExactly(new int[] {7, 7});
    }
}

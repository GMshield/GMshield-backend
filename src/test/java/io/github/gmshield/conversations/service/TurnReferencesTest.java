package io.github.gmshield.conversations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.gmshield.scope.service.CitedPlace;
import io.github.gmshield.scope.service.ScopedRecord;
import io.github.gmshield.scope.service.ScopedRecordContent;
import io.github.gmshield.scope.service.Tier;
import io.github.gmshield.shared.service.Ids;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Story 2.5: how cited places become references: one per distinct (version, section path, page), by first citation.
 * Story 4.3 part C: a cited record is a reference of its own, at its first page's place, with its passages there.
 */
class TurnReferencesTest {

    private static final List<String> SECTION = List.of("Magias", "Fire Bolt");

    @Test
    void twoMaterialsCitedOnTheSamePageGiveTwoReferencesEachWithItsOwnName() {
        UUID a = Ids.newId();
        UUID b = Ids.newId();
        UUID versionA = Ids.newId();
        UUID versionB = Ids.newId();

        List<TurnReferences.Grouped> grouped = TurnReferences.group(List.of(
                new CitedPlace(a, versionA, Ids.newId(), "Livro do Jogador", SECTION, 5, Tier.CAMPAIGN),
                new CitedPlace(b, versionB, Ids.newId(), "Bestiário", SECTION, 5, Tier.CAMPAIGN)));

        assertThat(grouped).extracting(TurnReferences.Grouped::number, group -> group.place().materialName(),
                TurnReferences.Grouped::passageIds)
                .containsExactly(tuple(1, "Livro do Jogador", List.of(a)), tuple(2, "Bestiário", List.of(b)));
    }

    @Test
    void oneVersionsPassagesOnOnePageUnderDifferentSectionsGiveTwoReferencesEachWithItsOwnPath() {
        UUID version = Ids.newId();
        UUID material = Ids.newId();
        UUID first = Ids.newId();
        UUID second = Ids.newId();
        UUID third = Ids.newId();

        List<TurnReferences.Grouped> grouped = TurnReferences.group(List.of(
                new CitedPlace(first, version, material, "Livro", List.of("Magias", "Fire Bolt"), 7, Tier.CAMPAIGN),
                new CitedPlace(second, version, material, "Livro", List.of("Magias", "Frostbite"), 7, Tier.CAMPAIGN),
                new CitedPlace(third, version, material, "Livro", List.of("Magias", "Fire Bolt"), 7, Tier.CAMPAIGN)));

        assertThat(grouped).extracting(TurnReferences.Grouped::number, group -> group.place().sectionPath(),
                TurnReferences.Grouped::passageIds)
                .containsExactly(tuple(1, List.of("Magias", "Fire Bolt"), List.of(first, third)),
                        tuple(2, List.of("Magias", "Frostbite"), List.of(second)));
    }

    @Test
    void theSamePlaceOnAnotherPageIsAnotherReference() {
        UUID version = Ids.newId();
        UUID first = Ids.newId();
        UUID second = Ids.newId();

        List<TurnReferences.Grouped> grouped = TurnReferences.group(List.of(
                new CitedPlace(first, version, Ids.newId(), "Livro", SECTION, 7, Tier.CAMPAIGN),
                new CitedPlace(second, version, Ids.newId(), "Livro", SECTION, 8, Tier.CAMPAIGN)));

        assertThat(grouped).extracting(TurnReferences.Grouped::number, group -> group.place().page())
                .containsExactly(tuple(1, 7), tuple(2, 8));
        assertThat(TurnReferences.group(List.of())).isEmpty();
    }

    @Test
    void aCitedRecordIsItsOwnReferenceEvenOnAPlaceAPassageWasCitedAt() {
        UUID version = Ids.newId();
        UUID material = Ids.newId();
        UUID passage = Ids.newId();
        UUID other = Ids.newId();
        UUID recordId = Ids.newId();
        ScopedRecordContent record = new ScopedRecordContent(new ScopedRecord(recordId, version, material, "Livro",
                SECTION, 5, List.of(passage, other), "spell", "Luz", Tier.SYSTEM),
                List.of(new ScopedRecordContent.Field("Nível", "0")));
        CitedPlace place = new CitedPlace(passage, version, material, "Livro", SECTION, 5, Tier.SYSTEM);

        List<TurnReferences.Grouped> grouped = TurnReferences.groupCited(List.of(TurnReferences.Cited.passage(place),
                TurnReferences.Cited.record(record), TurnReferences.Cited.record(record)));

        assertThat(grouped).extracting(TurnReferences.Grouped::number, TurnReferences.Grouped::passageIds,
                TurnReferences.Grouped::record)
                .containsExactly(tuple(1, List.of(passage), null), tuple(2, List.of(passage, other), record));
        assertThat(grouped.get(1).place()).extracting(CitedPlace::page, CitedPlace::sectionPath, CitedPlace::tier)
                .containsExactly(5, SECTION, Tier.SYSTEM);
    }
}

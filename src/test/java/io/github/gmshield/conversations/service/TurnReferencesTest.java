package io.github.gmshield.conversations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.gmshield.scope.service.CitedPlace;
import io.github.gmshield.shared.service.Ids;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Story 2.5: how cited places become references: one per distinct (version, section path, page), by first citation. */
class TurnReferencesTest {

    private static final List<String> SECTION = List.of("Magias", "Fire Bolt");

    @Test
    void twoMaterialsCitedOnTheSamePageGiveTwoReferencesEachWithItsOwnName() {
        UUID a = Ids.newId();
        UUID b = Ids.newId();
        UUID versionA = Ids.newId();
        UUID versionB = Ids.newId();

        List<TurnReferences.Grouped> grouped = TurnReferences.group(List.of(
                new CitedPlace(a, versionA, Ids.newId(), "Livro do Jogador", SECTION, 5),
                new CitedPlace(b, versionB, Ids.newId(), "Bestiário", SECTION, 5)));

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
                new CitedPlace(first, version, material, "Livro", List.of("Magias", "Fire Bolt"), 7),
                new CitedPlace(second, version, material, "Livro", List.of("Magias", "Frostbite"), 7),
                new CitedPlace(third, version, material, "Livro", List.of("Magias", "Fire Bolt"), 7)));

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
                new CitedPlace(first, version, Ids.newId(), "Livro", SECTION, 7),
                new CitedPlace(second, version, Ids.newId(), "Livro", SECTION, 8)));

        assertThat(grouped).extracting(TurnReferences.Grouped::number, group -> group.place().page())
                .containsExactly(tuple(1, 7), tuple(2, 8));
        assertThat(TurnReferences.group(List.of())).isEmpty();
    }
}

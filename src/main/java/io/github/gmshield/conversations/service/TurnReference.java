package io.github.gmshield.conversations.service;

import java.util.List;
import java.util.UUID;

/**
 * A source reference of a completed turn as the GM's stream sends it (Story 2.5): its number (1..n, by first citation),
 * the material's name, the section path (outermost first), the page, the ids of the cited passages at that place, and
 * whether its source has been removed since. {@code toString} names the number and the page only.
 */
public record TurnReference(int number, String materialName, List<String> sectionPath, int page,
                            List<UUID> passageIds, boolean removed) {

    public TurnReference {
        sectionPath = List.copyOf(sectionPath);
        passageIds = List.copyOf(passageIds);
    }

    @Override
    public String toString() {
        return "TurnReference[number=" + number + ", page=" + page + ", removed=" + removed + "]";
    }
}

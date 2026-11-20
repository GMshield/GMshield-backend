package io.github.gmshield.scope.service;

import java.util.List;
import java.util.UUID;

/**
 * A passage served within a campaign's scope (Story 2.2): its id, the tier of its source, its text, its section path
 * (outermost first) and its page. {@code toString} names the id, the tier and the page only.
 */
public record ScopedPassage(UUID id, Tier tier, String text, List<String> sectionPath, int page) {

    public ScopedPassage {
        sectionPath = List.copyOf(sectionPath);
    }

    @Override
    public String toString() {
        return "ScopedPassage[id=" + id + ", tier=" + tier + ", page=" + page + "]";
    }
}

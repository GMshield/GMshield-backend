package io.github.gmshield.jobs.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A passage a job's handler serves to the worker by id (Story 2.2, internal contract 1.8.0): its id, the tier of its
 * source ({@code campaign} or {@code system}), its text, its section path (outermost first) and its page.
 * {@code toString} names the id, the tier and the page only.
 */
public record JobPassage(UUID id, String tier, String text, List<String> sectionPath, int page) {

    public JobPassage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(text, "text");
        sectionPath = List.copyOf(sectionPath);
    }

    @Override
    public String toString() {
        return "JobPassage[id=" + id + ", tier=" + tier + ", page=" + page + "]";
    }
}

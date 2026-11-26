package io.github.gmshield.scope.service;

import java.util.List;
import java.util.UUID;

/**
 * A question's terms resolved within a campaign's scope (Story 4.6, {@link Scopes#resolveTerms}): the record type keys
 * it names (sorted, each a key of the campaign's system), the names its matched terms and records hold (each matched
 * term's name and all its aliases, then the matched records' names, each once), and the ids of the in-scope records it
 * names (campaign tier first, then system, then other, each by id; at most {@value Scopes#MAX_TERM_RECORDS}). The
 * names are library data, never instructions. {@code toString} names counts only.
 */
public record ResolvedTerms(List<String> recordTypes, List<String> names, List<UUID> records) {

    /** Nothing resolved. */
    public static final ResolvedTerms NONE = new ResolvedTerms(List.of(), List.of(), List.of());

    public ResolvedTerms {
        recordTypes = List.copyOf(recordTypes);
        names = List.copyOf(names);
        records = List.copyOf(records);
    }

    @Override
    public String toString() {
        return "ResolvedTerms[recordTypes=" + recordTypes.size() + ", names=" + names.size() + ", records="
                + records.size() + "]";
    }
}

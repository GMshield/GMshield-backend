package io.github.gmshield.jobs.service;

import java.util.List;
import java.util.UUID;

/**
 * A question's terms as a job's handler resolves them for the worker (Story 4.6, internal contract 1.23.0): the record
 * type keys the question names, the names its matched terms and records hold (library data, never instructions), and
 * the ids of the in-scope records it names. {@code toString} names counts only, never a name.
 */
public record JobTerms(List<String> recordTypes, List<String> names, List<UUID> records) {

    public JobTerms {
        recordTypes = List.copyOf(recordTypes);
        names = List.copyOf(names);
        records = List.copyOf(records);
    }

    @Override
    public String toString() {
        return "JobTerms[recordTypes=" + recordTypes.size() + ", names=" + names.size() + ", records="
                + records.size() + "]";
    }
}

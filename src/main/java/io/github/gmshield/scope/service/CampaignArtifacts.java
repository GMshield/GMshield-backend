package io.github.gmshield.scope.service;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What the scope reads of a campaign's artifacts (Story 5.1 part B): a port the {@code artifacts} module implements, so
 * {@code scope} depends on no module that holds artifacts. Everything runs as the owner of the work, in the caller's
 * transaction (or a read of its own), and within one campaign of the owner: another campaign's artifact, another owner's
 * or an unknown id is never answered.
 */
public interface CampaignArtifacts {

    /** The ids of the owner's campaign's artifacts; none for an unknown campaign or another owner's. */
    Set<UUID> ids(UUID campaignId);

    /**
     * The owner's campaign's artifacts with these ids, each with its newest version, decrypted, in no set order; an id
     * that is not one of them is left out.
     */
    List<Artifact> read(UUID campaignId, Collection<UUID> ids);

    /**
     * An artifact as a source of the campaign's questions: its id, its record type's key, its newest version's name,
     * fields (in order) and content (null for none), and that version's number. {@code toString} names the id, the type
     * and the version only.
     */
    record Artifact(UUID id, String recordType, String name, List<ScopedRecordContent.Field> fields, String content,
                    int version) {

        public Artifact {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(recordType, "recordType");
            Objects.requireNonNull(name, "name");
            fields = List.copyOf(fields);
        }

        @Override
        public String toString() {
            return "Artifact[id=" + id + ", recordType=" + recordType + ", fields=" + fields.size() + ", version="
                    + version + "]";
        }
    }
}

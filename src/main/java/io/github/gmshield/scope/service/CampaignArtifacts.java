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
 * or an unknown id is never answered. From Story 5.2 part B a deleting artifact (the GM deleted it, and its rows are
 * still being removed) is never answered either, as one that is gone.
 *
 * <p>Story 5.6 part A: an artifact can be shared with the owner's other campaigns of its system. {@link #shared} and
 * {@link #readShared} answer the live artifacts shared into a campaign, kept apart from {@link #ids}, {@link #live} and
 * {@link #read}, which stay the campaign's own artifacts (overrides and relations read those only).
 */
public interface CampaignArtifacts {

    /** The ids of the owner's campaign's artifacts; none for an unknown campaign or another owner's. */
    Set<UUID> ids(UUID campaignId);

    /**
     * Which of these ids are the owner's campaign's live artifacts (Story 5.2 part B): one that is deleting or gone,
     * another campaign's, another owner's or unknown is left out.
     */
    Set<UUID> live(UUID campaignId, Collection<UUID> ids);

    /**
     * Which of these ids name an artifact of the owner that the GM deleted (Story 5.2 part B), whether it is still
     * deleting or already gone, of whichever campaign; any other id is left out.
     */
    Set<UUID> deleted(Collection<UUID> ids);

    /**
     * The owner's campaign's artifacts with these ids, each with its newest version, decrypted, in no set order; an id
     * that is not one of them is left out.
     */
    List<Artifact> read(UUID campaignId, Collection<UUID> ids);

    /**
     * The ids of the owner's live artifacts shared into the campaign {@code campaignId} (Story 5.6 part A): each of
     * another campaign of the owner that is still readable (archived or not, never one being deleted); none for an
     * unknown campaign or another owner's.
     */
    Set<UUID> shared(UUID campaignId);

    /**
     * The owner's live artifacts shared into the campaign {@code campaignId} with these ids (Story 5.6 part A), as
     * {@link #shared} finds them, each with its newest version, decrypted, in no set order; any other id is left out.
     */
    List<Artifact> readShared(UUID campaignId, Collection<UUID> ids);

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

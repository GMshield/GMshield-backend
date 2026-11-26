package io.github.gmshield.library.service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * What a module does when one of the owner's systems is deleted (Story 3.1 part B, AD-22). {@link SystemDeletion}
 * finds every handler registered as a bean and calls it as the owner of the work, so {@code library} depends on no
 * module that implements it ({@code materials}, {@code conversations}). Each call names what goes, collected again at
 * each call: the system and its campaigns, or (Story 3.2 part B, a campaign's deletion) no system and the one campaign,
 * or (Story 3.4 part B) one material, or one version of a material, by id alone: {@code library} knows nothing of
 * either, and the module that owns them interprets the id. Story 3.10 part B adds one conversation, by its id alone in
 * the same way: only {@code conversations} acts on it, and every other handler ignores it, as it ignores a material
 * in a module that has none. Story 5.2 part B adds one artifact, by its id alone too: only {@code artifacts} acts on
 * it, and every other handler ignores it.
 *
 * <ul>
 *   <li>{@link #count}: how many of its own items go, for the confirmation dialog; inside a read-only transaction.</li>
 *   <li>{@link #prepare}: in the GM's transaction (and again at the start of each removal pass), it cancels the jobs of
 *       what goes and hides it from every list; it answers the latest time a claim of a job it cancelled lapses, when
 *       one was running. Nothing is removed yet.</li>
 *   <li>{@link #remove}: after every such claim has lapsed, outside any transaction, it removes its rows and files,
 *       each step safe to repeat. A failure (thrown) leaves the deletion for the next pass.</li>
 * </ul>
 */
public interface SystemDeletionHandler {

    /**
     * What goes: the owner's system and the ids of all its campaigns; for a campaign's deletion (Story 3.2 part B)
     * {@code systemId} is null and {@code campaignIds} names that campaign alone, so nothing of its system goes. From
     * Story 3.4 part B, a material's deletion names only {@code materialId}, and a version's removal only
     * {@code versionId}: no system and no campaign go then. From Story 3.10 part B, a conversation's deletion names only
     * {@code conversationId}, an id {@code library} keeps opaque. From Story 5.2 part B, an artifact's deletion names
     * only {@code artifactId}, opaque in the same way.
     */
    record Going(UUID systemId, List<UUID> campaignIds, UUID materialId, UUID versionId, UUID conversationId,
                 UUID artifactId) {

        public Going {
            campaignIds = List.copyOf(campaignIds);
        }

        /** A system, or (null system) a campaign, and its campaigns going. */
        public Going(UUID systemId, List<UUID> campaignIds) {
            this(systemId, campaignIds, null, null, null, null);
        }

        /** One material going, with all its versions (Story 3.4 part B). */
        public static Going ofMaterial(UUID materialId) {
            return new Going(null, List.of(), Objects.requireNonNull(materialId, "materialId"), null, null, null);
        }

        /** One version of a material going; the material stays (Story 3.4 part B). */
        public static Going ofVersion(UUID versionId) {
            return new Going(null, List.of(), null, Objects.requireNonNull(versionId, "versionId"), null, null);
        }

        /** One conversation going, with its turns (Story 3.10 part B); nothing of its campaign goes. */
        public static Going ofConversation(UUID conversationId) {
            return new Going(null, List.of(), null, null, Objects.requireNonNull(conversationId, "conversationId"),
                    null);
        }

        /** One artifact going, with all its versions (Story 5.2 part B); nothing of its campaign goes. */
        public static Going ofArtifact(UUID artifactId) {
            return new Going(null, List.of(), null, null, null, Objects.requireNonNull(artifactId, "artifactId"));
        }

        /** Whether one material or one version goes, and no system or campaign (Story 3.4 part B). */
        public boolean ofMaterials() {
            return materialId != null || versionId != null;
        }

        /** Whether one conversation goes, and nothing else (Story 3.10 part B). */
        public boolean ofOneConversation() {
            return conversationId != null;
        }

        /** Whether one artifact goes, and nothing else (Story 5.2 part B). */
        public boolean ofOneArtifact() {
            return artifactId != null;
        }

        /**
         * Whether one item goes, by its id alone (a material, a version, a conversation or an artifact), and no system
         * or campaign: {@code library} has nothing of its own to remove then.
         */
        public boolean ofOneItem() {
            return ofMaterials() || ofOneConversation() || ofOneArtifact();
        }

        /**
         * A handler's log line about what goes, so it matches the deletion's own lines: {@code systemId} when a system
         * goes, else {@code campaignId} for a single campaign; then how many campaigns. For a material or a version,
         * {@code materialId} or {@code versionId} alone, for a conversation {@code conversationId} alone, and for an
         * artifact {@code artifactId} alone. Ids and counts only.
         */
        public LoggingEventBuilder about(LoggingEventBuilder event) {
            if (artifactId != null) {
                return event.addKeyValue("artifactId", artifactId);
            }
            if (conversationId != null) {
                return event.addKeyValue("conversationId", conversationId);
            }
            if (materialId != null) {
                return event.addKeyValue("materialId", materialId);
            }
            if (versionId != null) {
                return event.addKeyValue("versionId", versionId);
            }
            LoggingEventBuilder subject = event;
            if (systemId != null) {
                subject = subject.addKeyValue("systemId", systemId);
            } else if (campaignIds.size() == 1) {
                subject = subject.addKeyValue("campaignId", campaignIds.get(0));
            }
            return subject.addKeyValue("campaigns", campaignIds.size());
        }
    }

    /** The name of the count this handler gives: {@code conversations} or {@code materials}. */
    String part();

    /**
     * How many of this module's items go with the system, or with the campaign (0 for a material, a version, a
     * conversation or an artifact).
     */
    long count(Going going);

    /** Cancels and hides what goes, in the caller's transaction; the latest claim lapse of what it cancelled, if any. */
    Optional<Instant> prepare(Going going);

    /** Removes what goes, in transactions of its own; safe to repeat. */
    void remove(Going going);
}

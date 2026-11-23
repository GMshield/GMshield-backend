package io.github.gmshield.conversations.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code search} (Story 3.9): a search of a campaign's scope, its job, the query as a vault ciphertext, its
 * filters ({@code tier} {@code campaign}, {@code system} or {@code other}, {@code materialId}, and from Story 4.3 part B
 * {@code recordType}, a core key; each null when unset), its state ({@code pending}, {@code done} or {@code failed})
 * and, when failed, its code. {@code toString} names ids and words only.
 */
public record SearchRow(UUID id, UUID ownerId, UUID campaignId, UUID jobId, byte[] query, String tier,
                        UUID materialId, String recordType, String state, String code, Instant createdAt) {

    public static final String PENDING = "pending";
    public static final String DONE = "done";
    public static final String FAILED = "failed";

    public SearchRow {
        query = query.clone();
    }

    @Override
    public byte[] query() {
        return query.clone();
    }

    /** The row with this state and code. */
    public SearchRow ended(String state, String code) {
        return new SearchRow(id, ownerId, campaignId, jobId, query, tier, materialId, recordType, state, code,
                createdAt);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SearchRow that && Objects.equals(id, that.id) && Objects.equals(ownerId, that.ownerId)
                && Objects.equals(campaignId, that.campaignId) && Objects.equals(jobId, that.jobId)
                && Arrays.equals(query, that.query) && Objects.equals(tier, that.tier)
                && Objects.equals(materialId, that.materialId) && Objects.equals(recordType, that.recordType)
                && Objects.equals(state, that.state)
                && Objects.equals(code, that.code) && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, campaignId, jobId, Arrays.hashCode(query), tier, materialId, recordType, state,
                code, createdAt);
    }

    @Override
    public String toString() {
        return "SearchRow[id=" + id + ", campaignId=" + campaignId + ", jobId=" + jobId + ", tier=" + tier
                + ", recordType=" + recordType + ", state=" + state + ", code=" + code + "]";
    }
}

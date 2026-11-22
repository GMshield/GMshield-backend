package io.github.gmshield.conversations.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One row of {@code turn}: a question (a vault ciphertext), its language ({@code pt-BR}, {@code en} or null), the scope
 * settings frozen with it, the job that answers it, and when the GM stopped its answer (Story 2.6; null when never).
 * From Story 3.5 the frozen settings include the other tier's switch and the ids of the materials switched off
 * ({@code turn_material_off}). From Story 3.11, when the GM dismissed it ({@code dismissed_at}; null while shown).
 * {@code toString} names ids only.
 */
public record TurnRow(UUID id, UUID ownerId, UUID conversationId, UUID jobId, byte[] question, String language,
                      boolean campaignTier, boolean systemTier, boolean otherTier, boolean modelKnowledge,
                      Set<UUID> materialsOff, Instant createdAt, Instant stoppedAt, Instant dismissedAt) {

    public TurnRow {
        question = question.clone();
        materialsOff = materialsOff == null ? Set.of() : Set.copyOf(materialsOff);
    }

    @Override
    public byte[] question() {
        return question.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TurnRow that && campaignTier == that.campaignTier && systemTier == that.systemTier
                && otherTier == that.otherTier && modelKnowledge == that.modelKnowledge
                && materialsOff.equals(that.materialsOff) && Objects.equals(id, that.id)
                && Objects.equals(ownerId, that.ownerId) && Objects.equals(conversationId, that.conversationId)
                && Objects.equals(jobId, that.jobId) && Arrays.equals(question, that.question)
                && Objects.equals(language, that.language) && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(stoppedAt, that.stoppedAt) && Objects.equals(dismissedAt, that.dismissedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, conversationId, jobId, Arrays.hashCode(question), language, campaignTier,
                systemTier, otherTier, modelKnowledge, materialsOff, createdAt, stoppedAt, dismissedAt);
    }

    /** This row, stopped at {@code at}. */
    public TurnRow stopped(Instant at) {
        return new TurnRow(id, ownerId, conversationId, jobId, question, language, campaignTier, systemTier,
                otherTier, modelKnowledge, materialsOff, createdAt, at, dismissedAt);
    }

    /** This row, dismissed at {@code at} (Story 3.11; null: shown again). */
    public TurnRow dismissed(Instant at) {
        return new TurnRow(id, ownerId, conversationId, jobId, question, language, campaignTier, systemTier,
                otherTier, modelKnowledge, materialsOff, createdAt, stoppedAt, at);
    }

    @Override
    public String toString() {
        return "TurnRow[id=" + id + ", jobId=" + jobId + "]";
    }
}

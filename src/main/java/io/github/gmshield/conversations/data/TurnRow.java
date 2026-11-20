package io.github.gmshield.conversations.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code turn}: a question (a vault ciphertext), its language ({@code pt-BR}, {@code en} or null), the scope
 * settings frozen with it, the job that answers it, and when the GM stopped its answer (Story 2.6; null when never).
 * {@code toString} names ids only.
 */
public record TurnRow(UUID id, UUID ownerId, UUID conversationId, UUID jobId, byte[] question, String language,
                      boolean campaignTier, boolean systemTier, boolean modelKnowledge, Instant createdAt,
                      Instant stoppedAt) {

    public TurnRow {
        question = question.clone();
    }

    @Override
    public byte[] question() {
        return question.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TurnRow that && campaignTier == that.campaignTier && systemTier == that.systemTier
                && modelKnowledge == that.modelKnowledge && Objects.equals(id, that.id)
                && Objects.equals(ownerId, that.ownerId) && Objects.equals(conversationId, that.conversationId)
                && Objects.equals(jobId, that.jobId) && Arrays.equals(question, that.question)
                && Objects.equals(language, that.language) && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(stoppedAt, that.stoppedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, conversationId, jobId, Arrays.hashCode(question), language, campaignTier,
                systemTier, modelKnowledge, createdAt, stoppedAt);
    }

    /** This row, stopped at {@code at}. */
    public TurnRow stopped(Instant at) {
        return new TurnRow(id, ownerId, conversationId, jobId, question, language, campaignTier, systemTier,
                modelKnowledge, createdAt, at);
    }

    @Override
    public String toString() {
        return "TurnRow[id=" + id + ", jobId=" + jobId + "]";
    }
}

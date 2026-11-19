package io.github.gmshield.conversations.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code turn}: a question (a vault ciphertext), its language ({@code pt-BR}, {@code en} or null), the scope
 * settings frozen with it and the job that answers it. {@code toString} names ids only.
 */
public record TurnRow(UUID id, UUID ownerId, UUID conversationId, UUID jobId, byte[] question, String language,
                      boolean campaignTier, boolean systemTier, boolean modelKnowledge, Instant createdAt) {

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
                && Objects.equals(language, that.language) && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, conversationId, jobId, Arrays.hashCode(question), language, campaignTier,
                systemTier, modelKnowledge, createdAt);
    }

    @Override
    public String toString() {
        return "TurnRow[id=" + id + ", jobId=" + jobId + "]";
    }
}

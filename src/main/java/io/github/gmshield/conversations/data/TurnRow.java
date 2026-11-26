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
 * From Story 3.12, when the GM bookmarked its answer ({@code bookmarked_at}; null while not saved).
 * From Story 5.3 part A, its kind ({@code kind}, V47): {@value #QUESTION}, answered by a {@code question.answer} job,
 * or {@value #DRAFT}, drafted by a {@code content.draft} job; null reads as {@value #QUESTION}.
 * {@code toString} names ids only.
 */
public record TurnRow(UUID id, UUID ownerId, UUID conversationId, UUID jobId, byte[] question, String language,
                      boolean campaignTier, boolean systemTier, boolean otherTier, boolean modelKnowledge,
                      Set<UUID> materialsOff, Instant createdAt, Instant stoppedAt, Instant dismissedAt,
                      Instant bookmarkedAt, String kind) {

    /** The kind of a turn the GM asked as a question (Story 5.3 part A): the default. */
    public static final String QUESTION = "question";
    /** The kind of a turn the GM asked as a draft in the questions area (Story 5.3 part A). */
    public static final String DRAFT = "draft";

    public TurnRow {
        question = question.clone();
        kind = kind == null ? QUESTION : kind;
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
                && Objects.equals(stoppedAt, that.stoppedAt) && Objects.equals(dismissedAt, that.dismissedAt)
                && Objects.equals(bookmarkedAt, that.bookmarkedAt) && Objects.equals(kind, that.kind);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, conversationId, jobId, Arrays.hashCode(question), language, campaignTier,
                systemTier, otherTier, modelKnowledge, materialsOff, createdAt, stoppedAt, dismissedAt, bookmarkedAt,
                kind);
    }

    /** This row, stopped at {@code at}. */
    public TurnRow stopped(Instant at) {
        return new TurnRow(id, ownerId, conversationId, jobId, question, language, campaignTier, systemTier,
                otherTier, modelKnowledge, materialsOff, createdAt, at, dismissedAt, bookmarkedAt, kind);
    }

    /** This row, dismissed at {@code at} (Story 3.11; null: shown again). */
    public TurnRow dismissed(Instant at) {
        return new TurnRow(id, ownerId, conversationId, jobId, question, language, campaignTier, systemTier,
                otherTier, modelKnowledge, materialsOff, createdAt, stoppedAt, at, bookmarkedAt, kind);
    }

    /** This row, bookmarked at {@code at} (Story 3.12; null: not saved). */
    public TurnRow bookmarked(Instant at) {
        return new TurnRow(id, ownerId, conversationId, jobId, question, language, campaignTier, systemTier,
                otherTier, modelKnowledge, materialsOff, createdAt, stoppedAt, dismissedAt, at, kind);
    }

    @Override
    public String toString() {
        return "TurnRow[id=" + id + ", jobId=" + jobId + "]";
    }
}

package io.github.gmshield.conversations.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code conversation}: a campaign's conversation (Story 2.4). From Story 3.10 (V30) many per campaign, each
 * in one area ({@code questions} or {@code rules}), with the GM's name (a vault ciphertext; null until renamed), its
 * version (raised by each rename) and when its newest question was asked. {@code toString} names ids only.
 */
public record ConversationRow(UUID id, UUID ownerId, UUID campaignId, String area, byte[] name, long version,
                              Instant createdAt, Instant lastAskedAt) {

    public static final String QUESTIONS = "questions";
    public static final String RULES = "rules";

    public ConversationRow {
        name = name == null ? null : name.clone();
    }

    /** A new conversation of {@code area}, made by its first question at {@code at}: no name, version 0. */
    public static ConversationRow started(UUID id, UUID ownerId, UUID campaignId, String area, Instant at) {
        return new ConversationRow(id, ownerId, campaignId, area, null, 0, at, at);
    }

    @Override
    public byte[] name() {
        return name == null ? null : name.clone();
    }

    /** This row, its newest question asked at {@code at}. */
    public ConversationRow askedAt(Instant at) {
        return new ConversationRow(id, ownerId, campaignId, area, name, version, createdAt, at);
    }

    /** This row renamed to {@code newName}, its version raised by one. */
    public ConversationRow renamed(byte[] newName) {
        return new ConversationRow(id, ownerId, campaignId, area, newName, version + 1, createdAt, lastAskedAt);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConversationRow that && version == that.version && Objects.equals(id, that.id)
                && Objects.equals(ownerId, that.ownerId) && Objects.equals(campaignId, that.campaignId)
                && Objects.equals(area, that.area) && Arrays.equals(name, that.name)
                && Objects.equals(createdAt, that.createdAt) && Objects.equals(lastAskedAt, that.lastAskedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, campaignId, area, Arrays.hashCode(name), version, createdAt, lastAskedAt);
    }

    @Override
    public String toString() {
        return "ConversationRow[id=" + id + ", area=" + area + ", version=" + version + "]";
    }
}

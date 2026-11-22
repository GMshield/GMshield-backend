package io.github.gmshield.conversations.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code turn_reference} (Story 2.5): a numbered source reference of a turn, stored by value: its version
 * and material ids, its page, the ids of the cited passages at that place, the material's name and the section path as
 * vault ciphertexts, and (Story 3.7) the tier of its source in the turn's scope at answer time ({@code campaign},
 * {@code system} or {@code other}; {@code null} on a row written before V28). {@code toString} names ids and numbers
 * only.
 */
public record TurnReferenceRow(UUID id, UUID ownerId, UUID turnId, int number, UUID versionId, UUID materialId,
                               int page, List<UUID> passageIds, byte[] materialName, byte[] sectionPath,
                               Instant createdAt, String tier) {

    public TurnReferenceRow {
        passageIds = List.copyOf(passageIds);
        materialName = materialName.clone();
        sectionPath = sectionPath.clone();
    }

    @Override
    public byte[] materialName() {
        return materialName.clone();
    }

    @Override
    public byte[] sectionPath() {
        return sectionPath.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TurnReferenceRow that && number == that.number && page == that.page
                && Objects.equals(id, that.id) && Objects.equals(ownerId, that.ownerId)
                && Objects.equals(turnId, that.turnId) && Objects.equals(versionId, that.versionId)
                && Objects.equals(materialId, that.materialId) && Objects.equals(passageIds, that.passageIds)
                && Arrays.equals(materialName, that.materialName) && Arrays.equals(sectionPath, that.sectionPath)
                && Objects.equals(createdAt, that.createdAt) && Objects.equals(tier, that.tier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, turnId, number, versionId, materialId, page, passageIds,
                Arrays.hashCode(materialName), Arrays.hashCode(sectionPath), createdAt, tier);
    }

    @Override
    public String toString() {
        return "TurnReferenceRow[id=" + id + ", turnId=" + turnId + ", number=" + number + ", versionId=" + versionId
                + ", page=" + page + "]";
    }
}

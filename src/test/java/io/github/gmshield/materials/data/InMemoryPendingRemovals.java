package io.github.gmshield.materials.data;

import java.util.List;

/** {@link PendingRemovals} over an {@link InMemoryMaterialStore}'s versions, of every owner. */
public class InMemoryPendingRemovals implements PendingRemovals {

    private final InMemoryMaterialStore materials;

    public InMemoryPendingRemovals(InMemoryMaterialStore materials) {
        this.materials = materials;
    }

    @Override
    public List<Pending> list(int limit) {
        return materials.removalPending(limit).stream()
                .map(version -> new Pending(version.ownerId(), version.id())).toList();
    }
}

package io.github.gmshield.materials.data;

import java.util.List;
import java.util.UUID;

/** {@link StaleIndexes} over an {@link InMemoryMaterialStore}'s versions, of every owner. */
public class InMemoryStaleIndexes implements StaleIndexes {

    private final InMemoryMaterialStore materials;

    public InMemoryStaleIndexes(InMemoryMaterialStore materials) {
        this.materials = materials;
    }

    @Override
    public List<UUID> owners(String recipe) {
        return materials.staleOwners(recipe);
    }
}

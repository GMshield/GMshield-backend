package io.github.gmshield.artifacts.data;

import java.util.List;
import java.util.UUID;

/** {@link StaleArtifacts} over an {@link InMemoryArtifactIndexStore} and its versions, of every owner. */
public class InMemoryStaleArtifacts implements StaleArtifacts {

    private final InMemoryArtifactIndexStore index;

    public InMemoryStaleArtifacts(InMemoryArtifactIndexStore index) {
        this.index = index;
    }

    @Override
    public List<UUID> owners(String recipe) {
        return index.staleOwners(recipe);
    }
}

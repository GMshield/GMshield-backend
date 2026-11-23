package io.github.gmshield.materials.service;

import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The names of the owner's materials (Story 4.4), for the {@code knowledge} module: a proposal names the material that
 * proposed it, and an approved record type or term its origin. Every read acts for the owner of the work
 * ({@link OwnerContext}) in a read transaction (the caller's, when there is one), so another owner's material reads
 * exactly like an unknown one. Nothing here logs.
 */
@Service
@NamedInterface("service")
public class MaterialNames {

    private final MaterialStore materials;
    private final Vault vault;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    MaterialNames(MaterialStore materials, Vault vault, ObjectProvider<PlatformTransactionManager> transactions) {
        this.materials = materials;
        this.vault = vault;
        this.transactions = transactions;
    }

    /** The decrypted name of the owner's material {@code materialId}; empty for an unknown id or another owner's. */
    public Optional<String> name(UUID materialId) {
        return reading(() -> materials.findMaterial(materialId).map(this::name));
    }

    /** The decrypted names of the owner's materials with these ids; an unknown id or another owner's is left out. */
    public Map<UUID, String> names(Collection<UUID> materialIds) {
        if (materialIds.isEmpty()) {
            return Map.of();
        }
        return reading(() -> {
            Map<UUID, String> found = new HashMap<>();
            materialIds.stream().filter(Objects::nonNull).distinct().forEach(id -> materials.findMaterial(id)
                    .ifPresent(material -> found.put(id, name(material))));
            return Map.copyOf(found);
        });
    }

    private String name(MaterialRow material) {
        return vault.decryptText(new Binding(Uploads.MATERIAL, Uploads.FIELD_NAME, material.id(), material.ownerId()),
                material.name());
    }

    private <T> T reading(Supplier<T> read) {
        OwnerContext.require();
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }
}

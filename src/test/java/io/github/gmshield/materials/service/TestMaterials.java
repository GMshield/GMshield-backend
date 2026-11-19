package io.github.gmshield.materials.service;

import io.github.gmshield.materials.data.IndexStore;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.vault.service.Vault;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

/** What other packages' tests need of {@code materials.service} that is not public. */
public final class TestMaterials {

    private TestMaterials() {
    }

    /** {@link Materials#passage}, package-private since Story 2.2, for the HTTP flow tests of {@code materials.web}. */
    public static Optional<Materials.Passage> passage(Materials materials, UUID id) {
        return materials.passage(id);
    }

    /** Working scope reads over given stores; {@code unendedJobs} answers which jobs of the owner have not ended. */
    public static ScopeReads scopeReads(MaterialStore materials, IndexStore index, Vault vault,
                                        Function<Collection<UUID>, Set<UUID>> unendedJobs,
                                        ObjectProvider<PlatformTransactionManager> transactions) {
        return new ScopeReads(materials, index, vault, unendedJobs, transactions);
    }

    /** A material's name encrypted as an upload stores it (binding {@code material}/{@code name}). */
    public static byte[] materialName(Vault vault, UUID materialId, UUID owner, String name) {
        return vault.encryptText(new io.github.gmshield.vault.service.Binding(Uploads.MATERIAL, Uploads.FIELD_NAME,
                materialId, owner), name);
    }

    /** A passage row as ingestion stores it: its text and its place (the section path, no box) encrypted. */
    public static PassageRow passage(Vault vault, UUID id, UUID owner, UUID versionId, int ordinal, int page,
                                     String text, List<String> sectionPath, Instant now) {
        return new PassageRow(id, owner, versionId, ordinal, page, vault.encryptText(Passages.text(id, owner), text),
                vault.encrypt(Passages.place(id, owner), Passages.placeJson(new Passages.Place(sectionPath,
                        null, List.of(0, text.codePointCount(0, text.length()))))), now);
    }
}

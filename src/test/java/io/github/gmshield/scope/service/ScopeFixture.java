package io.github.gmshield.scope.service;

import io.github.gmshield.library.data.CampaignRow;
import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.library.service.Language;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.data.IndexChangeRow;
import io.github.gmshield.materials.data.InMemoryIndexStore;
import io.github.gmshield.materials.data.InMemoryMaterialStore;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialSystemRow;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.materials.service.TestMaterials;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An owner's library as the scope tests need it, written straight into the in-memory stores: the starter campaign
 * and system, another campaign of that system, materials of a campaign or a system with versions in given states, and
 * passages (text {@code "<tag> <ordinal>"}, section path {@code ["Capítulo", "Seção <ordinal>"]}, page
 * {@code ordinal / 10 + 1}), stored encrypted as ingestion stores them.
 */
final class ScopeFixture {

    /** The name of every material made here. */
    static final String MATERIAL_NAME = "Livro de Regras";

    private final Library library;
    private final InMemoryLibraryStore libraryStore;
    private final InMemoryMaterialStore materials;
    private final InMemoryIndexStore index;
    private final Vault vault;
    private final PlatformTransactionManager transactions;
    private Instant clock = Instant.parse("2026-11-18T12:00:00Z");

    ScopeFixture(Library library, InMemoryLibraryStore libraryStore, InMemoryMaterialStore materials,
                 InMemoryIndexStore index, Vault vault, PlatformTransactionManager transactions) {
        this.library = library;
        this.libraryStore = libraryStore;
        this.materials = materials;
        this.index = index;
        this.vault = vault;
        this.transactions = transactions;
    }

    /** An owner's starter library: its campaign and that campaign's system. */
    record Starter(UUID owner, UUID campaign, UUID system) {
    }

    Starter starter() {
        UUID owner = Ids.newId();
        return as(owner, () -> {
            library.createStarter(Language.EN);
            Library.Campaign campaign = library.currentCampaign().orElseThrow();
            return new Starter(owner, campaign.id(), campaign.system().id());
        });
    }

    /** Another campaign of the starter's system. */
    UUID otherCampaign(Starter starter) {
        UUID id = Ids.newId();
        as(starter.owner(), () -> {
            libraryStore.insertCampaign(new CampaignRow(id, starter.owner(), starter.system(),
                    vault.encryptText(new Binding("campaign", "name", id, starter.owner()), "Outra"), next()));
            return null;
        });
        return id;
    }

    /**
     * A material of {@code campaign} (or none), that belongs to {@code system} (or none), with one version per state,
     * oldest first; answers the versions' ids in that order.
     */
    List<UUID> material(UUID owner, UUID campaign, UUID system, String... states) {
        return as(owner, () -> {
            UUID materialId = Ids.newId();
            materials.insertMaterial(new MaterialRow(materialId, owner,
                    TestMaterials.materialName(vault, materialId, owner, MATERIAL_NAME), next(), "rulebook", null,
                    campaign));
            if (system != null) {
                materials.insertMaterialSystem(new MaterialSystemRow(Ids.newId(), owner, materialId, system, next()));
            }
            List<UUID> versions = new ArrayList<>();
            for (String state : states) {
                UUID versionId = Ids.newId();
                Instant at = next();
                materials.insertVersion(new VersionRow(versionId, owner, materialId, state, "pdf", 1000L, at, at));
                versions.add(versionId);
            }
            return List.copyOf(versions);
        });
    }

    /** A newer version of the owner's material in {@code state} (Story 3.4: a replacement); answers its id. */
    UUID version(UUID owner, UUID materialId, String state) {
        return as(owner, () -> {
            UUID versionId = Ids.newId();
            Instant at = next();
            materials.insertVersion(new VersionRow(versionId, owner, materialId, state, "pdf", 1000L, at, at));
            return versionId;
        });
    }

    /** {@code count} passages of the version, by ordinal from 0; answers their ids in that order. */
    List<UUID> passages(UUID owner, UUID versionId, String tag, int count) {
        return as(owner, () -> {
            List<PassageRow> rows = new ArrayList<>();
            for (int ordinal = 0; ordinal < count; ordinal++) {
                rows.add(TestMaterials.passage(vault, Ids.newId(), owner, versionId, ordinal, ordinal / 10 + 1,
                        tag + " " + ordinal, List.of("Capítulo", "Seção " + ordinal), next()));
            }
            materials.insertPassages(rows);
            return rows.stream().map(PassageRow::id).toList();
        });
    }

    /** An index change of the version over ordinals {@code first} to {@code last}, as job {@code jobId}. */
    void indexChange(UUID owner, UUID jobId, UUID versionId, int first, int last) {
        indexChange(owner, jobId, versionId, first, last, 0);
    }

    /** {@link #indexChange(UUID, UUID, UUID, int, int)} after {@code retries} retries of the retry sweep. */
    void indexChange(UUID owner, UUID jobId, UUID versionId, int first, int last, int retries) {
        as(owner, () -> {
            index.insertChange(new IndexChangeRow(jobId, owner, versionId, first, last, null, retries));
            return null;
        });
    }

    /** Discards the owner's version, its removal pending, as the GM's discard does. */
    void discard(UUID owner, UUID versionId) {
        as(owner, () -> materials.discardVersion(versionId, next()));
    }

    /** Ends the removal of the owner's discarded version: the state a finished {@code VersionRemoval} leaves. */
    void finishRemoval(UUID owner, UUID versionId) {
        as(owner, () -> materials.clearRemovalPending(versionId, next()));
    }

    /** Marks the owner's version as pending removal, its state unchanged. */
    void pendRemoval(UUID owner, UUID versionId) {
        as(owner, () -> {
            VersionRow before = materials.versions.get(versionId);
            materials.versions.put(versionId, new VersionRow(before.id(), before.ownerId(), before.materialId(),
                    before.state(), before.format(), before.sizeBytes(), before.createdAt(), next(),
                    before.pageTotal(), before.pipelineVersion(), before.failureCode(), true, before.indexRecipe()));
            return null;
        });
    }

    /** Deletes the owner's version, and its material with it when {@code material} is true. */
    void delete(UUID owner, UUID versionId, boolean material) {
        as(owner, () -> {
            UUID materialId = materials.versions.get(versionId).materialId();
            materials.deleteVersion(versionId);
            if (material) {
                materials.deleteMaterial(materialId);
            }
            return null;
        });
    }

    /**
     * Story 3.3: the material of the owner's version now belongs to {@code campaign} when it is not null, else to
     * {@code systems}, as a change of where it belongs writes it (its version is read first).
     */
    void move(UUID owner, UUID versionId, UUID campaign, UUID... systems) {
        as(owner, () -> {
            UUID materialId = materials.versions.get(versionId).materialId();
            long version = materials.materials.get(materialId).version();
            Instant at = next();
            return materials.changeBelonging(materialId, version, campaign, java.util.Arrays.stream(systems)
                    .map(system -> new MaterialSystemRow(Ids.newId(), owner, materialId, system, at)).toList(), at);
        });
    }

    /** A new system of the owner (Story 3.3), with no campaign. */
    UUID system(UUID owner, String name) {
        return as(owner, () -> library.createSystem(new Library.SystemFields(name, null, null)).id());
    }

    /** A new campaign of the owner's system {@code system} (Story 3.3). */
    UUID campaignOf(UUID owner, UUID system, String name) {
        return as(owner, () -> library.createCampaign(system, name).orElseThrow().id());
    }

    /** Moves the owner's version to {@code state}. */
    void state(UUID owner, UUID versionId, String state) {
        as(owner, () -> materials.changeVersionState(versionId, state, next()));
    }

    private Instant next() {
        clock = clock.plus(1, ChronoUnit.SECONDS);
        return clock;
    }

    private <T> T as(UUID owner, Supplier<T> work) {
        return OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status -> work.get()));
    }
}

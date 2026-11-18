package io.github.gmshield.materials.service;

import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.materials.data.IndexChangeRow;
import io.github.gmshield.materials.data.IndexStore;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Vault;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The library reads a campaign's scope is made of (Story 2.2, AD-9): outside {@code materials}, the one way to read
 * library items for a campaign, and only the {@code scope} module may use it ({@code ModuleBoundaryTest}). Every read
 * acts for the owner of the work ({@link OwnerContext}), in a read transaction (the caller's, when there is one), so
 * another owner's item reads exactly like an unknown one.
 *
 * <ul>
 *   <li>{@link #readyOfCampaign} and {@link #readyOfSystem}: the ids of the {@code ready} versions of the materials of a
 *       campaign ({@code material.campaign_id}), or that belong to a game system ({@code material_system}).</li>
 *   <li>{@link #pendingPassages}: the ids of the passages of these versions whose index change has not ended
 *       ({@link IndexChanges#unended}).</li>
 *   <li>{@link #passages}: passages by id, checked before anything is decrypted: every id must be a passage of the
 *       owner, of one of the given versions, and that version {@code ready}; otherwise nothing is read.</li>
 * </ul>
 *
 * Nothing here logs; a text or a section title never leaves but in {@link Passage}, whose {@code toString} names ids.
 */
@Service
@NamedInterface("service")
public class ScopeReads {

    private final MaterialStore materials;
    private final IndexStore index;
    private final Vault vault;
    private final Function<Collection<UUID>, Set<UUID>> unendedJobs;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    /** A passage read for a scope: its id, its version (its source), section path (outermost first), page and text. */
    @NamedInterface("service")
    public record Passage(UUID id, UUID versionId, List<String> sectionPath, int page, String text) {
        @Override
        public String toString() {
            return "Passage[id=" + id + ", versionId=" + versionId + ", page=" + page + "]";
        }
    }

    @Autowired
    ScopeReads(MaterialStore materials, IndexStore index, Vault vault, Jobs jobs,
               ObjectProvider<PlatformTransactionManager> transactions) {
        this(materials, index, vault, jobs::unended, transactions);
    }

    ScopeReads(MaterialStore materials, IndexStore index, Vault vault, Function<Collection<UUID>, Set<UUID>> unendedJobs,
               ObjectProvider<PlatformTransactionManager> transactions) {
        this.materials = materials;
        this.index = index;
        this.vault = vault;
        this.unendedJobs = unendedJobs;
        this.transactions = transactions;
    }

    /** The ids of the owner's {@code ready} versions of the campaign's materials, oldest first. */
    public List<UUID> readyOfCampaign(UUID campaignId) {
        return reading(() -> ids(materials.readyOfCampaign(campaignId)));
    }

    /** The ids of the owner's {@code ready} versions of the materials that belong to the game system, oldest first. */
    public List<UUID> readyOfSystem(UUID systemId) {
        return reading(() -> ids(materials.readyOfSystem(systemId)));
    }

    /**
     * The ids of the owner's passages of these versions whose ordinal falls in the run of an index change that has not
     * ended, by version as given, then by ordinal.
     */
    public List<UUID> pendingPassages(Collection<UUID> versionIds) {
        return reading(() -> {
            List<UUID> pending = new ArrayList<>();
            for (UUID versionId : new LinkedHashSet<>(versionIds)) {
                for (IndexChangeRow run : IndexChanges.unended(index, unendedJobs, versionId)) {
                    pending.addAll(materials.passageIds(versionId, run.firstOrdinal(), run.lastOrdinal()));
                }
            }
            return List.copyOf(new LinkedHashSet<>(pending));
        });
    }

    /**
     * The owner's passages with these ids, decrypted, in the order given, each once; empty, with nothing decrypted, when
     * any id is not a passage of the owner, not of one of {@code versionIds}, or of a version that is not {@code ready}.
     */
    public Optional<List<Passage>> passages(List<UUID> ids, Set<UUID> versionIds) {
        List<UUID> wanted = List.copyOf(new LinkedHashSet<>(ids));
        if (wanted.isEmpty()) {
            return Optional.of(List.of());
        }
        return reading(() -> {
            Map<UUID, PassageRow> found = materials.findPassages(wanted).stream()
                    .collect(Collectors.toMap(PassageRow::id, row -> row, (first, second) -> first));
            if (found.size() != wanted.size() || !found.keySet().containsAll(wanted)) {
                return Optional.empty();
            }
            Set<UUID> versions = found.values().stream().map(PassageRow::versionId).collect(Collectors.toSet());
            if (!versionIds.containsAll(versions) || !versions.stream().allMatch(this::ready)) {
                return Optional.empty();
            }
            List<Passage> read = new ArrayList<>(wanted.size());
            for (UUID id : wanted) {
                read.add(decrypt(found.get(id)));
            }
            return Optional.of(List.copyOf(read));
        });
    }

    private boolean ready(UUID versionId) {
        return materials.findVersion(versionId).map(VersionRow::state).filter(Materials.READY::equals).isPresent();
    }

    private Passage decrypt(PassageRow row) {
        Passages.Place place = Passages.place(vault.decrypt(Passages.place(row.id(), row.ownerId()), row.place()));
        return new Passage(row.id(), row.versionId(), place.sectionPath(), row.page(),
                vault.decryptText(Passages.text(row.id(), row.ownerId()), row.text()));
    }

    private static List<UUID> ids(List<VersionRow> versions) {
        return List.copyOf(new LinkedHashSet<>(versions.stream().map(VersionRow::id).toList()));
    }

    /** One read-only transaction (or the caller's) for the reads of one answer, as the bound owner. */
    private <T> T reading(Supplier<T> read) {
        OwnerContext.require();
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }
}

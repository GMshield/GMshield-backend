package io.github.gmshield.materials.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.library.data.CampaignRow;
import io.github.gmshield.library.data.LibraryStore;
import io.github.gmshield.library.service.Language;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.data.IndexChangeRow;
import io.github.gmshield.materials.data.IndexStore;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.MaterialSystemRow;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.scope.service.Indexing;
import io.github.gmshield.scope.service.ResolvedScope;
import io.github.gmshield.scope.service.ScopeSettings;
import io.github.gmshield.scope.service.ScopedPassage;
import io.github.gmshield.scope.service.Scopes;
import io.github.gmshield.scope.service.Tier;
import io.github.gmshield.shared.service.ApiException;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 2.2 against a real PostgreSQL (test mode): the store queries a campaign's scope is made of
 * ({@code readyOfCampaign}, {@code readyOfSystem}, {@code passageIds}, {@code findPassages}) in JPQL, filtered by the
 * owner, and {@link Scopes} over them: both tiers, only {@code ready} versions, the passages of an index change that
 * has not ended (real jobs), passages read by id with their tier, a refusal for any id out of the scope, and another
 * owner's campaign. The same rows without a database are in {@code ScopesTest}.
 */
@SpringBootTest(properties = {"GMSHIELD_MODE=test", "gmshield.jobs.sweep.enabled=false",
    "gmshield.materials.upload-sweep.enabled=false",
    "gmshield.materials.version-removal-sweep.enabled=false",
    "gmshield.files.dir=${java.io.tmpdir}/gmshield-scope-it"})
@ActiveProfiles("test-mode")
class ScopeIntegrationTest {

    @Autowired
    private Library library;
    @Autowired
    private LibraryStore libraryStore;
    @Autowired
    private MaterialStore store;
    @Autowired
    private IndexStore index;
    @Autowired
    private Jobs jobs;
    @Autowired
    private Vault vault;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private Scopes scopes;
    @Autowired
    private ScopeReads reads;

    private Instant clock = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Test
    void aCampaignsScopeIsResolvedAndReadOnPostgres() {
        UUID owner = Ids.newId();
        Library.Campaign campaign = as(owner, () -> {
            library.createStarter(Language.EN);
            return library.currentCampaign().orElseThrow();
        });
        UUID system = campaign.system().id();
        UUID otherCampaign = Ids.newId();
        as(owner, () -> {
            libraryStore.insertCampaign(new CampaignRow(otherCampaign, owner, system,
                    vault.encryptText(new Binding("campaign", "name", otherCampaign, owner), "Outra"), next()));
            return null;
        });
        List<UUID> a = material(owner, campaign.id(), null, "superseded", "ready");
        List<UUID> b = material(owner, null, system, "ready");
        UUID c = material(owner, otherCampaign, null, "ready").getFirst();
        UUID reviewing = material(owner, campaign.id(), null, "needs_review").getFirst();
        List<UUID> aPassages = passages(owner, a.get(1), "A", 450);
        List<UUID> bPassages = passages(owner, b.getFirst(), "B", 2);
        List<UUID> cPassages = passages(owner, c, "C", 1);
        List<UUID> oldPassages = passages(owner, a.getFirst(), "Old", 1);
        List<UUID> reviewingPassages = passages(owner, reviewing, "R", 1);
        List<UUID> changes = as(owner, () -> List.of(change(owner, a.get(1), 0, 199), change(owner, a.get(1), 200, 399),
                change(owner, a.get(1), 400, 449)));
        try {
            as(owner, () -> jobs.cancel(changes.getFirst()));
            as(owner, () -> jobs.cancel(changes.get(2)));
            assertScope(owner, campaign, system, a, b, aPassages, bPassages, cPassages, oldPassages,
                    reviewingPassages);
        } finally {
            // Leave no job behind for the other tests' ingestion lane, whatever happened.
            for (UUID change : changes) {
                as(owner, () -> jobs.cancel(change));
            }
        }
    }

    /** The store queries and the scope over the library the test made (C of another campaign of the same system). */
    private void assertScope(UUID owner, Library.Campaign campaign, UUID system, List<UUID> a, List<UUID> b,
                             List<UUID> aPassages, List<UUID> bPassages, List<UUID> cPassages,
                             List<UUID> oldPassages, List<UUID> reviewingPassages) {
        // The store queries, filtered by the owner.
        assertThat(as(owner, () -> reads.readyOfCampaign(campaign.id()))).containsExactly(a.get(1));
        assertThat(as(owner, () -> reads.readyOfSystem(system))).containsExactly(b.getFirst());
        assertThat(as(owner, () -> store.passageIds(a.get(1), 3, 5))).containsExactlyElementsOf(aPassages.subList(3, 6));
        assertThat(as(owner, () -> store.countPassages(a.get(1), 3, 5))).isEqualTo(3L);
        assertThat(as(owner, () -> store.countPassages(a.get(1), 0, 999))).isEqualTo(450L);
        assertThat(as(owner, () -> store.findPassages(List.of(bPassages.get(1), cPassages.getFirst(), Ids.newId()))))
                .extracting(PassageRow::id).containsExactlyInAnyOrder(bPassages.get(1), cPassages.getFirst());
        UUID other = Ids.newId();
        assertThat(as(other, () -> store.readyOfCampaign(campaign.id()))).isEmpty();
        assertThat(as(other, () -> store.readyOfSystem(system))).isEmpty();
        assertThat(as(other, () -> store.passageIds(a.get(1), 0, 449))).isEmpty();
        assertThat(as(other, () -> store.countPassages(a.get(1), 0, 449))).isZero();
        assertThat(as(other, () -> store.findPassages(aPassages))).isEmpty();

        // The scope: A in the campaign tier, B in the system tier, C absent; the second run of A pending.
        ResolvedScope scope = as(owner, () -> scopes.resolve(campaign.id(), ScopeSettings.STARTING));
        assertThat(scope.sources(Tier.CAMPAIGN)).containsExactly(a.get(1));
        assertThat(scope.sources(Tier.SYSTEM)).containsExactly(b.getFirst());
        assertThat(scope.pending()).containsExactlyElementsOf(aPassages.subList(200, 400));
        // The indexing count: the 200 passages of A's pending run.
        assertThat(as(owner, () -> scopes.indexing(campaign.id(), ScopeSettings.STARTING)))
                .contains(new Indexing(200));

        List<ScopedPassage> read = as(owner, () -> scopes.passages(scope, List.of(bPassages.getFirst(),
                aPassages.get(7), bPassages.getFirst())));
        assertThat(read).extracting(ScopedPassage::id, ScopedPassage::tier, ScopedPassage::text, ScopedPassage::page)
                .containsExactly(tuple(bPassages.getFirst(), Tier.SYSTEM, "B 0", 1),
                        tuple(aPassages.get(7), Tier.CAMPAIGN, "A 7", 1));
        assertThat(read.get(1).sectionPath()).containsExactly("Capítulo", "Seção 7");
        for (UUID outside : List.of(cPassages.getFirst(), oldPassages.getFirst(), reviewingPassages.getFirst(),
                Ids.newId())) {
            assertThatThrownBy(() -> as(owner, () -> scopes.passages(scope, List.of(aPassages.getFirst(), outside))))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.problem().code()).isEqualTo("scope.passage_refused"));
        }
        assertThatThrownBy(() -> as(other, () -> scopes.resolve(campaign.id(), ScopeSettings.STARTING)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.problem().status()).isEqualTo(404));
    }

    private UUID change(UUID owner, UUID versionId, int first, int last) {
        return jobs.create(IndexChanges.KIND, job -> index.insertChange(new IndexChangeRow(job.id(), owner, versionId,
                first, last, null))).id();
    }

    private List<UUID> material(UUID owner, UUID campaign, UUID system, String... states) {
        return as(owner, () -> {
            UUID materialId = Ids.newId();
            store.insertMaterial(new MaterialRow(materialId, owner, vault.encryptText(new Binding(Uploads.MATERIAL,
                    Uploads.FIELD_NAME, materialId, owner), "Livro"), next(), "rulebook", null, campaign));
            if (system != null) {
                store.insertMaterialSystem(new MaterialSystemRow(Ids.newId(), owner, materialId, system, next()));
            }
            List<UUID> versions = new ArrayList<>();
            for (String state : states) {
                UUID versionId = Ids.newId();
                Instant at = next();
                store.insertVersion(new VersionRow(versionId, owner, materialId, state, "pdf", 1000L, at, at));
                versions.add(versionId);
            }
            return List.copyOf(versions);
        });
    }

    private List<UUID> passages(UUID owner, UUID versionId, String tag, int count) {
        return as(owner, () -> {
            List<PassageRow> rows = new ArrayList<>();
            for (int ordinal = 0; ordinal < count; ordinal++) {
                UUID id = Ids.newId();
                String text = tag + " " + ordinal;
                rows.add(new PassageRow(id, owner, versionId, ordinal, ordinal / 10 + 1,
                        vault.encryptText(Passages.text(id, owner), text),
                        vault.encrypt(Passages.place(id, owner), Passages.placeJson(new Passages.Place(
                                List.of("Capítulo", "Seção " + ordinal), null, List.of(0, text.length())))), next()));
            }
            store.insertPassages(rows);
            return rows.stream().map(PassageRow::id).toList();
        });
    }

    private Instant next() {
        clock = clock.plus(1, ChronoUnit.MILLIS);
        return clock;
    }

    private <T> T as(UUID owner, Supplier<T> work) {
        return OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status -> work.get()));
    }
}

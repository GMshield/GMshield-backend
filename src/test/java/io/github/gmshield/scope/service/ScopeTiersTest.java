package io.github.gmshield.scope.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.gmshield.jobs.data.MutableClock;
import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.library.service.SystemDeletionHandler;
import io.github.gmshield.library.service.TestLibraries;
import io.github.gmshield.materials.data.InMemoryIndexStore;
import io.github.gmshield.materials.data.InMemoryMaterialStore;
import io.github.gmshield.materials.service.TestMaterials;
import io.github.gmshield.scope.data.InMemoryScopeStore;
import io.github.gmshield.shared.data.InMemoryTransactionManager;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.TestVaults;
import io.github.gmshield.vault.service.Vault;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

/**
 * Story 3.5 without a database: the three tiers and the material switches, asserted on the source and passage ids
 * handed over, with no model call: each tier on and off, a single material off in each tier, the other tier holding
 * another campaign's and another system's material (an archived campaign's too), and nothing of another owner. Then the
 * stored settings ({@link CampaignScopes}): the starting read at version 0, a change, a stale one, an unknown
 * material, another owner, and the deletion handler. The flow through the API is in {@code ConversationFlowTest}.
 */
class ScopeTiersTest {

    private final InMemoryMaterialStore materials = new InMemoryMaterialStore();
    private final InMemoryIndexStore index = new InMemoryIndexStore(materials);
    private final InMemoryLibraryStore libraryStore = new InMemoryLibraryStore();
    private final InMemoryTransactionManager transactions = new InMemoryTransactionManager();
    private final InMemoryScopeStore store = new InMemoryScopeStore();
    private final Vault vault = TestVaults.handingOutTokenKeys(new java.util.concurrent.CopyOnWriteArrayList<>());
    private ScopeFixture fixture;
    private Library library;
    private Scopes scopes;
    private CampaignScopes campaignScopes;
    /** The jobs that have not ended, as the jobs module would answer. */
    private final Set<UUID> unended = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final StaticListableBeanFactory beans = new StaticListableBeanFactory();
    private final StubCampaignArtifacts artifacts = new StubCampaignArtifacts();
    /** The campaigns' overrides, as the knowledge module would answer (Story 5.5 part A). */
    private final StubCampaignOverrides overrides = new StubCampaignOverrides();

    @BeforeEach
    void wire() {
        beans.addBean("transactions", transactions);
        beans.addBean("clock", new MutableClock(Instant.parse("2026-11-21T12:00:00Z")));
        library = TestLibraries.library(libraryStore, vault, beans.getBeanProvider(PlatformTransactionManager.class),
                beans.getBeanProvider(Clock.class));
        scopes = new Scopes(library, TestMaterials.scopeReads(materials, index, vault,
                ids -> ids.stream().filter(unended::contains).collect(java.util.stream.Collectors.toSet()),
                ids -> Set.of(), beans.getBeanProvider(PlatformTransactionManager.class)), TestMaterials.NO_RECORDS,
                TestMaterials.CORE_KEYS, TestMaterials.NO_TERMS, artifacts, overrides, vault,
                beans.getBeanProvider(PlatformTransactionManager.class));
        campaignScopes = new CampaignScopes(store, scopes, library,
                beans.getBeanProvider(PlatformTransactionManager.class), beans.getBeanProvider(Clock.class));
        fixture = new ScopeFixture(library, libraryStore, materials, index, vault, transactions);
    }

    /**
     * The GM's library: A of the campaign, B of its system, C of another campaign of that system, D of a campaign of
     * another system, E of that other system; each one ready version with two passages.
     */
    private record World(ScopeFixture.Starter gm, UUID a, UUID b, UUID c, UUID d, UUID e, UUID otherCampaign,
                         UUID farCampaign) {

        List<UUID> all() {
            return List.of(a, b, c, d, e);
        }
    }

    private World world() {
        ScopeFixture.Starter gm = fixture.starter();
        UUID a = fixture.material(gm.owner(), gm.campaign(), null, "ready").getFirst();
        UUID b = fixture.material(gm.owner(), null, gm.system(), "ready").getFirst();
        UUID otherCampaign = fixture.otherCampaign(gm);
        UUID c = fixture.material(gm.owner(), otherCampaign, null, "ready").getFirst();
        UUID farSystem = fixture.system(gm.owner(), "Outro sistema");
        UUID farCampaign = fixture.campaignOf(gm.owner(), farSystem, "Campanha distante");
        UUID d = fixture.material(gm.owner(), farCampaign, null, "ready").getFirst();
        UUID e = fixture.material(gm.owner(), null, farSystem, "ready").getFirst();
        for (UUID version : List.of(a, b, c, d, e)) {
            fixture.passages(gm.owner(), version, "P", 2);
        }
        return new World(gm, a, b, c, d, e, otherCampaign, farCampaign);
    }

    private UUID materialOf(UUID version) {
        return materials.versions.get(version).materialId();
    }

    private UUID passageOf(UUID version) {
        return materials.passages.values().stream().filter(row -> row.versionId().equals(version)).findFirst()
                .orElseThrow().id();
    }

    private ResolvedScope resolve(World world, ScopeSettings settings) {
        return as(world.gm().owner(), () -> scopes.resolve(world.gm().campaign(), settings));
    }

    // --- the tiers

    @Test
    void theOtherTierHoldsAnotherCampaignsAndAnotherSystemsMaterialsOnlyWhenItIsOn() {
        World w = world();

        ResolvedScope starting = resolve(w, ScopeSettings.STARTING);
        ResolvedScope all = resolve(w, new ScopeSettings(true, true, true, false));

        assertThat(starting.sources(Tier.CAMPAIGN)).containsExactly(w.a());
        assertThat(starting.sources(Tier.SYSTEM)).containsExactly(w.b());
        assertThat(starting.sources(Tier.OTHER)).isEmpty();
        assertThat(starting.allowedSources()).doesNotContain(w.c(), w.d(), w.e());
        assertThat(all.sources(Tier.CAMPAIGN)).containsExactly(w.a());
        assertThat(all.sources(Tier.SYSTEM)).containsExactly(w.b());
        assertThat(all.sources(Tier.OTHER)).containsExactly(w.c(), w.d(), w.e());
        assertThat(all.tierOf(w.d())).contains(Tier.OTHER);
        assertThat(all.materialOf(w.e())).contains(materialOf(w.e()));
        assertThat(all.toString()).contains("otherSources=3");
        // The passages read are labelled with their tier; with the other tier off, one of it is refused.
        List<UUID> ids = List.of(passageOf(w.c()), passageOf(w.a()), passageOf(w.e()));
        assertThat(as(w.gm().owner(), () -> scopes.passages(all, ids))).extracting(ScopedPassage::id,
                        ScopedPassage::tier)
                .containsExactly(tuple(ids.get(0), Tier.OTHER), tuple(ids.get(1), Tier.CAMPAIGN),
                        tuple(ids.get(2), Tier.OTHER));
        assertRefused(w, starting, List.of(passageOf(w.c())));
        assertRefused(w, starting, List.of(passageOf(w.e())));
        // A citation of an out-of-scope passage is dropped.
        assertThat(as(w.gm().owner(), () -> scopes.references(starting, List.of(passageOf(w.d()), passageOf(w.a())))))
                .extracting(CitedPlace::versionId).containsExactly(w.a());
        // The question input lists them under "other", in tier order.
        ResolvedScope pending = resolve(w, new ScopeSettings(true, true, true, false));
        JsonNode input = as(w.gm().owner(), () -> scopes.questionInput(pending, "Regra?"));
        assertThat(input.path("tiers")).extracting(tier -> tier.path("tier").asString())
                .containsExactly("campaign", "system", "other");
        assertThat(input.path("tiers").get(2).path("sources")).extracting(JsonNode::asString)
                .containsExactly(w.c().toString(), w.d().toString(), w.e().toString());
    }

    @Test
    void theOtherTiersPassagesOfAnIndexChangeThatHasNotEndedArePendingInItsTier() {
        World w = world();
        UUID far = fixture.material(w.gm().owner(), w.farCampaign(), null, "ready").getFirst();
        List<UUID> farPassages = fixture.passages(w.gm().owner(), far, "F", 30);
        UUID run = Ids.newId();
        fixture.indexChange(w.gm().owner(), run, far, 0, 19);
        unended.add(run);
        ScopeSettings withOther = new ScopeSettings(true, true, true, false);

        ResolvedScope scope = resolve(w, withOther);

        assertThat(scope.sources(Tier.OTHER)).contains(far);
        assertThat(scope.pending(Tier.CAMPAIGN)).isEmpty();
        assertThat(scope.pending(Tier.SYSTEM)).isEmpty();
        assertThat(scope.pending(Tier.OTHER)).containsExactlyElementsOf(farPassages.subList(0, 20));
        JsonNode input = as(w.gm().owner(), () -> scopes.questionInput(scope, "Regra?"));
        assertThat(input.path("tiers").get(2).path("tier").asString()).isEqualTo("other");
        assertThat(input.path("tiers").get(2).path("pending")).extracting(JsonNode::asString)
                .containsExactlyElementsOf(farPassages.subList(0, 20).stream().map(UUID::toString).toList());
        assertThat(as(w.gm().owner(), () -> scopes.status(w.gm().campaign(), withOther)).indexing())
                .isEqualTo(new Indexing(20));
        // With the other tier off, nothing of it pends or is counted.
        assertThat(resolve(w, ScopeSettings.STARTING).pending()).isEmpty();
        assertThat(as(w.gm().owner(), () -> scopes.status(w.gm().campaign(), ScopeSettings.STARTING)).indexing())
                .isNull();
    }

    @Test
    void eachTierOffHasNoSourceAndTheOthersKeepTheirs() {
        World w = world();

        ResolvedScope noCampaign = resolve(w, new ScopeSettings(false, true, true, false));
        ResolvedScope noSystem = resolve(w, new ScopeSettings(true, false, true, false));
        ResolvedScope onlyOther = resolve(w, new ScopeSettings(false, false, true, false));
        ResolvedScope none = resolve(w, new ScopeSettings(false, false, false, true));

        assertThat(noCampaign.sources(Tier.CAMPAIGN)).isEmpty();
        assertThat(noCampaign.sources(Tier.SYSTEM)).containsExactly(w.b());
        // The campaign's own material never falls into the other tier, whatever its switch.
        assertThat(noCampaign.sources(Tier.OTHER)).containsExactly(w.c(), w.d(), w.e());
        assertThat(noSystem.sources(Tier.SYSTEM)).isEmpty();
        assertThat(noSystem.sources(Tier.OTHER)).doesNotContain(w.b());
        assertThat(onlyOther.allowedSources()).containsExactlyInAnyOrder(w.c(), w.d(), w.e());
        assertThat(none.allowedSources()).isEmpty();
        assertRefused(w, noCampaign, List.of(passageOf(w.a())));
        assertRefused(w, noSystem, List.of(passageOf(w.b())));
        assertRefused(w, onlyOther, List.of(passageOf(w.a()), passageOf(w.c())));
    }

    @Test
    void aMaterialSwitchedOffIsLeftOutOfWhicheverTierItFallsIn() {
        World w = world();
        Set<UUID> off = Set.of(materialOf(w.b()), materialOf(w.d()));

        ResolvedScope scope = resolve(w, new ScopeSettings(true, true, true, false, off));
        ResolvedScope campaignOff = resolve(w, new ScopeSettings(true, true, false, false,
                Set.of(materialOf(w.a()))));

        assertThat(scope.sources(Tier.CAMPAIGN)).containsExactly(w.a());
        assertThat(scope.sources(Tier.SYSTEM)).isEmpty();
        assertThat(scope.sources(Tier.OTHER)).containsExactly(w.c(), w.e());
        assertRefused(w, scope, List.of(passageOf(w.b())));
        assertRefused(w, scope, List.of(passageOf(w.d())));
        assertThat(campaignOff.sources(Tier.CAMPAIGN)).isEmpty();
        assertThat(campaignOff.sources(Tier.SYSTEM)).containsExactly(w.b());
        assertRefused(w, campaignOff, List.of(passageOf(w.a())));
        // A material id that is gone or in no tier changes nothing.
        ResolvedScope unknownOff = resolve(w, new ScopeSettings(true, true, false, false, Set.of(Ids.newId())));
        assertThat(unknownOff.allowedSources()).containsExactlyInAnyOrder(w.a(), w.b());
        // The settings' text counts the ids, never lists them.
        assertThat(scope.settings().toString()).contains("materialsOff=2").doesNotContain(materialOf(w.b()).toString());
    }

    @Test
    void anArchivedCampaignsMaterialIsInTheOtherTierAndAnotherOwnersNever() {
        World w = world();
        as(w.gm().owner(), () -> library.changeCampaign(w.otherCampaign(), 0, null, true).orElseThrow());
        ScopeFixture.Starter them = fixture.starter();
        UUID theirs = fixture.material(them.owner(), them.campaign(), null, "ready").getFirst();
        fixture.passages(them.owner(), theirs, "T", 1);

        ResolvedScope scope = resolve(w, new ScopeSettings(true, true, true, false));

        assertThat(scope.sources(Tier.OTHER)).contains(w.c()).doesNotContain(theirs);
        assertRefused(w, scope, List.of(passageOf(theirs)));
        assertThat(as(them.owner(), () -> scopes.resolve(them.campaign(), new ScopeSettings(true, true, true, false)))
                .allowedSources()).containsExactly(theirs);
    }

    @Test
    void theMembersAreEveryTiersMaterialsWhateverTheSwitchesInTierOrderThenNewestFirst() {
        World w = world();
        UUID newerSystem = fixture.material(w.gm().owner(), null, w.gm().system(), "ready").getFirst();
        UUID notReady = fixture.material(w.gm().owner(), w.gm().campaign(), null, "needs_review").getFirst();

        List<Scopes.Member> members = as(w.gm().owner(), () -> scopes.members(w.gm().campaign()));

        assertThat(members).extracting(Scopes.Member::materialId, Scopes.Member::tier).containsExactly(
                tuple(materialOf(w.a()), Tier.CAMPAIGN), tuple(materialOf(newerSystem), Tier.SYSTEM),
                tuple(materialOf(w.b()), Tier.SYSTEM), tuple(materialOf(w.e()), Tier.OTHER),
                tuple(materialOf(w.d()), Tier.OTHER), tuple(materialOf(w.c()), Tier.OTHER));
        assertThat(members).extracting(Scopes.Member::materialId).doesNotContain(materialOf(notReady));
        assertThat(members.getFirst().name()).isEqualTo(ScopeFixture.MATERIAL_NAME);
        assertThat(members.getFirst().toString()).doesNotContain(ScopeFixture.MATERIAL_NAME);
    }

    // --- the stored settings

    @Test
    void aCampaignNeverChangedReadsAsTheStartingSettingsAtVersionZeroWithItsMaterialsOn() {
        World w = world();

        CampaignScopes.View view = as(w.gm().owner(), () -> campaignScopes.view(w.gm().campaign())).orElseThrow();

        assertThat(view.version()).isZero();
        assertThat(view.settings()).isEqualTo(ScopeSettings.STARTING);
        assertThat(view.materials()).hasSize(5).allSatisfy(material -> assertThat(material.on()).isTrue());
        assertThat(as(w.gm().owner(), () -> campaignScopes.settings(w.gm().campaign())))
                .isEqualTo(ScopeSettings.STARTING);
        assertThat(store.rows).isEmpty();
    }

    @Test
    void aChangeIsStoredOnItsVersionAndRaisesItAndAStaleOneChangesNothing() {
        World w = world();
        UUID offB = materialOf(w.b());

        CampaignScopes.View changed = as(w.gm().owner(), () -> campaignScopes.change(w.gm().campaign(),
                new CampaignScopes.Change(0, true, true, true, true, List.of(offB, offB)))).orElseThrow();

        assertThat(changed.version()).isEqualTo(1);
        assertThat(changed.settings()).isEqualTo(new ScopeSettings(true, true, true, true, Set.of(offB)));
        assertThat(changed.materials()).filteredOn(material -> !material.on()).extracting(CampaignScopes.Material::id)
                .containsExactly(offB);
        assertThat(as(w.gm().owner(), () -> campaignScopes.settings(w.gm().campaign())).otherTier()).isTrue();
        // A stale version is a conflict, and nothing changes.
        assertProblem(() -> as(w.gm().owner(), () -> campaignScopes.change(w.gm().campaign(),
                new CampaignScopes.Change(0, false, false, false, false, List.of()))), 409, "request.stale_version");
        // The version is told before the ids: a stale change naming a material in no tier is a conflict, not unknown.
        assertProblem(() -> as(w.gm().owner(), () -> campaignScopes.change(w.gm().campaign(),
                new CampaignScopes.Change(0, true, true, false, false, List.of(Ids.newId())))), 409,
                "request.stale_version");
        assertThat(as(w.gm().owner(), () -> campaignScopes.view(w.gm().campaign())).orElseThrow().version())
                .isEqualTo(1);
        // The next change lists only the ids it is given: B is on again.
        CampaignScopes.View again = as(w.gm().owner(), () -> campaignScopes.change(w.gm().campaign(),
                new CampaignScopes.Change(1, true, true, false, false, List.of()))).orElseThrow();
        assertThat(again.version()).isEqualTo(2);
        assertThat(again.settings()).isEqualTo(ScopeSettings.STARTING);
    }

    @Test
    void aMaterialOfAnotherOwnerOrInNoTierIsUnknownAndAnotherOwnersCampaignIsNotFound() {
        World w = world();
        ScopeFixture.Starter them = fixture.starter();
        UUID theirs = fixture.material(them.owner(), them.campaign(), null, "ready").getFirst();
        UUID unready = fixture.material(w.gm().owner(), w.gm().campaign(), null, "processing").getFirst();

        for (UUID id : List.of(materialOf(theirs), materialOf(unready), Ids.newId())) {
            assertProblem(() -> as(w.gm().owner(), () -> campaignScopes.change(w.gm().campaign(),
                    new CampaignScopes.Change(0, true, true, false, false, List.of(id)))), 400,
                    "scope.material_unknown");
        }
        assertThat(store.rows).isEmpty();
        assertThat(as(them.owner(), () -> campaignScopes.view(w.gm().campaign()))).isEmpty();
        assertThat(as(them.owner(), () -> campaignScopes.change(w.gm().campaign(),
                new CampaignScopes.Change(0, true, true, true, false, List.of())))).isEmpty();
        assertThat(as(w.gm().owner(), () -> campaignScopes.view(Ids.newId()))).isEmpty();
        assertThat(store.rows).isEmpty();
    }

    @Test
    void theDeletionHandlerRemovesTheGoingCampaignsSettingsAndTheGoingMaterialsSwitches() {
        World w = world();
        UUID offB = materialOf(w.b());
        as(w.gm().owner(), () -> campaignScopes.change(w.gm().campaign(),
                new CampaignScopes.Change(0, true, true, true, false, List.of(offB, materialOf(w.c())))));
        as(w.gm().owner(), () -> campaignScopes.change(w.otherCampaign(),
                new CampaignScopes.Change(0, true, true, true, false, List.of(offB))));
        ScopeDeletion deletion = new ScopeDeletion(store, beans.getBeanProvider(PlatformTransactionManager.class));

        assertThat(deletion.count(new SystemDeletionHandler.Going(null, List.of(w.gm().campaign())))).isZero();
        assertThat(deletion.prepare(SystemDeletionHandler.Going.ofMaterial(offB))).isEmpty();
        as(w.gm().owner(), () -> {
            deletion.remove(SystemDeletionHandler.Going.ofMaterial(offB));
            return null;
        });

        assertThat(as(w.gm().owner(), () -> campaignScopes.settings(w.gm().campaign())).materialsOff())
                .containsExactly(materialOf(w.c()));
        assertThat(as(w.gm().owner(), () -> campaignScopes.settings(w.otherCampaign())).materialsOff()).isEmpty();
        as(w.gm().owner(), () -> {
            deletion.remove(SystemDeletionHandler.Going.ofVersion(w.c()));
            deletion.remove(new SystemDeletionHandler.Going(null, List.of(w.gm().campaign())));
            return null;
        });
        assertThat(store.rows).containsOnlyKeys(w.otherCampaign());
        // Repeating it is safe.
        as(w.gm().owner(), () -> {
            deletion.remove(new SystemDeletionHandler.Going(null, List.of(w.gm().campaign())));
            return null;
        });
        assertThat(store.rows).containsOnlyKeys(w.otherCampaign());
    }

    private void assertRefused(World w, ResolvedScope scope, List<UUID> ids) {
        assertThatThrownBy(() -> as(w.gm().owner(), () -> scopes.passages(scope, ids)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.problem().code()).isEqualTo("scope.passage_refused"));
    }

    private static void assertProblem(Runnable work, int status, String code) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.problem().status()).isEqualTo(status);
            assertThat(e.problem().code()).isEqualTo(code);
        });
    }

    private static <T> T as(UUID owner, Callable<T> work) {
        try {
            return OwnerContext.callAs(owner, work::call);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}

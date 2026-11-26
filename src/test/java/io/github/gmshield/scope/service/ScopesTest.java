package io.github.gmshield.scope.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.gmshield.jobs.data.MutableClock;
import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.library.service.TestLibraries;
import io.github.gmshield.materials.data.InMemoryIndexStore;
import io.github.gmshield.materials.data.InMemoryMaterialStore;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.service.TestMaterials;
import io.github.gmshield.shared.data.InMemoryTransactionManager;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.IntegrityException;
import io.github.gmshield.vault.service.TestVaults;
import io.github.gmshield.vault.service.Vault;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

/**
 * Story 2.2 without a database: every row of the I/O matrix the resolver answers, on the in-memory stores, asserted on
 * source and passage ids. The tiers, the versions a tier takes, the pending passages, a read within the scope, its
 * refusal before anything is decrypted, the settings, and another owner's campaign; Story 2.3's question input. No
 * model is called. The same queries against PostgreSQL are in {@code ScopeIntegrationTest}; the internal endpoint in
 * {@code ScopedPassagesFlowTest}.
 */
class ScopesTest {

    private final InMemoryMaterialStore materials = new InMemoryMaterialStore();
    private final InMemoryIndexStore index = new InMemoryIndexStore(materials);
    private final InMemoryLibraryStore libraryStore = new InMemoryLibraryStore();
    private final InMemoryTransactionManager transactions = new InMemoryTransactionManager();
    /** Every token key buffer the vault handed out, to see that the question input zeroed it. */
    private final List<byte[]> handedKeys = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Vault vault = TestVaults.handingOutTokenKeys(handedKeys);
    /** The jobs that have not ended, as the jobs module would answer. */
    private final Set<UUID> unended = ConcurrentHashMap.newKeySet();
    /** The jobs that ended failed, as the jobs module would answer. */
    private final Set<UUID> failed = ConcurrentHashMap.newKeySet();
    /** How many times the jobs module was asked which index changes are unended or failed. */
    private final AtomicInteger jobsAsked = new AtomicInteger();
    private ScopeFixture fixture;
    private Scopes scopes;

    @BeforeEach
    void wire() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("transactions", transactions);
        beans.addBean("clock", new MutableClock(Instant.parse("2026-11-18T12:00:00Z")));
        Library library = TestLibraries.library(libraryStore, vault,
                beans.getBeanProvider(PlatformTransactionManager.class), beans.getBeanProvider(Clock.class));
        scopes = new Scopes(library, TestMaterials.scopeReads(materials, index, vault,
                ids -> {
                    jobsAsked.incrementAndGet();
                    return ids.stream().filter(unended::contains).collect(Collectors.toSet());
                },
                ids -> {
                    jobsAsked.incrementAndGet();
                    return ids.stream().filter(failed::contains).collect(Collectors.toSet());
                },
                beans.getBeanProvider(PlatformTransactionManager.class)), TestMaterials.NO_RECORDS,
                TestMaterials.CORE_KEYS, TestMaterials.NO_TERMS, vault, beans.getBeanProvider(PlatformTransactionManager.class));
        fixture = new ScopeFixture(library, libraryStore, materials, index, vault, transactions);
    }

    /** The I/O matrix's library: A of the campaign, B of its system, C of another campaign of the same system. */
    private record Library2(ScopeFixture.Starter gm, UUID a, UUID b, UUID c, List<UUID> aPassages,
                            List<UUID> bPassages, List<UUID> cPassages) {
    }

    private Library2 twoTiers() {
        ScopeFixture.Starter gm = fixture.starter();
        UUID a = fixture.material(gm.owner(), gm.campaign(), null, "ready").getFirst();
        UUID b = fixture.material(gm.owner(), null, gm.system(), "ready").getFirst();
        UUID c = fixture.material(gm.owner(), fixture.otherCampaign(gm), null, "ready").getFirst();
        return new Library2(gm, a, b, c, fixture.passages(gm.owner(), a, "A", 3),
                fixture.passages(gm.owner(), b, "B", 2), fixture.passages(gm.owner(), c, "C", 2));
    }

    // --- the tiers

    @Test
    void theCampaignsMaterialIsInTheCampaignTierItsSystemsInTheSystemTierAndAnotherCampaignsInNone() {
        Library2 lib = twoTiers();

        ResolvedScope scope = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), ScopeSettings.STARTING));

        assertThat(scope.sources(Tier.CAMPAIGN)).containsExactly(lib.a());
        assertThat(scope.sources(Tier.SYSTEM)).containsExactly(lib.b());
        assertThat(scope.allowedSources()).containsExactlyInAnyOrder(lib.a(), lib.b()).doesNotContain(lib.c());
        assertThat(scope.tierOf(lib.a())).contains(Tier.CAMPAIGN);
        assertThat(scope.tierOf(lib.b())).contains(Tier.SYSTEM);
        assertThat(scope.tierOf(lib.c())).isEmpty();
        assertThat(scope.pending()).isEmpty();
        assertThat(scope.ownerId()).isEqualTo(lib.gm().owner());
        assertThat(scope.campaignId()).isEqualTo(lib.gm().campaign());
        assertThat(scope.toString()).doesNotContain(lib.a().toString()).contains("campaignSources=1");
        // The other campaign's scope has C in its campaign tier and B in its system tier.
        UUID other = materials.materials.get(materials.versions.get(lib.c()).materialId()).campaignId();
        ResolvedScope theirs = as(lib.gm().owner(), () -> scopes.resolve(other, ScopeSettings.STARTING));
        assertThat(theirs.sources(Tier.CAMPAIGN)).containsExactly(lib.c());
        assertThat(theirs.sources(Tier.SYSTEM)).containsExactly(lib.b());
    }

    @Test
    void onlyTheReadyVersionOfAMaterialIsAllowed() {
        ScopeFixture.Starter gm = fixture.starter();
        List<UUID> accepted = fixture.material(gm.owner(), gm.campaign(), null, "superseded", "ready");
        List<UUID> reviewing = fixture.material(gm.owner(), gm.campaign(), null, "needs_review");
        List<UUID> systemFailed = fixture.material(gm.owner(), null, gm.system(), "ready", "superseded", "failed",
                "processing");
        List<UUID> discarded = fixture.material(gm.owner(), null, gm.system(), "discarded");

        ResolvedScope scope = as(gm.owner(), () -> scopes.resolve(gm.campaign(), ScopeSettings.STARTING));

        assertThat(scope.sources(Tier.CAMPAIGN)).containsExactly(accepted.get(1));
        assertThat(scope.sources(Tier.SYSTEM)).containsExactly(systemFailed.getFirst());
        assertThat(scope.allowedSources()).doesNotContain(accepted.getFirst(), reviewing.getFirst(),
                systemFailed.get(1), systemFailed.get(2), systemFailed.get(3), discarded.getFirst());
    }

    @Test
    void aSourceOfTheCampaignThatAlsoBelongsToItsSystemIsInTheCampaignTierOnly() {
        ScopeFixture.Starter gm = fixture.starter();
        UUID both = fixture.material(gm.owner(), gm.campaign(), gm.system(), "ready").getFirst();

        ResolvedScope scope = as(gm.owner(), () -> scopes.resolve(gm.campaign(), ScopeSettings.STARTING));

        assertThat(scope.sources(Tier.CAMPAIGN)).containsExactly(both);
        assertThat(scope.sources(Tier.SYSTEM)).isEmpty();
        assertThat(as(gm.owner(), () -> scopes.resolve(gm.campaign(), new ScopeSettings(false, true, false, false)))
                .sources(Tier.SYSTEM)).containsExactly(both);
    }

    @Test
    void aTierSwitchedOffHasNoSourceAndTheOtherTierIsAccepted() {
        Library2 lib = twoTiers();

        ResolvedScope systemOnly = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(),
                new ScopeSettings(false, true, false, false)));
        ResolvedScope campaignOnly = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(),
                new ScopeSettings(true, false, false, true)));
        ResolvedScope none = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(),
                new ScopeSettings(false, false, false, false)));

        assertThat(systemOnly.sources(Tier.CAMPAIGN)).isEmpty();
        assertThat(systemOnly.sources(Tier.SYSTEM)).containsExactly(lib.b());
        assertThat(campaignOnly.sources(Tier.CAMPAIGN)).containsExactly(lib.a());
        assertThat(campaignOnly.sources(Tier.SYSTEM)).isEmpty();
        assertThat(campaignOnly.settings().modelKnowledge()).isTrue();
        assertThat(none.allowedSources()).isEmpty();
        assertThat(ScopeSettings.STARTING).isEqualTo(new ScopeSettings(true, true, false, false, Set.of()));
        // Story 3.5: the other tier is no longer refused.
        assertThat(new ScopeSettings(true, true, true, false).otherTier()).isTrue();
        // With the system tier off, B's passages are refused.
        assertRefused(lib.gm().owner(), campaignOnly, List.of(lib.aPassages().getFirst(), lib.bPassages().getFirst()));
    }

    // --- pending

    @Test
    void thePassagesOfAnIndexChangeThatHasNotEndedArePending() {
        ScopeFixture.Starter gm = fixture.starter();
        UUID version = fixture.material(gm.owner(), gm.campaign(), null, "ready").getFirst();
        List<UUID> passages = fixture.passages(gm.owner(), version, "P", 450);
        UUID first = Ids.newId();
        UUID second = Ids.newId();
        UUID third = Ids.newId();
        fixture.indexChange(gm.owner(), first, version, 0, 199);
        fixture.indexChange(gm.owner(), second, version, 200, 399);
        fixture.indexChange(gm.owner(), third, version, 400, 449);
        unended.add(second);

        ResolvedScope scope = as(gm.owner(), () -> scopes.resolve(gm.campaign(), ScopeSettings.STARTING));

        assertThat(scope.pending()).hasSize(200).containsExactlyElementsOf(passages.subList(200, 400));
        assertThat(scope.pending(Tier.CAMPAIGN)).isEqualTo(scope.pending());
        assertThat(scope.pending(Tier.SYSTEM)).isEmpty();
        // Once it ended, nothing is pending; while all three run, every passage is.
        unended.remove(second);
        assertThat(as(gm.owner(), () -> scopes.resolve(gm.campaign(), ScopeSettings.STARTING)).pending()).isEmpty();
        unended.addAll(List.of(first, second, third));
        assertThat(as(gm.owner(), () -> scopes.resolve(gm.campaign(), ScopeSettings.STARTING)).pending())
                .containsExactlyElementsOf(passages);
        // A version out of the scope pends nothing in it.
        assertThat(as(gm.owner(), () -> scopes.resolve(gm.campaign(), new ScopeSettings(false, true, false, false)))
                .pending()).isEmpty();
    }

    // --- indexing (the GM's notice)

    /** {@code count} passages of the version, and its index changes as IndexChanges makes them: runs of 200. */
    private List<UUID> indexed(UUID owner, UUID version, String tag, int count) {
        List<UUID> runs = new ArrayList<>();
        fixture.passages(owner, version, tag, count);
        for (int first = 0; first < count; first += 200) {
            UUID job = Ids.newId();
            fixture.indexChange(owner, job, version, first, Math.min(first + 200, count) - 1);
            runs.add(job);
        }
        return runs;
    }

    @Test
    void theIndexingCountsThePassagesStillPendingWithoutReadingOne() {
        ScopeFixture.Starter gm = fixture.starter();
        UUID srd = fixture.material(gm.owner(), null, gm.system(), "ready").getFirst();
        UUID coimbra = fixture.material(gm.owner(), gm.campaign(), null, "ready").getFirst();
        List<UUID> srdRuns = indexed(gm.owner(), srd, "S", 3142);
        indexed(gm.owner(), coimbra, "C", 779);
        // Matrix row "Indexing": the SRD's first ten runs (2,000 passages) have not ended; every run of Coimbra has.
        unended.addAll(srdRuns.subList(0, 10));
        // No passage's text or place opens: the count reads none.
        materials.passages.replaceAll((id, row) -> new PassageRow(row.id(), row.ownerId(), row.versionId(),
                row.ordinal(), row.page(), new byte[] {1, 2, 3}, new byte[] {4, 5, 6}, row.createdAt()));

        Optional<Indexing> indexing = as(gm.owner(), () -> scopes.indexing(gm.campaign(), ScopeSettings.STARTING));

        assertThat(indexing).contains(new Indexing(2000));
        // As runs end the count falls; once none is left, there is no count (matrix row "Nothing pending").
        unended.removeAll(srdRuns.subList(0, 9));
        assertThat(as(gm.owner(), () -> scopes.indexing(gm.campaign(), ScopeSettings.STARTING)))
                .contains(new Indexing(200));
        unended.clear();
        assertThat(as(gm.owner(), () -> scopes.indexing(gm.campaign(), ScopeSettings.STARTING))).isEmpty();
    }

    @Test
    void theStatusSaysWhetherTheScopeHasASourceAndCountsItsPendingPassagesFromOneLookUp() {
        ScopeFixture.Starter gm = fixture.starter();
        // No accepted material: no source, no count.
        assertThat(as(gm.owner(), () -> scopes.status(gm.campaign(), ScopeSettings.STARTING)))
                .isEqualTo(new ScopeStatus(false, null, null));
        UUID a = fixture.material(gm.owner(), gm.campaign(), null, "ready").getFirst();
        List<UUID> runs = indexed(gm.owner(), a, "A", 250);
        // Every passage indexed: a source, no count.
        assertThat(as(gm.owner(), () -> scopes.status(gm.campaign(), ScopeSettings.STARTING)))
                .isEqualTo(new ScopeStatus(true, null, null));
        // Every passage pending: a source (as resolve says, with its pending passages), and the count.
        unended.addAll(runs);
        assertThat(as(gm.owner(), () -> scopes.status(gm.campaign(), ScopeSettings.STARTING)))
                .isEqualTo(new ScopeStatus(true, new Indexing(250), null));
        ResolvedScope scope = as(gm.owner(), () -> scopes.resolve(gm.campaign(), ScopeSettings.STARTING));
        assertThat(!scope.allowedSources().isEmpty() || !scope.pending().isEmpty()).isTrue();
        // A tier switched off: no source.
        assertThat(as(gm.owner(), () -> scopes.status(gm.campaign(), new ScopeSettings(false, false, false, false))))
                .isEqualTo(new ScopeStatus(false, null, null));
    }

    @Test
    void twoPendingVersionsAddUpAndTheCountFallsWhenOneEnds() {
        ScopeFixture.Starter gm = fixture.starter();
        UUID a = fixture.material(gm.owner(), gm.campaign(), null, "ready").getFirst();
        UUID b = fixture.material(gm.owner(), null, gm.system(), "ready").getFirst();
        List<UUID> aRuns = indexed(gm.owner(), a, "A", 300);
        List<UUID> bRuns = indexed(gm.owner(), b, "B", 50);
        // Matrix row "Two versions": A's second run (100 passages) and B's one run (50) pending.
        unended.add(aRuns.get(1));
        unended.addAll(bRuns);

        assertThat(as(gm.owner(), () -> scopes.indexing(gm.campaign(), ScopeSettings.STARTING)))
                .contains(new Indexing(150));
        // A tier switched off is not counted.
        assertThat(as(gm.owner(), () -> scopes.indexing(gm.campaign(), new ScopeSettings(true, false, false, false))))
                .contains(new Indexing(100));
        assertThat(as(gm.owner(), () -> scopes.indexing(gm.campaign(), new ScopeSettings(false, false, false, false))))
                .isEmpty();
        // Matrix row "One finishes": B's run ends; the number falls and never rises.
        unended.removeAll(bRuns);
        assertThat(as(gm.owner(), () -> scopes.indexing(gm.campaign(), ScopeSettings.STARTING)))
                .contains(new Indexing(100));
    }

    @Test
    void aPassageCoveredByTwoUnendedRunsIsCountedOnce() {
        ScopeFixture.Starter gm = fixture.starter();
        UUID a = fixture.material(gm.owner(), gm.campaign(), null, "ready").getFirst();
        fixture.passages(gm.owner(), a, "A", 30);
        UUID first = Ids.newId();
        UUID second = Ids.newId();
        UUID third = Ids.newId();
        fixture.indexChange(gm.owner(), first, a, 0, 14);
        fixture.indexChange(gm.owner(), second, a, 10, 19);
        fixture.indexChange(gm.owner(), third, a, 25, 40);
        unended.addAll(List.of(first, second, third));

        // 0 to 19 (20 passages) and 25 to 29 (5; the run goes past the last passage).
        assertThat(as(gm.owner(), () -> scopes.indexing(gm.campaign(), ScopeSettings.STARTING)))
                .contains(new Indexing(25));
    }

    @Test
    void nothingOutOfScopeAnEndedOrFailedChangeOrAVersionNotReadyIsCounted() {
        Library2 lib = twoTiers();
        UUID owner = lib.gm().owner();
        // Everything indexed: no count.
        assertThat(as(owner, () -> scopes.indexing(lib.gm().campaign(), ScopeSettings.STARTING))).isEmpty();
        // Matrix row "Out of scope": another campaign's version (C), a version under review and another owner's are
        // pending: still no count.
        UUID cJob = Ids.newId();
        fixture.indexChange(owner, cJob, lib.c(), 0, 1);
        UUID reviewing = fixture.material(owner, lib.gm().campaign(), null, "needs_review").getFirst();
        List<UUID> reviewingRuns = indexed(owner, reviewing, "R", 5);
        ScopeFixture.Starter them = fixture.starter();
        UUID theirs = fixture.material(them.owner(), them.campaign(), null, "ready").getFirst();
        List<UUID> theirRuns = indexed(them.owner(), theirs, "T", 5);
        unended.add(cJob);
        unended.addAll(reviewingRuns);
        unended.addAll(theirRuns);
        // Matrix row "Failed change": A's only change ended (failed, cancelled or done: the jobs module answers it as
        // ended).
        UUID aJob = Ids.newId();
        fixture.indexChange(owner, aJob, lib.a(), 0, 2);

        assertThat(as(owner, () -> scopes.indexing(lib.gm().campaign(), ScopeSettings.STARTING))).isEmpty();
        // The other campaign's own scope counts C; the other owner's counts theirs.
        UUID other = materials.materials.get(materials.versions.get(lib.c()).materialId()).campaignId();
        assertThat(as(owner, () -> scopes.indexing(other, ScopeSettings.STARTING))).contains(new Indexing(2));
        assertThat(as(them.owner(), () -> scopes.indexing(them.campaign(), ScopeSettings.STARTING)))
                .contains(new Indexing(5));
        // A run of an unended change with no passage in it counts nothing.
        UUID emptyRun = Ids.newId();
        fixture.indexChange(owner, emptyRun, lib.a(), 50, 60);
        unended.add(emptyRun);
        assertThat(as(owner, () -> scopes.indexing(lib.gm().campaign(), ScopeSettings.STARTING))).isEmpty();
        // An unknown campaign, or another owner's, is not found, as for resolve; and an owner is required.
        for (UUID campaign : java.util.Arrays.asList(them.campaign(), Ids.newId(), null)) {
            assertThatThrownBy(() -> as(owner, () -> scopes.indexing(campaign, ScopeSettings.STARTING)))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.problem().code()).isEqualTo("request.not_found"));
        }
        assertThatThrownBy(() -> scopes.indexing(lib.gm().campaign(), ScopeSettings.STARTING))
                .isInstanceOf(OwnerContext.NoOwnerException.class);
        assertThatThrownBy(() -> new Indexing(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Indexing(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- failed index changes (indexing at worker start)

    @Test
    void aFailedRunIsPendingForTheFallbackButNotIndexingAndIsReportedWithWhetherARetryRemains() {
        ScopeFixture.Starter gm = fixture.starter();
        UUID a = fixture.material(gm.owner(), gm.campaign(), null, "ready").getFirst();
        List<UUID> passages = fixture.passages(gm.owner(), a, "A", 450);
        UUID failedRun = Ids.newId();
        UUID running = Ids.newId();
        UUID done = Ids.newId();
        fixture.indexChange(gm.owner(), failedRun, a, 0, 199, 0);
        fixture.indexChange(gm.owner(), running, a, 200, 399, 0);
        fixture.indexChange(gm.owner(), done, a, 400, 449, 0);
        unended.add(running);
        failed.add(failedRun);
        // No passage's text or place opens: the counts read none.
        Runnable scramble = () -> materials.passages.replaceAll((id, row) -> new PassageRow(row.id(), row.ownerId(),
                row.versionId(), row.ordinal(), row.page(), new byte[] {1, 2, 3}, new byte[] {4, 5, 6},
                row.createdAt()));

        // Matrix row "Run fails": its passages stay in the fallback, after the unended run's (which a cap or the
        // worker's read budget must never leave out for a failed run), each in order.
        ResolvedScope scope = as(gm.owner(), () -> scopes.resolve(gm.campaign(), ScopeSettings.STARTING));
        List<UUID> expected = new ArrayList<>(passages.subList(200, 400));
        expected.addAll(passages.subList(0, 200));
        assertThat(scope.pending()).containsExactlyElementsOf(expected);
        scramble.run();
        assertThat(as(gm.owner(), () -> scopes.status(gm.campaign(), ScopeSettings.STARTING)))
                .isEqualTo(new ScopeStatus(true, new Indexing(200), new IndexingFailure(200, true)));
        // The unended run ends: only the failure is left.
        unended.clear();
        assertThat(as(gm.owner(), () -> scopes.status(gm.campaign(), ScopeSettings.STARTING)))
                .isEqualTo(new ScopeStatus(true, null, new IndexingFailure(200, true)));

        // Matrix row "Retries used": the run failed with retries 3; nothing remains to retry.
        index.changes.remove(failedRun);
        fixture.indexChange(gm.owner(), failedRun, a, 0, 199, 3);
        assertThat(as(gm.owner(), () -> scopes.status(gm.campaign(), ScopeSettings.STARTING)))
                .isEqualTo(new ScopeStatus(true, null, new IndexingFailure(200, false)));
        // A failed run of the system tier with a retry left: the passages add up, and a retry remains.
        UUID b = fixture.material(gm.owner(), null, gm.system(), "ready").getFirst();
        fixture.passages(gm.owner(), b, "B", 30);
        UUID bFailed = Ids.newId();
        fixture.indexChange(gm.owner(), bFailed, b, 0, 29, 2);
        failed.add(bFailed);
        assertThat(as(gm.owner(), () -> scopes.status(gm.campaign(), ScopeSettings.STARTING)))
                .isEqualTo(new ScopeStatus(true, null, new IndexingFailure(230, true)));
        assertThat(as(gm.owner(), () -> scopes.status(gm.campaign(), new ScopeSettings(true, false, false, false))))
                .isEqualTo(new ScopeStatus(true, null, new IndexingFailure(200, false)));

        // The retry succeeds (the sweep moved the row to a new job, which is done): nothing is reported.
        failed.clear();
        assertThat(as(gm.owner(), () -> scopes.status(gm.campaign(), ScopeSettings.STARTING)))
                .isEqualTo(new ScopeStatus(true, null, null));
        assertThatThrownBy(() -> new IndexingFailure(0, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCancelledRunIsNeitherPendingNorFailedAndAFailedRunOutOfScopeOrOfARemovedVersionIsNotCounted() {
        Library2 lib = twoTiers();
        UUID owner = lib.gm().owner();
        // A cancelled run: the jobs module answers it neither unended nor failed.
        fixture.indexChange(owner, Ids.newId(), lib.a(), 0, 2, 0);
        // A failed run of another campaign's version (C), of a discarded version and of another owner's.
        UUID cFailed = Ids.newId();
        fixture.indexChange(owner, cFailed, lib.c(), 0, 1, 0);
        UUID removed = fixture.material(owner, lib.gm().campaign(), null, "ready").getFirst();
        fixture.passages(owner, removed, "R", 5);
        UUID removedFailed = Ids.newId();
        fixture.indexChange(owner, removedFailed, removed, 0, 4, 0);
        fixture.discard(owner, removed);
        ScopeFixture.Starter them = fixture.starter();
        UUID theirs = fixture.material(them.owner(), them.campaign(), null, "ready").getFirst();
        fixture.passages(them.owner(), theirs, "T", 5);
        UUID theirFailed = Ids.newId();
        fixture.indexChange(them.owner(), theirFailed, theirs, 0, 4, 0);
        failed.addAll(List.of(cFailed, removedFailed, theirFailed));

        // Matrix row "Removed version": not counted.
        assertThat(as(owner, () -> scopes.status(lib.gm().campaign(), ScopeSettings.STARTING)))
                .isEqualTo(new ScopeStatus(true, null, null));
        assertThat(as(owner, () -> scopes.resolve(lib.gm().campaign(), ScopeSettings.STARTING)).pending()).isEmpty();
        UUID other = materials.materials.get(materials.versions.get(lib.c()).materialId()).campaignId();
        assertThat(as(owner, () -> scopes.status(other, ScopeSettings.STARTING)).failed())
                .isEqualTo(new IndexingFailure(2, true));
        assertThat(as(them.owner(), () -> scopes.status(them.campaign(), ScopeSettings.STARTING)).failed())
                .isEqualTo(new IndexingFailure(5, true));
    }

    // --- passages by id

    @Test
    void passagesOfBothTiersAreReadLabelledInTheOrderAskedEachOnce() {
        Library2 lib = twoTiers();
        ResolvedScope scope = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), ScopeSettings.STARTING));
        UUID b1 = lib.bPassages().get(1);
        UUID a0 = lib.aPassages().getFirst();
        UUID a2 = lib.aPassages().get(2);

        List<ScopedPassage> read = as(lib.gm().owner(), () -> scopes.passages(scope, List.of(b1, a0, b1, a2)));

        assertThat(read).extracting(ScopedPassage::id, ScopedPassage::tier, ScopedPassage::text,
                        ScopedPassage::sectionPath, ScopedPassage::page)
                .containsExactly(tuple(b1, Tier.SYSTEM, "B 1", List.of("Capítulo", "Seção 1"), 1),
                        tuple(a0, Tier.CAMPAIGN, "A 0", List.of("Capítulo", "Seção 0"), 1),
                        tuple(a2, Tier.CAMPAIGN, "A 2", List.of("Capítulo", "Seção 2"), 1));
        assertThat(read.getFirst().toString()).doesNotContain("B 1").doesNotContain("Seção");
        assertThat(Tier.CAMPAIGN.word()).isEqualTo("campaign");
        assertThat(Tier.SYSTEM.word()).isEqualTo("system");
    }

    @Test
    void aScopeResolvedForItsSourcesHasTheSameSourcesAndTiersButReadsNoPendingPassage() {
        Library2 lib = twoTiers();
        UUID run = Ids.newId();
        fixture.indexChange(lib.gm().owner(), run, lib.a(), 0, 2, 0);
        unended.add(run);
        ResolvedScope full = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), ScopeSettings.STARTING));
        assertThat(full.pending()).containsExactlyElementsOf(lib.aPassages());
        jobsAsked.set(0);

        ResolvedScope sources = as(lib.gm().owner(),
                () -> scopes.resolveSources(lib.gm().campaign(), ScopeSettings.STARTING));

        assertThat(jobsAsked).hasValue(0);
        assertThat(sources.pending()).isEmpty();
        for (Tier tier : Tier.values()) {
            assertThat(sources.sources(tier)).isEqualTo(full.sources(tier));
        }
        assertThat(sources.allowedSources()).isEqualTo(full.allowedSources());
        // The passages and references reads answer as from the full scope, still asking no job.
        UUID a0 = lib.aPassages().getFirst();
        UUID b0 = lib.bPassages().getFirst();
        assertThat(as(lib.gm().owner(), () -> scopes.passages(sources, List.of(a0, b0))))
                .extracting(ScopedPassage::id, ScopedPassage::tier)
                .containsExactly(tuple(a0, Tier.CAMPAIGN), tuple(b0, Tier.SYSTEM));
        assertThat(as(lib.gm().owner(), () -> scopes.references(sources, List.of(a0, b0))))
                .extracting(CitedPlace::tier).containsExactly(Tier.CAMPAIGN, Tier.SYSTEM);
        assertThatThrownBy(() -> as(lib.gm().owner(), () -> scopes.passages(sources, List.of(lib.cPassages()
                .getFirst())))).isInstanceOf(ApiException.class);
        assertThat(jobsAsked).hasValue(0);
        // The same refusals: another owner's campaign is not found.
        ScopeFixture.Starter them = fixture.starter();
        assertThatThrownBy(() -> as(them.owner(), () -> scopes.resolveSources(lib.gm().campaign(),
                ScopeSettings.STARTING))).isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.problem().code()).isEqualTo("request.not_found"));
    }

    @Test
    void anyIdOutsideTheScopeRefusesTheWholeRequestBeforeAnythingIsDecrypted() {
        Library2 lib = twoTiers();
        ScopeFixture.Starter them = fixture.starter();
        UUID theirVersion = fixture.material(them.owner(), them.campaign(), null, "ready").getFirst();
        UUID theirs = fixture.passages(them.owner(), theirVersion, "T", 1).getFirst();
        UUID gone = fixture.material(lib.gm().owner(), lib.gm().campaign(), null, "ready").getFirst();
        UUID goneP = fixture.passages(lib.gm().owner(), gone, "G", 1).getFirst();
        UUID reviewing = fixture.material(lib.gm().owner(), lib.gm().campaign(), null, "needs_review").getFirst();
        UUID reviewingP = fixture.passages(lib.gm().owner(), reviewing, "R", 1).getFirst();
        ResolvedScope scope = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), ScopeSettings.STARTING));
        fixture.state(lib.gm().owner(), gone, "discarded");
        // A good passage whose ciphertext no longer opens: reading it would fail, so a refusal proves nothing was read.
        UUID broken = lib.aPassages().getFirst();
        PassageRow row = materials.passages.get(broken);
        materials.passages.put(broken, new PassageRow(row.id(), row.ownerId(), row.versionId(), row.ordinal(),
                row.page(), new byte[] {1, 2, 3}, row.place(), row.createdAt()));
        UUID good = lib.bPassages().getFirst();

        for (UUID outside : List.of(lib.cPassages().getFirst(), goneP, reviewingP, theirs, Ids.newId())) {
            assertRefused(lib.gm().owner(), scope, List.of(good, broken, outside));
            assertRefused(lib.gm().owner(), scope, List.of(outside));
        }
        assertThatThrownBy(() -> as(lib.gm().owner(), () -> scopes.passages(scope, List.of(good, broken))))
                .isInstanceOf(IntegrityException.class);
        assertThat(as(lib.gm().owner(), () -> scopes.passages(scope, List.of(good)))).hasSize(1);
    }

    @Test
    void aVersionNoLongerReadyAfterTheResolveIsRefused() {
        Library2 lib = twoTiers();
        ResolvedScope scope = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), ScopeSettings.STARTING));
        fixture.state(lib.gm().owner(), lib.a(), "superseded");

        assertRefused(lib.gm().owner(), scope, List.of(lib.aPassages().getFirst()));
        assertThat(as(lib.gm().owner(), () -> scopes.passages(scope, List.of(lib.bPassages().getFirst())))).hasSize(1);
    }

    // --- Story 3.4: a material replaced

    @Test
    void theOldVersionServesWhileItsReplacementIsProcessedAndOnlyTheNewOneAfterItIsAccepted() {
        Library2 lib = twoTiers();
        UUID materialId = materials.versions.get(lib.a()).materialId();
        UUID replacement = fixture.version(lib.gm().owner(), materialId, "processing");
        List<UUID> newPassages = fixture.passages(lib.gm().owner(), replacement, "N", 2);

        for (String state : List.of("processing", "needs_review")) {
            fixture.state(lib.gm().owner(), replacement, state);
            ResolvedScope during = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(),
                    ScopeSettings.STARTING));
            assertThat(during.sources(Tier.CAMPAIGN)).as(state).containsExactly(lib.a());
            assertThat(as(lib.gm().owner(), () -> scopes.passages(during, List.of(lib.aPassages().getFirst()))))
                    .as(state).extracting(ScopedPassage::id).containsExactly(lib.aPassages().getFirst());
            assertRefused(lib.gm().owner(), during, List.of(newPassages.getFirst()));
        }

        // The accept: the new version ready, the old one superseded.
        fixture.state(lib.gm().owner(), replacement, "ready");
        fixture.state(lib.gm().owner(), lib.a(), "superseded");
        ResolvedScope after = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), ScopeSettings.STARTING));

        assertThat(after.sources(Tier.CAMPAIGN)).containsExactly(replacement);
        assertThat(after.allowedSources()).doesNotContain(lib.a());
        assertThat(as(lib.gm().owner(), () -> scopes.passages(after, List.of(newPassages.getFirst()))))
                .extracting(ScopedPassage::id).containsExactly(newPassages.getFirst());
        assertRefused(lib.gm().owner(), after, List.of(lib.aPassages().getFirst()));
    }

    @Test
    void aDiscardedReplacementLeavesTheOldVersionServing() {
        Library2 lib = twoTiers();
        UUID materialId = materials.versions.get(lib.a()).materialId();
        UUID replacement = fixture.version(lib.gm().owner(), materialId, "needs_review");
        fixture.passages(lib.gm().owner(), replacement, "N", 2);

        fixture.discard(lib.gm().owner(), replacement);

        ResolvedScope scope = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), ScopeSettings.STARTING));
        assertThat(scope.sources(Tier.CAMPAIGN)).containsExactly(lib.a());
        assertThat(as(lib.gm().owner(), () -> scopes.passages(scope, List.of(lib.aPassages().getFirst()))))
                .hasSize(1);
    }

    // --- Story 3.3: where a material belongs changes

    @Test
    void aCampaignsMaterialIsAbsentFromASiblingCampaignOfTheSameSystem() {
        Library2 lib = twoTiers();
        UUID sibling = materials.materials.get(materials.versions.get(lib.c()).materialId()).campaignId();

        ResolvedScope theirs = as(lib.gm().owner(), () -> scopes.resolve(sibling, ScopeSettings.STARTING));

        assertThat(theirs.allowedSources()).doesNotContain(lib.a());
        assertRefused(lib.gm().owner(), theirs, List.of(lib.aPassages().getFirst()));
    }

    @Test
    void aMaterialMovedFromItsCampaignToTheSystemLeavesTheCampaignTierAndJoinsTheSystemTierOfEveryCampaignOfIt() {
        Library2 lib = twoTiers();
        UUID sibling = materials.materials.get(materials.versions.get(lib.c()).materialId()).campaignId();

        fixture.move(lib.gm().owner(), lib.a(), null, lib.gm().system());

        ResolvedScope mine = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), ScopeSettings.STARTING));
        ResolvedScope theirs = as(lib.gm().owner(), () -> scopes.resolve(sibling, ScopeSettings.STARTING));
        assertThat(mine.sources(Tier.CAMPAIGN)).isEmpty();
        assertThat(mine.sources(Tier.SYSTEM)).containsExactlyInAnyOrder(lib.a(), lib.b());
        assertThat(theirs.sources(Tier.SYSTEM)).containsExactlyInAnyOrder(lib.a(), lib.b());
        assertThat(as(lib.gm().owner(), () -> scopes.passages(theirs, List.of(lib.aPassages().getFirst()))))
                .singleElement().satisfies(passage -> assertThat(passage.tier()).isEqualTo(Tier.SYSTEM));
    }

    @Test
    void aMaterialMovedToAnotherCampaignIsInThatCampaignsTierOnly() {
        Library2 lib = twoTiers();
        UUID sibling = materials.materials.get(materials.versions.get(lib.c()).materialId()).campaignId();

        fixture.move(lib.gm().owner(), lib.a(), sibling);

        ResolvedScope mine = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), ScopeSettings.STARTING));
        ResolvedScope theirs = as(lib.gm().owner(), () -> scopes.resolve(sibling, ScopeSettings.STARTING));
        assertThat(mine.allowedSources()).containsExactly(lib.b());
        assertRefused(lib.gm().owner(), mine, List.of(lib.aPassages().getFirst()));
        assertThat(theirs.sources(Tier.CAMPAIGN)).containsExactlyInAnyOrder(lib.a(), lib.c());
    }

    @Test
    void aMaterialOfSeveralSystemsIsInTheSystemTierOfEachOnesCampaignsAndOfNoOtherSystem() {
        Library2 lib = twoTiers();
        UUID second = fixture.system(lib.gm().owner(), "Pathfinder");
        UUID secondCampaign = fixture.campaignOf(lib.gm().owner(), second, "Mesa de sábado");
        UUID third = fixture.system(lib.gm().owner(), "Tormenta");
        UUID thirdCampaign = fixture.campaignOf(lib.gm().owner(), third, "Mesa de domingo");

        fixture.move(lib.gm().owner(), lib.a(), null, lib.gm().system(), second);

        assertThat(as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), ScopeSettings.STARTING))
                .sources(Tier.SYSTEM)).contains(lib.a());
        assertThat(as(lib.gm().owner(), () -> scopes.resolve(secondCampaign, ScopeSettings.STARTING))
                .sources(Tier.SYSTEM)).containsExactly(lib.a());
        assertThat(as(lib.gm().owner(), () -> scopes.resolve(thirdCampaign, ScopeSettings.STARTING))
                .allowedSources()).isEmpty();
    }

    @Test
    void aMaterialMovedOutAfterAQuestionResolvedIsRefusedAtTheNextFetchWhichResolvesAgain() {
        Library2 lib = twoTiers();
        UUID sibling = materials.materials.get(materials.versions.get(lib.c()).materialId()).campaignId();
        // The question's first fetch, as AnswerJobs makes it: the scope resolved for that fetch.
        ResolvedScope first = as(lib.gm().owner(), () -> scopes.resolveSources(lib.gm().campaign(),
                ScopeSettings.STARTING));
        assertThat(as(lib.gm().owner(), () -> scopes.passages(first, List.of(lib.aPassages().getFirst()))))
                .hasSize(1);

        fixture.move(lib.gm().owner(), lib.a(), sibling);

        // The next fetch of the same question resolves again and no longer has A.
        ResolvedScope next = as(lib.gm().owner(), () -> scopes.resolveSources(lib.gm().campaign(),
                ScopeSettings.STARTING));
        assertRefused(lib.gm().owner(), next, List.of(lib.bPassages().getFirst(), lib.aPassages().getFirst()));
        assertThat(as(lib.gm().owner(), () -> scopes.passages(next, List.of(lib.bPassages().getFirst())))).hasSize(1);
    }

    @Test
    void anotherOwnersScopeANullIdOrNoOwnerIsRefused() {
        Library2 lib = twoTiers();
        ScopeFixture.Starter them = fixture.starter();
        ResolvedScope scope = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), ScopeSettings.STARTING));
        List<UUID> withNull = new ArrayList<>();
        withNull.add(lib.aPassages().getFirst());
        withNull.add(null);

        assertRefused(them.owner(), scope, List.of(lib.aPassages().getFirst()));
        assertRefused(lib.gm().owner(), scope, withNull);
        assertThatThrownBy(() -> scopes.passages(scope, List.of(lib.aPassages().getFirst())))
                .isInstanceOf(OwnerContext.NoOwnerException.class);
        assertThatThrownBy(() -> scopes.resolve(lib.gm().campaign(), ScopeSettings.STARTING))
                .isInstanceOf(OwnerContext.NoOwnerException.class);
    }

    // --- cited places (Story 2.5)

    @Test
    void citedPlacesOfTheScopeAreReadInTheOrderCitedEachOnceAndEveryOtherIdIsDroppedWithoutDecryptingText() {
        Library2 lib = twoTiers();
        ScopeFixture.Starter them = fixture.starter();
        UUID theirVersion = fixture.material(them.owner(), them.campaign(), null, "ready").getFirst();
        UUID theirs = fixture.passages(them.owner(), theirVersion, "T", 1).getFirst();
        UUID reviewing = fixture.material(lib.gm().owner(), lib.gm().campaign(), null, "needs_review").getFirst();
        UUID reviewingP = fixture.passages(lib.gm().owner(), reviewing, "R", 1).getFirst();
        ResolvedScope scope = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), ScopeSettings.STARTING));
        UUID a1 = lib.aPassages().get(1);
        UUID a0 = lib.aPassages().getFirst();
        UUID b0 = lib.bPassages().getFirst();
        // A passage whose text no longer opens: a place is read without its text.
        PassageRow row = materials.passages.get(a1);
        materials.passages.put(a1, new PassageRow(row.id(), row.ownerId(), row.versionId(), row.ordinal(),
                row.page(), new byte[] {1, 2, 3}, row.place(), row.createdAt()));
        List<UUID> cited = new ArrayList<>(List.of(a1, theirs, lib.cPassages().getFirst(), a0, reviewingP, a1));
        cited.add(null);
        cited.addAll(List.of(Ids.newId(), b0));

        List<CitedPlace> places = as(lib.gm().owner(), () -> scopes.references(scope, cited));

        assertThat(places).extracting(CitedPlace::passageId, CitedPlace::versionId, CitedPlace::materialName,
                        CitedPlace::sectionPath, CitedPlace::page)
                .containsExactly(tuple(a1, lib.a(), ScopeFixture.MATERIAL_NAME, List.of("Capítulo", "Seção 1"), 1),
                        tuple(a0, lib.a(), ScopeFixture.MATERIAL_NAME, List.of("Capítulo", "Seção 0"), 1),
                        tuple(b0, lib.b(), ScopeFixture.MATERIAL_NAME, List.of("Capítulo", "Seção 0"), 1));
        // Story 3.7: each place carries its source's tier in this scope, the scope's own label.
        assertThat(places).extracting(CitedPlace::tier).containsExactly(Tier.CAMPAIGN, Tier.CAMPAIGN, Tier.SYSTEM);
        assertThat(places.getFirst().materialId()).isEqualTo(materials.versions.get(lib.a()).materialId());
        assertThat(places.getFirst().toString()).doesNotContain(ScopeFixture.MATERIAL_NAME).doesNotContain("Seção");
        assertThat(as(lib.gm().owner(), () -> scopes.references(scope, List.of(theirs, Ids.newId())))).isEmpty();
        assertThat(as(lib.gm().owner(), () -> scopes.references(scope, List.of()))).isEmpty();
        // A version no longer ready after the resolve is dropped too.
        fixture.state(lib.gm().owner(), lib.b(), "superseded");
        assertThat(as(lib.gm().owner(), () -> scopes.references(scope, List.of(b0, a0))))
                .extracting(CitedPlace::passageId).containsExactly(a0);
        // Only the scope's owner reads within it.
        assertThatThrownBy(() -> as(them.owner(), () -> scopes.references(scope, List.of(a0))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aVersionGoneDiscardedWithItsRemovalPendingOrFinishedPendingRemovalOrOfAGoneMaterialIsRemoved() {
        ScopeFixture.Starter gm = fixture.starter();
        UUID kept = fixture.material(gm.owner(), gm.campaign(), null, "ready").getFirst();
        UUID superseded = fixture.material(gm.owner(), gm.campaign(), null, "superseded").getFirst();
        UUID discarded = fixture.material(gm.owner(), gm.campaign(), null, "ready").getFirst();
        fixture.discard(gm.owner(), discarded);
        UUID gone = fixture.material(gm.owner(), gm.campaign(), null, "ready").getFirst();
        fixture.delete(gm.owner(), gone, false);
        UUID materialGone = fixture.material(gm.owner(), gm.campaign(), null, "ready").getFirst();
        fixture.delete(gm.owner(), materialGone, true);
        // Discarded with its removal finished: the permanent state after VersionRemoval.
        UUID removalDone = fixture.material(gm.owner(), gm.campaign(), null, "ready").getFirst();
        fixture.discard(gm.owner(), removalDone);
        fixture.finishRemoval(gm.owner(), removalDone);
        assertThat(materials.versions.get(removalDone).removalPending()).isFalse();
        // Still ready, but with its removal pending.
        UUID readyPending = fixture.material(gm.owner(), gm.campaign(), null, "ready").getFirst();
        fixture.pendRemoval(gm.owner(), readyPending);
        ScopeFixture.Starter them = fixture.starter();
        UUID theirs = fixture.material(them.owner(), them.campaign(), null, "ready").getFirst();

        Set<UUID> removed = as(gm.owner(), () -> scopes.removedVersions(Set.of(kept, superseded, discarded, gone,
                materialGone, removalDone, readyPending, theirs)));

        assertThat(removed).containsExactlyInAnyOrder(discarded, gone, materialGone, removalDone, readyPending, theirs);
        assertThat(as(gm.owner(), () -> scopes.removedVersions(Set.of()))).isEmpty();
        assertThatThrownBy(() -> scopes.removedVersions(Set.of(kept))).isInstanceOf(OwnerContext.NoOwnerException.class);
    }

    // --- the campaign

    @Test
    void anotherOwnersCampaignOrAnUnknownOneIsNotFound() {
        Library2 lib = twoTiers();
        ScopeFixture.Starter them = fixture.starter();

        for (UUID campaign : java.util.Arrays.asList(lib.gm().campaign(), Ids.newId(), null)) {
            assertThatThrownBy(() -> as(them.owner(), () -> scopes.resolve(campaign, ScopeSettings.STARTING)))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.problem().status()).isEqualTo(404);
                        assertThat(e.problem().code()).isEqualTo("request.not_found");
                    });
        }
    }

    // --- the question input (Story 2.3)

    @Test
    void theQuestionInputCarriesTheQuestionTheOwnerAndEachTiersSourcesAndPendingInTierOrder() {
        Library2 lib = twoTiers();
        UUID second = fixture.material(lib.gm().owner(), lib.gm().campaign(), null, "ready").getFirst();
        List<UUID> secondPassages = fixture.passages(lib.gm().owner(), second, "O", 4);
        UUID campaignJob = Ids.newId();
        UUID systemJob = Ids.newId();
        fixture.indexChange(lib.gm().owner(), campaignJob, second, 1, 2);
        fixture.indexChange(lib.gm().owner(), systemJob, lib.b(), 0, 1);
        unended.addAll(List.of(campaignJob, systemJob));
        ResolvedScope scope = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), ScopeSettings.STARTING));

        JsonNode input = as(lib.gm().owner(), () -> scopes.questionInput(scope, "Qual é o alcance da Espada Vorpal?"));

        assertThat(fieldNames(input)).containsExactly("question", "owner", "modelKnowledge", "tiers", "tokenKey",
                "recordTypes");
        // Story 4.4 part B: the keys of the campaign's system, sorted.
        assertThat(input.path("recordTypes").toString()).isEqualTo("[\"character_sheet\",\"creature\",\"dungeon\","
                + "\"encounter\",\"item\",\"note\",\"rule\",\"spell\"]");
        assertThat(input.path("question").asString()).isEqualTo("Qual é o alcance da Espada Vorpal?");
        assertThat(input.path("modelKnowledge").isBoolean()).isTrue();
        assertThat(input.path("modelKnowledge").asBoolean()).isFalse();
        assertThat(input.path("owner").asString()).isEqualTo(lib.gm().owner().toString());
        JsonNode tiers = input.path("tiers");
        assertThat(tiers.size()).isEqualTo(3);
        assertThat(tiers.get(2).path("tier").asString()).isEqualTo("other");
        assertThat(tiers.get(2).path("sources").isEmpty()).isTrue();
        assertThat(tiers.get(0).path("tier").asString()).isEqualTo("campaign");
        assertThat(strings(tiers.get(0).path("sources"))).containsExactly(lib.a().toString(), second.toString());
        assertThat(strings(tiers.get(0).path("pending"))).containsExactly(secondPassages.get(1).toString(),
                secondPassages.get(2).toString());
        assertThat(tiers.get(1).path("tier").asString()).isEqualTo("system");
        assertThat(strings(tiers.get(1).path("sources"))).containsExactly(lib.b().toString());
        assertThat(strings(tiers.get(1).path("pending"))).containsExactly(lib.bPassages().get(0).toString(),
                lib.bPassages().get(1).toString());
        // Nothing of another campaign, and the owner's token key in Base64.
        assertThat(input.toString()).doesNotContain(lib.c().toString());
        assertThat(java.util.Base64.getDecoder().decode(input.path("tokenKey").asString()))
                .isEqualTo(vault.ownerTokenKey(lib.gm().owner()));
    }

    @Test
    void theQuestionInputCarriesTheScopesModelKnowledgeSwitchAndNothingElseChanges() {
        Library2 lib = twoTiers();
        ResolvedScope off = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), ScopeSettings.STARTING));
        ResolvedScope on = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(),
                new ScopeSettings(true, true, false, true)));

        JsonNode withoutIt = as(lib.gm().owner(), () -> scopes.questionInput(off, "Vorpal?"));
        JsonNode withIt = as(lib.gm().owner(), () -> scopes.questionInput(on, "Vorpal?"));

        assertThat(withoutIt.path("modelKnowledge").asBoolean()).isFalse();
        assertThat(withIt.path("modelKnowledge").isBoolean()).isTrue();
        assertThat(withIt.path("modelKnowledge").asBoolean()).isTrue();
        // The switch changes no source and no passage handed over.
        assertThat(withIt.path("tiers")).isEqualTo(withoutIt.path("tiers"));
    }

    @Test
    void aTierWithNoSourceIsStillListedWithEmptyListsAndAnEmptyScopeHasThreeEmptyTiers() {
        Library2 lib = twoTiers();
        ResolvedScope systemOnly = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(),
                new ScopeSettings(false, true, false, false)));
        ScopeFixture.Starter empty = fixture.starter();
        ResolvedScope nothing = as(empty.owner(), () -> scopes.resolve(empty.campaign(), ScopeSettings.STARTING));

        JsonNode input = as(lib.gm().owner(), () -> scopes.questionInput(systemOnly, "Vorpal?"));
        JsonNode none = as(empty.owner(), () -> scopes.questionInput(nothing, "Vorpal?"));

        assertThat(input.path("tiers").get(0).path("tier").asString()).isEqualTo("campaign");
        assertThat(input.path("tiers").get(0).path("sources").isEmpty()).isTrue();
        assertThat(input.path("tiers").get(0).path("pending").isEmpty()).isTrue();
        assertThat(strings(input.path("tiers").get(1).path("sources"))).containsExactly(lib.b().toString());
        assertThat(none.path("tiers").size()).isEqualTo(3);
        assertThat(none.path("tiers")).extracting(tier -> tier.path("tier").asString())
                .containsExactly("campaign", "system", "other");
        for (JsonNode tier : none.path("tiers")) {
            assertThat(tier.path("sources").isArray()).isTrue();
            assertThat(tier.path("sources").isEmpty()).isTrue();
            assertThat(tier.path("pending").isEmpty()).isTrue();
        }
        assertThat(none.path("owner").asString()).isEqualTo(empty.owner().toString());
    }

    @Test
    void aTiersPendingIsListedUpToItsFirst5000InOrderAndHowManyWereLeftOutIsLogged() {
        ScopeFixture.Starter gm = fixture.starter();
        UUID version = fixture.material(gm.owner(), gm.campaign(), null, "ready").getFirst();
        List<UUID> passages = fixture.passages(gm.owner(), version, "P", 5_100);
        UUID job = Ids.newId();
        fixture.indexChange(gm.owner(), job, version, 0, 5_099);
        unended.add(job);
        ResolvedScope scope = as(gm.owner(), () -> scopes.resolve(gm.campaign(), ScopeSettings.STARTING));
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(Scopes.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logged =
                new ch.qos.logback.core.read.ListAppender<>();
        logged.start();
        logger.addAppender(logged);

        JsonNode input;
        try {
            input = as(gm.owner(), () -> scopes.questionInput(scope, "Vorpal?"));
        } finally {
            logger.detachAppender(logged);
        }

        assertThat(scope.pending(Tier.CAMPAIGN)).hasSize(5_100);
        assertThat(Scopes.MAX_PENDING).isEqualTo(5_000);
        assertThat(strings(input.path("tiers").get(0).path("pending")))
                .containsExactlyElementsOf(passages.subList(0, 5_000).stream().map(UUID::toString).toList());
        assertThat(logged.list).filteredOn(event -> event.getMessage().equals("scope.question_input"))
                .singleElement().satisfies(event -> assertThat(event.getKeyValuePairs())
                        .extracting(pair -> pair.key + "=" + pair.value)
                        .contains("pending=5100", "pendingListed=5000", "pendingLeftOut=100"));
    }

    @Test
    void theTokenKeyBufferIsZeroedOnceEncoded() {
        Library2 lib = twoTiers();
        ResolvedScope scope = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), ScopeSettings.STARTING));
        handedKeys.clear();

        JsonNode input = as(lib.gm().owner(), () -> scopes.questionInput(scope, "Vorpal?"));

        assertThat(handedKeys).hasSize(1);
        assertThat(handedKeys.getFirst()).hasSize(32).containsOnly(0);
        assertThat(java.util.Base64.getDecoder().decode(input.path("tokenKey").asString())).hasSize(32)
                .isNotEqualTo(new byte[32]);
    }

    @Test
    void aQuestionIsOneTo4000CharactersAndTheInputIsBuiltOnlyForTheScopesOwner() {
        Library2 lib = twoTiers();
        ScopeFixture.Starter them = fixture.starter();
        ResolvedScope scope = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), ScopeSettings.STARTING));
        // 4000 code points, each a surrogate pair: 8000 UTF-16 units, still within the bound.
        String longest = "\uD83D\uDC09".repeat(4000);

        assertThat(as(lib.gm().owner(), () -> scopes.questionInput(scope, "?")).path("question").asString())
                .isEqualTo("?");
        assertThat(as(lib.gm().owner(), () -> scopes.questionInput(scope, longest)).path("question").asString())
                .isEqualTo(longest);
        assertThat(as(lib.gm().owner(), () -> scopes.questionInput(scope, "a".repeat(4000))).path("question")
                .asString()).hasSize(4000);
        for (String question : java.util.Arrays.asList(null, "", "a".repeat(4001), longest + "a")) {
            assertThatThrownBy(() -> as(lib.gm().owner(), () -> scopes.questionInput(scope, question)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("scope.question_invalid");
        }
        assertThat(Scopes.MAX_QUESTION).isEqualTo(4000);
        assertThatThrownBy(() -> as(them.owner(), () -> scopes.questionInput(scope, "Vorpal?")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> scopes.questionInput(scope, "Vorpal?"))
                .isInstanceOf(OwnerContext.NoOwnerException.class);
    }

    // --- search (Story 3.9)

    @Test
    void aFilterOnlyNarrowsTheScopeAndItsPendingPassagesAreThoseOfItsOwnSources() {
        Library2 lib = twoTiers();
        UUID second = fixture.material(lib.gm().owner(), lib.gm().campaign(), null, "ready").getFirst();
        List<UUID> secondPassages = fixture.passages(lib.gm().owner(), second, "S", 2);
        UUID job = Ids.newId();
        UUID systemJob = Ids.newId();
        fixture.indexChange(lib.gm().owner(), job, second, 0, 1);
        fixture.indexChange(lib.gm().owner(), systemJob, lib.b(), 0, 1);
        unended.addAll(List.of(job, systemJob));
        UUID aMaterial = materials.versions.get(lib.a()).materialId();
        UUID secondMaterial = materials.versions.get(second).materialId();
        UUID bMaterial = materials.versions.get(lib.b()).materialId();
        ScopeSettings all = new ScopeSettings(true, true, true, false);

        ResolvedScope campaignOnly = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), all,
                new ScopeFilter(Tier.CAMPAIGN, null)));
        ResolvedScope oneMaterial = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), all,
                new ScopeFilter(null, secondMaterial)));
        ResolvedScope both = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), all,
                new ScopeFilter(Tier.CAMPAIGN, aMaterial)));

        assertThat(campaignOnly.sources(Tier.CAMPAIGN)).containsExactly(lib.a(), second);
        assertThat(campaignOnly.sources(Tier.SYSTEM)).isEmpty();
        assertThat(campaignOnly.sources(Tier.OTHER)).isEmpty();
        assertThat(campaignOnly.pending(Tier.CAMPAIGN)).containsExactlyElementsOf(secondPassages);
        assertThat(campaignOnly.pending(Tier.SYSTEM)).isEmpty();
        assertThat(oneMaterial.allowedSources()).containsExactly(second);
        assertThat(oneMaterial.pending()).containsExactlyElementsOf(secondPassages);
        assertThat(both.allowedSources()).containsExactly(lib.a());
        assertThat(both.pending()).isEmpty();
        // A filter never widens: a tier switched off, a material switched off or in another tier leaves nothing.
        assertThat(as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), ScopeSettings.STARTING,
                new ScopeFilter(Tier.OTHER, null))).allowedSources()).isEmpty();
        assertThat(as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(),
                new ScopeSettings(true, true, true, false, Set.of(secondMaterial)),
                new ScopeFilter(null, secondMaterial))).allowedSources()).isEmpty();
        assertThat(as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), all,
                new ScopeFilter(Tier.CAMPAIGN, bMaterial))).allowedSources()).isEmpty();
        // No filter is the scope itself.
        assertThat(as(lib.gm().owner(), () -> scopes.resolveSources(lib.gm().campaign(), all, ScopeFilter.NONE))
                .allowedSources()).isEqualTo(as(lib.gm().owner(), () -> scopes.resolveSources(lib.gm().campaign(),
                all)).allowedSources());
        assertThat(new ScopeFilter(Tier.CAMPAIGN, aMaterial).toString()).doesNotContain(aMaterial.toString());
    }

    @Test
    void theSearchInputIsTheQuestionInputsShapeWithQueryAndAQueryIsOneTo500Characters() {
        Library2 lib = twoTiers();
        ResolvedScope scope = as(lib.gm().owner(), () -> scopes.resolve(lib.gm().campaign(), ScopeSettings.STARTING));
        String longest = "🐉".repeat(500);

        JsonNode input = as(lib.gm().owner(), () -> scopes.searchInput(scope, "Espada Vorpal"));

        assertThat(fieldNames(input)).containsExactly("query", "owner", "modelKnowledge", "tiers", "tokenKey",
                "recordTypes");
        assertThat(input.path("query").asString()).isEqualTo("Espada Vorpal");
        JsonNode asQuestion = as(lib.gm().owner(), () -> scopes.questionInput(scope, "Espada Vorpal"));
        assertThat(input.path("tiers")).isEqualTo(asQuestion.path("tiers"));
        assertThat(input.path("owner")).isEqualTo(asQuestion.path("owner"));
        assertThat(as(lib.gm().owner(), () -> scopes.searchInput(scope, longest)).path("query").asString())
                .isEqualTo(longest);
        for (String query : java.util.Arrays.asList(null, "", "a".repeat(501), longest + "a")) {
            assertThatThrownBy(() -> as(lib.gm().owner(), () -> scopes.searchInput(scope, query)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("scope.query_invalid");
        }
        assertThat(Scopes.MAX_QUERY).isEqualTo(500);
        ScopeFixture.Starter them = fixture.starter();
        assertThatThrownBy(() -> as(them.owner(), () -> scopes.searchInput(scope, "Vorpal")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resultsOfTheScopeAreReadWithTheirPlaceTextAndTierAndEveryOtherIdIsDroppedNeverRefused() {
        Library2 lib = twoTiers();
        ScopeFixture.Starter them = fixture.starter();
        UUID theirVersion = fixture.material(them.owner(), them.campaign(), null, "ready").getFirst();
        UUID theirs = fixture.passages(them.owner(), theirVersion, "T", 1).getFirst();
        ResolvedScope scope = as(lib.gm().owner(), () -> scopes.resolveSources(lib.gm().campaign(),
                ScopeSettings.STARTING));
        UUID a0 = lib.aPassages().getFirst();
        UUID b1 = lib.bPassages().get(1);
        List<UUID> asked = new ArrayList<>(List.of(b1, theirs, lib.cPassages().getFirst(), a0, b1));
        asked.add(null);
        asked.add(Ids.newId());

        List<ScopedResult> results = as(lib.gm().owner(), () -> scopes.results(scope, asked));

        assertThat(results).extracting(ScopedResult::passageId, ScopedResult::versionId, ScopedResult::tier,
                        ScopedResult::text, ScopedResult::materialName, ScopedResult::page)
                .containsExactly(tuple(b1, lib.b(), Tier.SYSTEM, "B 1", ScopeFixture.MATERIAL_NAME, 1),
                        tuple(a0, lib.a(), Tier.CAMPAIGN, "A 0", ScopeFixture.MATERIAL_NAME, 1));
        assertThat(results.getFirst().sectionPath()).containsExactly("Capítulo", "Seção 1");
        assertThat(results.getFirst().materialId()).isEqualTo(materials.versions.get(lib.b()).materialId());
        assertThat(results.getFirst().toString()).doesNotContain("B 1").doesNotContain(ScopeFixture.MATERIAL_NAME);
        assertThat(as(lib.gm().owner(), () -> scopes.results(scope, List.of()))).isEmpty();
        // A version no longer ready is dropped; a narrowed scope drops the rest.
        fixture.state(lib.gm().owner(), lib.b(), "superseded");
        assertThat(as(lib.gm().owner(), () -> scopes.results(scope, List.of(b1, a0))))
                .extracting(ScopedResult::passageId).containsExactly(a0);
        ResolvedScope systemOnly = as(lib.gm().owner(), () -> scopes.resolveSources(lib.gm().campaign(),
                ScopeSettings.STARTING, new ScopeFilter(Tier.SYSTEM, null)));
        assertThat(as(lib.gm().owner(), () -> scopes.results(systemOnly, List.of(a0)))).isEmpty();
        assertThatThrownBy(() -> as(them.owner(), () -> scopes.results(scope, List.of(a0))))
                .isInstanceOf(IllegalStateException.class);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private static List<String> strings(JsonNode array) {
        List<String> found = new ArrayList<>();
        array.forEach(item -> found.add(item.asString()));
        return found;
    }

    private void assertRefused(UUID owner, ResolvedScope scope, List<UUID> ids) {
        assertThatThrownBy(() -> as(owner, () -> scopes.passages(scope, ids)))
                .as("%s", ids)
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.problem().status()).isEqualTo(403);
                    assertThat(e.problem().code()).isEqualTo("scope.passage_refused");
                    assertThat(e.problem().params()).isEmpty();
                    ids.stream().filter(java.util.Objects::nonNull)
                            .forEach(id -> assertThat(e.getMessage()).doesNotContain(id.toString()));
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

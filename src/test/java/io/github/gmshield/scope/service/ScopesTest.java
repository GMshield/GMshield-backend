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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
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
                ids -> ids.stream().filter(unended::contains).collect(Collectors.toSet()),
                beans.getBeanProvider(PlatformTransactionManager.class)), vault,
                beans.getBeanProvider(PlatformTransactionManager.class));
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
    void aTierSwitchedOffHasNoSourceAndTheOtherTierIsRefusedUntilStory35() {
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
        assertThat(ScopeSettings.STARTING).isEqualTo(new ScopeSettings(true, true, false, false));
        assertThatThrownBy(() -> new ScopeSettings(true, true, true, false))
                .isInstanceOf(IllegalArgumentException.class);
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

        assertThat(fieldNames(input)).containsExactly("question", "owner", "tiers", "tokenKey");
        assertThat(input.path("question").asString()).isEqualTo("Qual é o alcance da Espada Vorpal?");
        assertThat(input.path("owner").asString()).isEqualTo(lib.gm().owner().toString());
        JsonNode tiers = input.path("tiers");
        assertThat(tiers.size()).isEqualTo(2);
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
    void aTierWithNoSourceIsStillListedWithEmptyListsAndAnEmptyScopeHasTwoEmptyTiers() {
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
        assertThat(none.path("tiers").size()).isEqualTo(2);
        for (JsonNode tier : none.path("tiers")) {
            assertThat(tier.path("sources").isArray()).isTrue();
            assertThat(tier.path("sources").isEmpty()).isTrue();
            assertThat(tier.path("pending").isEmpty()).isTrue();
        }
        assertThat(none.path("owner").asString()).isEqualTo(empty.owner().toString());
    }

    @Test
    void aTiersPendingIsListedUpToItsFirstHundredInOrder() {
        ScopeFixture.Starter gm = fixture.starter();
        UUID version = fixture.material(gm.owner(), gm.campaign(), null, "ready").getFirst();
        List<UUID> passages = fixture.passages(gm.owner(), version, "P", 150);
        UUID job = Ids.newId();
        fixture.indexChange(gm.owner(), job, version, 0, 149);
        unended.add(job);
        ResolvedScope scope = as(gm.owner(), () -> scopes.resolve(gm.campaign(), ScopeSettings.STARTING));

        JsonNode input = as(gm.owner(), () -> scopes.questionInput(scope, "Vorpal?"));

        assertThat(scope.pending(Tier.CAMPAIGN)).hasSize(150);
        assertThat(Scopes.MAX_PENDING).isEqualTo(100);
        assertThat(strings(input.path("tiers").get(0).path("pending")))
                .containsExactlyElementsOf(passages.subList(0, 100).stream().map(UUID::toString).toList());
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

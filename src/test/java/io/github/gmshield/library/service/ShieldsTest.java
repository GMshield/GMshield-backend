package io.github.gmshield.library.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.library.data.CampaignRow;
import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.library.data.LibraryConflict;
import io.github.gmshield.library.data.ShieldRow;
import io.github.gmshield.library.data.SystemRow;
import io.github.gmshield.shared.data.InMemoryTransactionManager;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.TestVaults;
import io.github.gmshield.vault.service.Vault;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 6.1, the deploy window: a campaign that the previous release created after V53 has no Shield. Listing its
 * Shields creates the unnamed first one once; when another list created it meanwhile, the one unnamed Shield per
 * campaign refuses the second insert, and the list reads again instead of failing.
 *
 * <p>Part B: two deletions at once of a campaign's two Shields wait for the campaign's lock, so one deletes and the other
 * counts one Shield and is refused; the campaign keeps a Shield.
 */
class ShieldsTest {

    private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");

    private final AtomicInteger staleCounts = new AtomicInteger();
    /** A store whose first {@code staleCounts} counts say "none", as a list that read before another one inserted. */
    private final InMemoryLibraryStore store = new InMemoryLibraryStore() {
        @Override
        public long shieldCount(UUID campaignId) {
            return staleCounts.getAndUpdate(left -> Math.max(left - 1, 0)) > 0 ? 0 : super.shieldCount(campaignId);
        }
    };
    private final InMemoryTransactionManager transactions = new InMemoryTransactionManager();
    private final Vault vault = TestVaults.vault();
    private final Shields shields;

    ShieldsTest() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("transactions", transactions);
        beans.addBean("clock", Clock.fixed(NOW, ZoneOffset.UTC));
        shields = new Shields(store, vault, beans.getBeanProvider(PlatformTransactionManager.class),
                beans.getBeanProvider(Clock.class));
    }

    @Test
    void aCampaignWithoutAShieldGetsItsUnnamedFirstOneWhenItsShieldsAreListedAndOnlyOnce() {
        UUID owner = Ids.newId();
        UUID campaign = campaignWithoutShield(owner);

        Shields.ShieldPage first = OwnerContext.callAs(owner, () -> shields.shields(campaign, null, 0, 20)).orElseThrow();
        Shields.ShieldPage again = OwnerContext.callAs(owner, () -> shields.shields(campaign, null, 0, 20)).orElseThrow();

        assertThat(first.items()).hasSize(1);
        assertThat(first.items().getFirst().name()).isNull();
        assertThat(first.items().getFirst().version()).isZero();
        assertThat(first.total()).isEqualTo(1);
        assertThat(again.items()).containsExactlyElementsOf(first.items());
        assertThat(store.shields.values()).singleElement().satisfies(row -> {
            assertThat(row.campaignId()).isEqualTo(campaign);
            assertThat(row.ownerId()).isEqualTo(owner);
            assertThat(row.unnamed()).isTrue();
        });
    }

    @Test
    void whenAnotherListCreatedTheFirstShieldMeanwhileTheInsertIsRefusedAndTheListReadsAgain() {
        UUID owner = Ids.newId();
        UUID campaign = campaignWithoutShield(owner);
        UUID theirs = Ids.newId();
        OwnerContext.runAs(owner, () -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                store.insertShield(ShieldRow.unnamed(theirs, owner, campaign, NOW))));
        int rollbacks = transactions.rollbacks.get();
        // Both counts of this list (before and inside its insert's transaction) still saw no Shield.
        staleCounts.set(2);

        Shields.ShieldPage page = OwnerContext.callAs(owner, () -> shields.shields(campaign, null, 0, 20)).orElseThrow();

        assertThat(page.items()).extracting(Shields.Shield::id).containsExactly(theirs);
        assertThat(store.shields).containsOnlyKeys(theirs);
        assertThat(transactions.rollbacks.get()).isEqualTo(rollbacks + 1);
    }

    @Test
    void aSecondUnnamedShieldOrATakenNameIsRefusedByTheStore() {
        UUID owner = Ids.newId();
        UUID campaign = campaignWithoutShield(owner);
        byte[] fingerprint = vault.fingerprint("shield", "name", "combate");
        OwnerContext.runAs(owner, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            store.insertShield(ShieldRow.unnamed(Ids.newId(), owner, campaign, NOW));
            store.insertShield(new ShieldRow(Ids.newId(), owner, campaign, new byte[] {1}, fingerprint, 0, NOW));
        }));

        assertThatThrownBy(() -> OwnerContext.runAs(owner, () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> store.insertShield(ShieldRow.unnamed(Ids.newId(), owner, campaign, NOW)))))
                .isInstanceOfSatisfying(LibraryConflict.class,
                        conflict -> assertThat(conflict.reason()).isEqualTo(LibraryConflict.Reason.UNNAMED_SHIELD_TAKEN));
        assertThatThrownBy(() -> OwnerContext.runAs(owner, () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> store.insertShield(new ShieldRow(Ids.newId(), owner, campaign,
                        new byte[] {2}, fingerprint, 0, NOW)))))
                .isInstanceOfSatisfying(LibraryConflict.class,
                        conflict -> assertThat(conflict.reason()).isEqualTo(LibraryConflict.Reason.SHIELD_NAME_TAKEN));
        assertThat(store.shields).hasSize(2);
    }

    @Test
    void anUnknownCampaignIsNotFoundAndGetsNoShield() {
        UUID owner = Ids.newId();
        UUID other = Ids.newId();
        UUID campaign = campaignWithoutShield(other);

        assertThat(OwnerContext.callAs(owner, () -> shields.shields(campaign, null, 0, 20))).isEmpty();
        assertThat(OwnerContext.callAs(owner, () -> shields.shields(Ids.newId(), null, 0, 20))).isEmpty();
        assertThat(store.shields).isEmpty();
        assertThat(List.copyOf(store.campaigns.keySet())).containsExactly(campaign);
    }

    @Test
    void twoDeletionsAtOnceOfTheCampaignsTwoShieldsLeaveOneTheSecondIsRefused() throws Exception {
        UUID owner = Ids.newId();
        UUID campaign = campaignWithoutShield(owner);
        UUID first = Ids.newId();
        UUID second = Ids.newId();
        OwnerContext.runAs(owner, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            store.insertShield(ShieldRow.unnamed(first, owner, campaign, NOW));
            store.insertShield(new ShieldRow(second, owner, campaign, new byte[] {1},
                    vault.fingerprint("shield", "name", "combate"), 0, NOW));
        }));
        // A transaction holds the campaign's lock while both deletions start, so that both have to wait for it.
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> OwnerContext.runAs(owner, () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                    assertThat(store.lockCampaign(campaign)).isPresent();
                    locked.countDown();
                    try {
                        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                })));
        holder.start();
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        List<Thread> deleters = new ArrayList<>();
        List<CompletableFuture<String>> outcomes = new ArrayList<>();
        for (UUID shield : List.of(first, second)) {
            CompletableFuture<String> outcome = new CompletableFuture<>();
            outcomes.add(outcome);
            Thread deleter = new Thread(() -> {
                try {
                    boolean deleted = OwnerContext.callAs(owner, () -> shields.deleteShield(campaign, shield));
                    outcome.complete(deleted ? "deleted" : "not found");
                } catch (ApiException refused) {
                    outcome.complete(refused.problem().code());
                }
            });
            deleters.add(deleter);
            deleter.start();
        }
        for (Thread deleter : deleters) {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (deleter.getState() != Thread.State.WAITING && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            assertThat(deleter.getState()).isEqualTo(Thread.State.WAITING);
        }
        release.countDown();
        holder.join(10_000);

        List<String> results = new ArrayList<>();
        for (CompletableFuture<String> outcome : outcomes) {
            results.add(outcome.get(10, TimeUnit.SECONDS));
        }
        assertThat(results).containsExactlyInAnyOrder("deleted", "request.last_shield");
        assertThat(store.shields.values()).filteredOn(row -> row.campaignId().equals(campaign)).hasSize(1);
        assertThat(OwnerContext.callAs(owner, () -> shields.shieldDeletion(campaign,
                store.shields.values().iterator().next().id()))).hasValueSatisfying(deletion -> {
                    assertThat(deletion.last()).isTrue();
                    assertThat(deletion.notes()).isZero();
                    assertThat(deletion.rolls()).isZero();
                });
    }

    @Test
    void theStoreRefusesToLockOutsideATransactionAndForAnotherOwnersCampaign() {
        UUID owner = Ids.newId();
        UUID campaign = campaignWithoutShield(owner);

        assertThatThrownBy(() -> OwnerContext.runAs(owner, () -> store.lockCampaign(campaign)))
                .isInstanceOf(IllegalStateException.class);
        Optional<CampaignRow> theirs = OwnerContext.callAs(Ids.newId(), () -> new TransactionTemplate(transactions)
                .execute(status -> store.lockCampaign(campaign)));
        Optional<CampaignRow> own = OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions)
                .execute(status -> store.lockCampaign(campaign)));
        assertThat(theirs).isEmpty();
        assertThat(own).isPresent();
    }

    /** A campaign as the previous release creates it after V53: with no Shield. */
    private UUID campaignWithoutShield(UUID owner) {
        UUID system = Ids.newId();
        UUID campaign = Ids.newId();
        OwnerContext.runAs(owner, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            store.insertSystem(new SystemRow(system, owner, vault.encryptText(new Binding("system", "name", system, owner),
                    "S"), vault.fingerprint("system", "name", "s"), NOW));
            store.insertCampaign(new CampaignRow(campaign, owner, system,
                    vault.encryptText(new Binding("campaign", "name", campaign, owner), "Mesa"), NOW));
        }));
        return campaign;
    }
}

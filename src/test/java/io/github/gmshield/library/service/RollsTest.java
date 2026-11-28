package io.github.gmshield.library.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.library.data.CampaignRow;
import io.github.gmshield.library.data.InMemoryDeletionStore;
import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.library.data.RollRow;
import io.github.gmshield.library.data.ShieldRow;
import io.github.gmshield.library.data.SystemRow;
import io.github.gmshield.shared.data.InMemoryTransactionManager;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.IntegrityException;
import io.github.gmshield.vault.service.TestVaults;
import io.github.gmshield.vault.service.Vault;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 6.4 without a database: a roll is checked by the frontend's rules, stored once by its key (a repeat in its
 * Shield answers the stored roll, the key in another Shield is refused, a key committed meanwhile by a concurrent repeat
 * is read back), encrypted as one ciphertext bound to its row and owner, listed newest first a page at a time, and
 * cleared; another owner's Shield is not found and nothing is stored for it.
 */
class RollsTest {

    private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");

    /** How many key reads still answer "none", as a repeat that read before another one committed. */
    private final AtomicInteger staleKeyReads = new AtomicInteger();
    private final InMemoryLibraryStore store = new InMemoryLibraryStore() {
        @Override
        public Optional<RollRow> findRollByKey(UUID rollKey) {
            return staleKeyReads.getAndUpdate(left -> Math.max(left - 1, 0)) > 0 ? Optional.empty()
                    : super.findRollByKey(rollKey);
        }
    };
    private final InMemoryTransactionManager transactions = new InMemoryTransactionManager();
    private final Vault vault = TestVaults.vault();
    private final Rolls rolls;

    RollsTest() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("transactions", transactions);
        beans.addBean("clock", Clock.fixed(NOW, ZoneOffset.UTC));
        beans.addBean("deletion", new SystemDeletion(store, new InMemoryDeletionStore(),
                beans.getBeanProvider(SystemDeletionHandler.class), beans.getBeanProvider(PlatformTransactionManager.class),
                beans.getBeanProvider(SystemDeletionSweepSchedule.class), beans.getBeanProvider(Clock.class)));
        Shields shields = new Shields(store, vault, beans.getBeanProvider(SystemDeletion.class),
                beans.getBeanProvider(PlatformTransactionManager.class), beans.getBeanProvider(Clock.class));
        rolls = new Rolls(store, vault, shields, beans.getBeanProvider(PlatformTransactionManager.class),
                beans.getBeanProvider(Clock.class));
    }

    record Place(UUID owner, UUID campaign, UUID shield, UUID otherShield) {
    }

    @Test
    void aRollIsStoredWithItsNormalisedFormulaComputedTotalTrimmedLabelAndTimeAsOneBoundCiphertext() {
        Place place = place();
        UUID key = UUID.randomUUID();
        Instant at = NOW.minusSeconds(3).plusNanos(123_456_789);

        Rolls.Kept kept = keep(place, key, " 2D6 + 3 ", List.of(4, 5), "  Iniciativa ", at).orElseThrow();

        assertThat(kept.created()).isTrue();
        Rolls.Roll roll = kept.roll();
        assertThat(roll.key()).isEqualTo(key);
        assertThat(roll.formula()).isEqualTo("2d6+3");
        assertThat(roll.results()).containsExactly(4, 5);
        assertThat(roll.total()).isEqualTo(12);
        assertThat(roll.label()).isEqualTo("Iniciativa");
        assertThat(roll.rolledAt()).isEqualTo(NOW.minusSeconds(3).plusNanos(123_456_000));
        RollRow row = store.rolls.get(roll.id());
        assertThat(row.ownerId()).isEqualTo(place.owner());
        assertThat(row.shieldId()).isEqualTo(place.shield());
        assertThat(row.rollKey()).isEqualTo(key);
        assertThat(row.createdAt()).isEqualTo(NOW);
        assertThat(new String(row.content(), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("2d6")
                .doesNotContain("Iniciativa");
        assertThat(vault.decryptText(new Binding("roll", "content", row.id(), place.owner()), row.content()))
                .contains("2d6+3").contains("Iniciativa");
        for (Binding other : List.of(new Binding("roll", "label", row.id(), place.owner()),
                new Binding("shield", "content", row.id(), place.owner()),
                new Binding("roll", "content", Ids.newId(), place.owner()),
                new Binding("roll", "content", row.id(), Ids.newId()))) {
            assertThatThrownBy(() -> vault.decryptText(other, row.content())).isInstanceOf(IntegrityException.class);
        }
        // A blank label is none.
        assertThat(keep(place, UUID.randomUUID(), "d20", List.of(20), "   ", NOW).orElseThrow().roll().label()).isNull();
        assertThat(keep(place, UUID.randomUUID(), "d20", List.of(1), null, NOW).orElseThrow().roll().label()).isNull();
    }

    @Test
    void aRepeatInItsShieldAnswersTheStoredRollAndTheKeyInAnotherShieldIsRefused() {
        Place place = place();
        UUID key = UUID.randomUUID();
        Rolls.Roll first = keep(place, key, "1d20", List.of(17), "Ataque", NOW).orElseThrow().roll();

        Rolls.Kept again = keep(place, key, "1d20", List.of(17), "Ataque", NOW).orElseThrow();

        assertThat(again.created()).isFalse();
        assertThat(again.roll()).isEqualTo(first);
        assertThat(store.rolls).hasSize(1);
        assertThatThrownBy(() -> keep(place, key, "1d20", List.of(17), "Ataque", NOW, place.otherShield()))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.problem().status()).isEqualTo(409);
                    assertThat(refused.problem().code()).isEqualTo("request.roll_key_taken");
                });
        assertThat(store.rolls).hasSize(1);
    }

    @Test
    void aRepeatThatAnotherCommittedMeanwhileReadsItsRowBackInsteadOfFailing() {
        Place place = place();
        UUID key = UUID.randomUUID();
        Rolls.Roll theirs = keep(place, key, "1d6", List.of(3), null, NOW).orElseThrow().roll();
        // This repeat's first key read saw nothing: its insert then finds the key held.
        staleKeyReads.set(1);

        Rolls.Kept kept = keep(place, key, "1d6", List.of(3), null, NOW).orElseThrow();

        assertThat(kept.created()).isFalse();
        assertThat(kept.roll()).isEqualTo(theirs);
        assertThat(store.rolls).hasSize(1);
        assertThat(transactions.rollbacks.get()).isZero();
    }

    @Test
    void aRollTheBrowserCouldNotHaveMadeIsRefusedAndNothingIsStored() {
        Place place = place();
        List<Runnable> refused = new ArrayList<>(List.of(
                () -> keep(place, UUID.randomUUID(), "1d6", List.of(7), null, NOW),
                () -> keep(place, UUID.randomUUID(), "1d6", List.of(3, 4), null, NOW),
                () -> keep(place, UUID.randomUUID(), "2d6-1d4", List.of(6, 6), null, NOW),
                () -> keep(place, UUID.randomUUID(), "1d10 0", List.of(3), null, NOW),
                () -> keep(place, UUID.randomUUID(), "1d7", List.of(3), null, NOW),
                () -> keep(place, UUID.randomUUID(), null, List.of(3), null, NOW),
                () -> keep(place, UUID.randomUUID(), "1d6", null, null, NOW),
                () -> keep(place, UUID.randomUUID(), "1d6", List.of(3), "x".repeat(81), NOW),
                () -> keep(place, UUID.randomUUID(), "1d6", List.of(3), "🎲".repeat(81), NOW),
                () -> keep(place, UUID.randomUUID(), "1d6", List.of(3), null, NOW.plus(Duration.ofMinutes(5)).plusMillis(1)),
                () -> keep(place, UUID.randomUUID(), "1d6", List.of(3), null, null),
                // A far-past time parses, but would not fit PostgreSQL's timestamptz.
                () -> keep(place, UUID.randomUUID(), "1d6", List.of(3), null, Instant.parse("-5000-01-01T00:00:00Z")),
                () -> keep(place, UUID.randomUUID(), "1d6", List.of(3), null, Instant.EPOCH.minusNanos(1000))));
        for (Runnable request : refused) {
            assertThatThrownBy(request::run).isInstanceOfSatisfying(ApiException.class, problem -> {
                assertThat(problem.problem().status()).isEqualTo(400);
                assertThat(problem.problem().code()).isEqualTo("request.roll_invalid");
            });
        }
        assertThat(store.rolls).isEmpty();
        // At the limits: 80 code points (an emoji is one), exactly five minutes ahead, any time back to the epoch.
        assertThat(keep(place, UUID.randomUUID(), "1d6", List.of(3), " " + "🎲".repeat(80) + " ",
                NOW.plus(Duration.ofMinutes(5)))).isPresent();
        assertThat(keep(place, UUID.randomUUID(), "1d6", List.of(3), null, Instant.parse("2020-01-01T00:00:00Z")))
                .isPresent();
        assertThat(keep(place, UUID.randomUUID(), "1d6", List.of(3), null, Instant.EPOCH)).isPresent();
        assertThat(store.rolls).hasSize(3);
    }

    @Test
    void anotherOwnersOrAnUnknownShieldIsNotFoundAndNothingIsStoredOrCleared() {
        Place place = place();
        UUID key = UUID.randomUUID();
        keep(place, key, "1d6", List.of(3), null, NOW);
        UUID stranger = Ids.newId();

        assertThat(OwnerContext.callAs(stranger, () -> rolls.keep(place.campaign(), place.shield(), UUID.randomUUID(),
                "1d6", List.of(3), null, NOW))).isEmpty();
        // B's own key equal to A's: still not found in A's Shield.
        assertThat(OwnerContext.callAs(stranger, () -> rolls.keep(place.campaign(), place.shield(), key, "1d6",
                List.of(3), null, NOW))).isEmpty();
        assertThat(OwnerContext.callAs(stranger, () -> rolls.rolls(place.campaign(), place.shield(), null, 10)))
                .isEmpty();
        assertThat(OwnerContext.callAs(stranger, () -> rolls.clear(place.campaign(), place.shield()))).isFalse();
        assertThat(OwnerContext.callAs(place.owner(), () -> rolls.keep(Ids.newId(), place.shield(),
                UUID.randomUUID(), "1d6", List.of(3), null, NOW))).isEmpty();
        assertThat(OwnerContext.callAs(place.owner(), () -> rolls.rolls(place.campaign(), Ids.newId(), null, 10)))
                .isEmpty();
        assertThat(store.rolls).hasSize(1);
    }

    @Test
    void theLogIsNewestFirstByTimeThenByIdAPageAtATimeAndAGoneCursorGivesAnEmptyPage() {
        Place place = place();
        List<UUID> ids = new ArrayList<>();
        // Rolled out of order; two share a time.
        for (int seconds : new int[] {30, 10, 20, 20, 40}) {
            ids.add(keep(place, UUID.randomUUID(), "1d4", List.of(1), null, NOW.minusSeconds(100 - seconds))
                    .orElseThrow().roll().id());
        }

        Rolls.RollPage first = OwnerContext.callAs(place.owner(),
                () -> rolls.rolls(place.campaign(), place.shield(), null, 2)).orElseThrow();
        Rolls.RollPage second = OwnerContext.callAs(place.owner(),
                () -> rolls.rolls(place.campaign(), place.shield(), first.nextCursor(), 2)).orElseThrow();
        Rolls.RollPage last = OwnerContext.callAs(place.owner(),
                () -> rolls.rolls(place.campaign(), place.shield(), second.nextCursor(), 2)).orElseThrow();

        assertThat(first.items()).extracting(Rolls.Roll::id).containsExactly(ids.get(4), ids.get(0));
        // The two of one time: the higher id first.
        List<UUID> tied = ids.subList(2, 4).stream().sorted(java.util.Comparator.reverseOrder()).toList();
        assertThat(second.items()).extracting(Rolls.Roll::id).containsExactlyElementsOf(tied);
        assertThat(last.items()).extracting(Rolls.Roll::id).containsExactly(ids.get(1));
        assertThat(last.nextCursor()).isNull();
        assertThat(first.total()).isEqualTo(5);
        assertThat(OwnerContext.callAs(place.owner(),
                () -> rolls.rolls(place.campaign(), place.shield(), Ids.newId(), 2)).orElseThrow().items()).isEmpty();
        // The other Shield's log is its own.
        assertThat(OwnerContext.callAs(place.owner(),
                () -> rolls.rolls(place.campaign(), place.otherShield(), null, 10)).orElseThrow().total()).isZero();
    }

    @Test
    void clearingRemovesTheShieldsRollsOnlyAndTheShieldsDeletionsRemoveThemFirst() {
        Place place = place();
        keep(place, UUID.randomUUID(), "1d6", List.of(1), null, NOW);
        keep(place, UUID.randomUUID(), "1d6", List.of(2), null, NOW);
        UUID kept = keep(place, UUID.randomUUID(), "1d6", List.of(3), null, NOW, place.otherShield()).orElseThrow()
                .roll().id();

        assertThat(OwnerContext.callAs(place.owner(), () -> rolls.clear(place.campaign(), place.shield()))).isTrue();
        assertThat(store.rolls).containsOnlyKeys(kept);
        assertThat(OwnerContext.callAs(place.owner(), () -> rolls.clear(place.campaign(), place.shield()))).isTrue();

        // A campaign's deletion takes the Shields with their rolls.
        OwnerContext.runAs(place.owner(), () -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                assertThat(store.deleteShieldsOf(List.of(place.campaign()))).isEqualTo(2)));
        assertThat(store.rolls).isEmpty();
        assertThat(store.shields).isEmpty();
    }

    private Optional<Rolls.Kept> keep(Place place, UUID key, String formula, List<Integer> results, String label,
                                      Instant at) {
        return keep(place, key, formula, results, label, at, place.shield());
    }

    private Optional<Rolls.Kept> keep(Place place, UUID key, String formula, List<Integer> results, String label,
                                      Instant at, UUID shield) {
        return OwnerContext.callAs(place.owner(), () -> rolls.keep(place.campaign(), shield, key, formula, results,
                label, at));
    }

    /** An owner's campaign with two Shields. */
    private Place place() {
        UUID owner = Ids.newId();
        UUID system = Ids.newId();
        UUID campaign = Ids.newId();
        UUID shield = Ids.newId();
        UUID other = Ids.newId();
        OwnerContext.runAs(owner, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            store.insertSystem(new SystemRow(system, owner, vault.encryptText(new Binding("system", "name", system, owner),
                    "S"), vault.fingerprint("system", "name", "s"), NOW));
            store.insertCampaign(new CampaignRow(campaign, owner, system,
                    vault.encryptText(new Binding("campaign", "name", campaign, owner), "Mesa"), NOW));
            store.insertShield(ShieldRow.unnamed(shield, owner, campaign, NOW));
            store.insertShield(new ShieldRow(other, owner, campaign,
                    vault.encryptText(new Binding("shield", "name", other, owner), "Combate"),
                    vault.fingerprint("shield", "name", "combate"), 0, NOW));
        }));
        return new Place(owner, campaign, shield, other);
    }
}

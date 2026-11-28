package io.github.gmshield.library.service;

import io.github.gmshield.library.data.LibraryStore;
import io.github.gmshield.library.data.RollRow;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stored rolls of a Shield (Story 6.4, AD-31). The browser draws the dice (Story 6.3) and sends each roll with a key
 * it made; this keeps the roll exactly once, never rolls and never edits one. Everything here acts for the owner of the
 * work ({@link OwnerContext}): another owner's campaign or Shield, a deleting Shield and a campaign being deleted read
 * exactly like an unknown one.
 *
 * <p>A roll is stored only when the browser could have made it ({@code request.roll_invalid}, 400, otherwise): its
 * formula reads under {@code dice.ts}'s grammar ({@link DiceFormula}), its results are its dice in order, one per die and
 * each within its die's range, its label is at most {@value #LABEL_MAX} code points after trimming (blank for none), and
 * its time is from 1970-01-01T00:00:00Z (a far-past time would not fit the column) to at most five minutes ahead of the
 * server's clock. The total is computed here.
 *
 * <p>Keys are unique per owner. A key the owner already holds in that Shield answers the stored roll and creates
 * nothing; the same key in another of the owner's Shields is {@code request.roll_key_taken} (409). The insert writes
 * nothing for a held key, a key committed meanwhile by a concurrent repeat included, and the roll is then read back, so
 * a repeat never fails. Writes find the Shield through {@link Shields#lockedShield}, under the campaign's row lock that a
 * Shield's deletion takes too, so no roll lands in a Shield being deleted.
 *
 * <p>The formula, the results and the label are encrypted together as one vault ciphertext bound to the entity
 * {@code roll}, the field {@code content}, the row and the owner; the time stays plain, for the log's order (newest
 * first, then by id). A roll never reaches the worker, the index, a search, an answer or a scope, and is never a note or
 * an artifact. Logs carry ids and counts only.
 */
@Service
public class Rolls {

    static final String ROLL = "roll";
    static final String FIELD_CONTENT = "content";
    /** The longest label, in code points after trimming. */
    public static final int LABEL_MAX = 80;
    /** How far ahead of the server's clock a roll's time may be. */
    static final Duration AHEAD = Duration.ofMinutes(5);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Logger log = LoggerFactory.getLogger(Rolls.class);

    private final LibraryStore store;
    private final Vault vault;
    private final Shields shields;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final Clock clock;

    Rolls(LibraryStore store, Vault vault, Shields shields, ObjectProvider<PlatformTransactionManager> transactions,
          ObjectProvider<Clock> clock) {
        this.store = store;
        this.vault = vault;
        this.shields = shields;
        this.transactions = transactions;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** A stored roll: its key, normalised formula, results in order, total, label (null for none) and time. */
    public record Roll(UUID id, UUID key, String formula, List<Integer> results, long total, String label,
                       Instant rolledAt) {

        public Roll {
            results = List.copyOf(results);
        }

        @Override
        public String toString() {
            return "Roll[id=" + id + ", rolledAt=" + rolledAt + "]";
        }
    }

    /** A roll as {@link #keep} answers it: stored now ({@code created}), or held already under its key. */
    public record Kept(Roll roll, boolean created) {
    }

    /** One page of a Shield's rolls, newest first, the cursor of the next one (null on the last), and how many in all. */
    public record RollPage(List<Roll> items, UUID nextCursor, long total) {
    }

    /** What is encrypted: the formula, the results and the label, together. */
    private record Content(String formula, List<Integer> results, String label) {
    }

    /**
     * Keeps a roll of the owner's Shield {@code shieldId} of the campaign {@code campaignId}, as the class says: the roll
     * stored now, or the one already held under {@code key} in that Shield. Empty, and nothing is stored, for any Shield
     * {@link Shields#shield} does not find. Throws {@code request.roll_invalid} (400) and {@code request.roll_key_taken}
     * (409); nothing is stored then.
     */
    public Optional<Kept> keep(UUID campaignId, UUID shieldId, UUID key, String typedFormula, List<Integer> results,
                               String typedLabel, Instant rolledAt) {
        DiceFormula formula = DiceFormula.parse(typedFormula).orElseThrow(Rolls::invalid);
        long total = formula.total(results).orElseThrow(Rolls::invalid);
        String label = label(typedLabel);
        Instant now = now();
        if (rolledAt == null || rolledAt.isBefore(Instant.EPOCH) || rolledAt.isAfter(now.plus(AHEAD))) {
            throw invalid();
        }
        UUID owner = OwnerContext.require();
        UUID id = Ids.newId();
        Instant at = rolledAt.truncatedTo(ChronoUnit.MICROS);
        Optional<Kept> kept = writing(() -> shields.lockedShield(campaignId, shieldId).map(shield -> {
            Optional<Kept> held = held(shieldId, key);
            if (held.isPresent()) {
                return held.get();
            }
            Content content = new Content(formula.text(), results, label);
            RollRow row = new RollRow(id, owner, shieldId, key,
                    vault.encryptText(binding(id, owner), JSON.writeValueAsString(content)), at, now);
            if (!store.insertRoll(row)) {
                // A concurrent repeat committed this key meanwhile: its row answers.
                return held(shieldId, key).orElseThrow(() -> new IllegalStateException("library.roll_key_vanished"));
            }
            return new Kept(new Roll(id, key, formula.text(), results, total, label, at), true);
        }));
        kept.ifPresent(roll -> log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("shieldId", shieldId).addKeyValue("rollId", roll.roll().id())
                .addKeyValue("created", roll.created()).log("library.roll_kept"));
        return kept;
    }

    /**
     * The rolls of the owner's Shield {@code shieldId} of the campaign {@code campaignId}, newest first, after the roll
     * {@code cursor} when it is given (an empty page when that roll is gone), at most {@code limit} (1 to
     * {@value Library#MAX_LIMIT}); empty for any Shield {@link Shields#shield} does not find.
     */
    public Optional<RollPage> rolls(UUID campaignId, UUID shieldId, UUID cursor, int limit) {
        Library.checkLimit(limit);
        return reading(() -> store.findCampaign(campaignId).flatMap(campaign -> store.findShield(campaign.id(), shieldId))
                .map(shield -> {
                    List<RollRow> found = store.rolls(shield.id(), cursor, limit + 1);
                    List<RollRow> page = found.size() > limit ? found.subList(0, limit) : found;
                    UUID next = found.size() > limit ? page.getLast().id() : null;
                    return new RollPage(page.stream().map(this::open).toList(), next, store.rollCount(shield.id()));
                }));
    }

    /**
     * Deletes every stored roll of the owner's Shield {@code shieldId} of the campaign {@code campaignId}: true once they
     * are gone (none is fine), false for any Shield {@link Shields#shield} does not find.
     */
    public boolean clear(UUID campaignId, UUID shieldId) {
        UUID owner = OwnerContext.require();
        Optional<Integer> cleared = writing(() -> shields.lockedShield(campaignId, shieldId)
                .map(shield -> store.deleteRollsOf(shieldId)));
        cleared.ifPresent(count -> log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("shieldId", shieldId).addKeyValue("count", count).log("library.rolls_cleared"));
        return cleared.isPresent();
    }

    /** The roll the owner holds under {@code key}: answered when it is of this Shield, refused when of another. */
    private Optional<Kept> held(UUID shieldId, UUID key) {
        return store.findRollByKey(key).map(row -> {
            if (!row.shieldId().equals(shieldId)) {
                throw new ApiException(LibraryProblems.ROLL_KEY_TAKEN);
            }
            return new Kept(open(row), false);
        });
    }

    private Roll open(RollRow row) {
        Content content = JSON.readValue(vault.decryptText(binding(row.id(), row.ownerId()), row.content()),
                Content.class);
        long total = DiceFormula.parse(content.formula()).flatMap(formula -> formula.total(content.results()))
                .orElseThrow(() -> new IllegalStateException("library.roll_unreadable"));
        return new Roll(row.id(), row.rollKey(), content.formula(), content.results(), total, content.label(),
                row.rolledAt());
    }

    /** The label, trimmed; null when absent or blank; {@code request.roll_invalid} past {@value #LABEL_MAX} code points. */
    private static String label(String typed) {
        String label = typed == null ? "" : typed.strip();
        if (label.codePointCount(0, label.length()) > LABEL_MAX) {
            throw invalid();
        }
        return label.isEmpty() ? null : label;
    }

    /** {@code request.roll_invalid} (400): also the web layer's answer to a roll field of the wrong kind. */
    public static ApiException invalid() {
        return new ApiException(LibraryProblems.ROLL_INVALID);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** One read-only transaction for the reads of one answer, so they come from one session. */
    private <T> T reading(Supplier<T> read) {
        OwnerContext.require();
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }

    /** One transaction for a write and its checks: a problem thrown inside rolls everything back. */
    private <T> T writing(Supplier<T> write) {
        OwnerContext.require();
        return new TransactionTemplate(transactions.getObject()).execute(status -> write.get());
    }

    private static Binding binding(UUID rowId, UUID owner) {
        return new Binding(ROLL, FIELD_CONTENT, rowId, owner);
    }
}

package io.github.gmshield.library.service;

import io.github.gmshield.library.data.DeletionRow;
import io.github.gmshield.library.data.DeletionStore;
import io.github.gmshield.library.data.LibraryStore;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deleting one of the owner's systems (Story 3.1 part B, AD-22): a recorded process that resumes after a restart.
 * What goes: the system, its campaigns, their conversations and every material attached to one of its campaigns or to
 * this system and no other; a material also attached to another system stays and only loses this attachment. Each
 * module removes its own part through its {@link SystemDeletionHandler}; {@code library} removes the campaigns and the
 * system last.
 *
 * <ul>
 *   <li>{@link #preview}: what the confirmation dialog names, {@link Counts}; each module counts its own part.</li>
 *   <li>{@link #delete}: in one transaction the system becomes {@code deleting} (from the commit it and its campaigns
 *       are "not found" to every read and write), each handler prepares (its jobs cancelled, its items hidden), and a
 *       {@code deletion} row records it with {@code not_before}: the latest claim lapse of what was cancelled, or now.
 *       After the commit a one-shot pass is scheduled at {@code not_before} ({@link SystemDeletionSweepSchedule}).</li>
 *   <li>{@link #sweep}: at the start and every {@code gmshield.library.deletion-sweep-every}, a pass of every deletion
 *       that is due, as its owner ({@link OwnerContext#callAs}).</li>
 * </ul>
 *
 * A pass collects again what goes and prepares it again, so whatever arrived during the wait (an upload that
 * completed, a question asked as the system went) goes too; when that cancelled a running job, the deletion waits for
 * its claim to lapse ({@code not_before} moves) and the pass stops. Otherwise each handler removes its part, then one
 * transaction deletes the campaigns and the system and marks the deletion done. Every step is safe to repeat; a pass
 * that fails leaves the deletion for the next one. Two passes of one deletion never run at once in this backend.
 * Logs carry ids, counts and times only.
 *
 * <p>Story 3.2 part B: deleting one of the owner's campaigns is the same process with no system part, a
 * {@code deletion} row of kind {@code campaign}. What goes: the campaign, its conversation and every material attached
 * to it; nothing of its system or of the other campaigns. {@link #previewCampaign} and {@link #deleteCampaign} start
 * it; each handler is given {@code Going(null, [campaign])}; the same sweep and one-shot passes run it, the row's kind
 * picking what is collected and what {@code library} removes last (the campaign row). A system deleted while one of its
 * campaigns is deleting takes that campaign too; the campaign's own deletion then finds it gone and is marked done.
 */
@Service
public class SystemDeletion {

    /** The parts a handler counts ({@link SystemDeletionHandler#part}). */
    public static final String CONVERSATIONS = "conversations";
    public static final String MATERIALS = "materials";
    static final int BATCH = 50;
    private static final Logger log = LoggerFactory.getLogger(SystemDeletion.class);

    private final LibraryStore store;
    private final DeletionStore deletions;
    private final ObjectProvider<SystemDeletionHandler> handlers;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final ObjectProvider<SystemDeletionSweepSchedule> schedule;
    private final Clock clock;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    SystemDeletion(LibraryStore store, DeletionStore deletions, ObjectProvider<SystemDeletionHandler> handlers,
                   ObjectProvider<PlatformTransactionManager> transactions,
                   ObjectProvider<SystemDeletionSweepSchedule> schedule, ObjectProvider<Clock> clock) {
        this.store = store;
        this.deletions = deletions;
        this.handlers = handlers;
        this.transactions = transactions;
        this.schedule = schedule;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** What goes with a system, as the confirmation dialog names it. */
    public record Counts(long campaigns, long conversations, long materials) {
    }

    /** What goes with a campaign, as the confirmation dialog names it (Story 3.2 part B). */
    public record CampaignCounts(long conversations, long materials) {
    }

    /** What goes with the owner's system {@code systemId}; empty for an unknown system, another owner's or a deleting one. */
    public Optional<Counts> preview(UUID systemId) {
        return reading(() -> store.findSystem(systemId).map(system -> {
            SystemDeletionHandler.Going going = new SystemDeletionHandler.Going(system.id(),
                    store.campaignIdsOf(system.id()));
            Map<String, Long> parts = new HashMap<>();
            for (SystemDeletionHandler handler : handlers()) {
                parts.merge(handler.part(), handler.count(going), Long::sum);
            }
            return new Counts(going.campaignIds().size(), parts.getOrDefault(CONVERSATIONS, 0L),
                    parts.getOrDefault(MATERIALS, 0L));
        }));
    }

    /**
     * What goes with the owner's campaign {@code campaignId} (Story 3.2 part B); empty for an unknown campaign, another
     * owner's, a deleting one or one of a deleting system.
     */
    public Optional<CampaignCounts> previewCampaign(UUID campaignId) {
        return reading(() -> store.findCampaign(campaignId).map(campaign -> {
            SystemDeletionHandler.Going going = new SystemDeletionHandler.Going(null, List.of(campaign.id()));
            Map<String, Long> parts = new HashMap<>();
            for (SystemDeletionHandler handler : handlers()) {
                parts.merge(handler.part(), handler.count(going), Long::sum);
            }
            return new CampaignCounts(parts.getOrDefault(CONVERSATIONS, 0L), parts.getOrDefault(MATERIALS, 0L));
        }));
    }

    /**
     * Starts the deletion of the owner's campaign {@code campaignId} (Story 3.2 part B), as the class says: archived,
     * current or last, it goes; the current campaign then falls back by itself. False (nothing changes) for an unknown
     * campaign, another owner's, one already deleting or one of a deleting system.
     */
    public boolean deleteCampaign(UUID campaignId) {
        UUID owner = OwnerContext.require();
        Instant now = now();
        Optional<DeletionRow> started = writing(() -> {
            if (!store.markCampaignDeleting(campaignId, now)) {
                return Optional.<DeletionRow>empty();
            }
            DeletionRow row = new DeletionRow(Ids.newId(), owner, DeletionRow.CAMPAIGN, campaignId,
                    prepare(goingOfCampaign(campaignId), now), now, null);
            deletions.insert(row);
            log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                    .addKeyValue("deletionId", row.id()).addKeyValue("notBefore", row.notBefore())
                    .log("library.campaign_deleting");
            return Optional.of(row);
        });
        started.ifPresent(row -> later(row.ownerId(), row.id(), row.notBefore()));
        return started.isPresent();
    }

    /**
     * Starts the deletion of the owner's system {@code systemId}, as the class says; false (nothing changes) for an
     * unknown system, another owner's or one already deleting.
     */
    public boolean delete(UUID systemId) {
        UUID owner = OwnerContext.require();
        Instant now = now();
        Optional<DeletionRow> started = writing(() -> {
            if (!store.markDeleting(systemId, now)) {
                return Optional.<DeletionRow>empty();
            }
            SystemDeletionHandler.Going going = new SystemDeletionHandler.Going(systemId,
                    store.campaignIdsOfDeleting(systemId));
            DeletionRow row = new DeletionRow(Ids.newId(), owner, DeletionRow.SYSTEM, systemId, prepare(going, now), now,
                    null);
            deletions.insert(row);
            log.atInfo().addKeyValue("ownerId", owner).addKeyValue("systemId", systemId)
                    .addKeyValue("deletionId", row.id()).addKeyValue("campaigns", going.campaignIds().size())
                    .addKeyValue("notBefore", row.notBefore()).log("library.system_deleting");
            return Optional.of(row);
        });
        started.ifPresent(row -> later(row.ownerId(), row.id(), row.notBefore()));
        return started.isPresent();
    }

    /** One sweep: a pass of every deletion that is due, as its owner. Answers how many it finished. Never throws. */
    public int sweep() {
        int finished = 0;
        try {
            for (DeletionRow row : deletions.due(now(), BATCH)) {
                finished += run(row.ownerId(), row.id()) ? 1 : 0;
            }
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("errorType", failure.getClass().getName()).log("library.deletion_sweep_failed");
        }
        return finished;
    }

    /**
     * One pass of the owner's deletion {@code deletionId}, as that owner: whether it finished now. Never throws: a
     * failure is logged by its type and left for the next pass.
     */
    public boolean run(UUID ownerId, UUID deletionId) {
        if (!running.add(deletionId)) {
            return false;
        }
        try {
            return OwnerContext.callAs(ownerId, () -> pass(deletionId));
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("ownerId", ownerId).addKeyValue("deletionId", deletionId)
                    .addKeyValue("errorType", failure.getClass().getName()).log("library.deletion_deferred");
            return false;
        } finally {
            running.remove(deletionId);
        }
    }

    /** Where a pass stands after it prepared again. */
    private record Prepared(SystemDeletionHandler.Going going, Instant postponedTo, boolean gone) {
    }

    private boolean pass(UUID deletionId) {
        UUID owner = OwnerContext.require();
        Optional<DeletionRow> pending = deletions.find(owner, deletionId).filter(row -> row.doneAt() == null);
        if (pending.isEmpty() || now().isBefore(pending.get().notBefore())) {
            return false;
        }
        DeletionRow row = pending.get();
        Prepared prepared = writing(() -> {
            Instant now = now();
            if (!targetDeleting(row)) {
                // Nothing of the target is left (it goes with the record's end, in one transaction, or, for a
                // campaign, with its system's deletion): end the record.
                deletions.done(owner, row.id(), now);
                return new Prepared(null, null, true);
            }
            SystemDeletionHandler.Going going = goingOf(row);
            Instant notBefore = prepare(going, now);
            if (notBefore.isAfter(now)) {
                deletions.postpone(owner, row.id(), notBefore);
                return new Prepared(going, notBefore, false);
            }
            return new Prepared(going, null, false);
        });
        if (prepared.gone()) {
            return true;
        }
        if (prepared.postponedTo() != null) {
            log.atInfo().addKeyValue("deletionId", row.id()).addKeyValue("notBefore", prepared.postponedTo())
                    .log("library.deletion_postponed");
            later(owner, row.id(), prepared.postponedTo());
            return false;
        }
        for (SystemDeletionHandler handler : handlers()) {
            handler.remove(prepared.going());
        }
        if (DeletionRow.CAMPAIGN.equals(row.kind())) {
            endCampaign(owner, row);
            return true;
        }
        writing(() -> {
            if (!store.deleteDeleting(row.targetId())) {
                throw new IllegalStateException("library.deleting_system_missing");
            }
            deletions.done(owner, row.id(), now());
            return null;
        });
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("systemId", row.targetId())
                .addKeyValue("deletionId", row.id()).addKeyValue("campaigns", prepared.going().campaignIds().size())
                .log("library.system_deleted");
        return true;
    }

    /** Whether the deletion's target is still there, deleting: its system, or (kind {@code campaign}) its campaign. */
    private boolean targetDeleting(DeletionRow row) {
        return DeletionRow.CAMPAIGN.equals(row.kind()) ? store.campaignDeleting(row.targetId())
                : store.deleting(row.targetId());
    }

    /** What goes, collected again: the system and all its campaigns, or (kind {@code campaign}) the campaign alone. */
    private SystemDeletionHandler.Going goingOf(DeletionRow row) {
        return DeletionRow.CAMPAIGN.equals(row.kind()) ? goingOfCampaign(row.targetId())
                : new SystemDeletionHandler.Going(row.targetId(), store.campaignIdsOfDeleting(row.targetId()));
    }

    /**
     * The end of a campaign's deletion, once its handlers removed their parts: the campaign row goes and the record is
     * done, in one transaction. A system's deletion that took the campaign meanwhile removed the row already; the record
     * ends all the same, logged {@code removed=false}.
     */
    private void endCampaign(UUID owner, DeletionRow row) {
        boolean removed = Boolean.TRUE.equals(writing(() -> {
            boolean deleted = store.deleteDeletingCampaign(row.targetId());
            deletions.done(owner, row.id(), now());
            return deleted;
        }));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", row.targetId())
                .addKeyValue("deletionId", row.id()).addKeyValue("removed", removed).log("library.campaign_deleted");
    }

    /** What goes with a campaign: the campaign alone, with no system part. */
    private static SystemDeletionHandler.Going goingOfCampaign(UUID campaignId) {
        return new SystemDeletionHandler.Going(null, List.of(campaignId));
    }

    /** Each handler prepares, in the caller's transaction: the latest claim lapse of what they cancelled, or now. */
    private Instant prepare(SystemDeletionHandler.Going going, Instant now) {
        Instant notBefore = now;
        for (SystemDeletionHandler handler : handlers()) {
            Optional<Instant> lapse = handler.prepare(going);
            if (lapse.isPresent() && lapse.get().isAfter(notBefore)) {
                notBefore = lapse.get().truncatedTo(ChronoUnit.MICROS);
            }
        }
        return notBefore;
    }

    /** The one-shot pass at {@code at}, when a scheduler runs here; otherwise the sweep finds it. */
    private void later(UUID ownerId, UUID deletionId, Instant at) {
        try {
            SystemDeletionSweepSchedule later = schedule.getIfAvailable();
            if (later != null) {
                later.once(() -> run(ownerId, deletionId), at);
            }
        } catch (RuntimeException failure) {
            // Never the GM's failure: the sweep runs every deletion that is due.
            log.atWarn().addKeyValue("deletionId", deletionId).addKeyValue("errorType", failure.getClass().getName())
                    .log("library.deletion_not_scheduled");
        }
    }

    private List<SystemDeletionHandler> handlers() {
        return handlers.orderedStream().toList();
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
}

package io.github.gmshield.jobs.data;

import io.github.gmshield.shared.data.RollbackUndo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@code job} in memory, for tests that run without PostgreSQL. {@link #joined()} undoes its writes when the
 * caller's transaction rolls back (as the real store takes part in it); {@link #separately} runs alone and undoes
 * everything it wrote when its work throws, as its own transaction would.
 */
public class InMemoryJobStorage implements JobStorage {

    public final Map<UUID, JobRow> rows = new ConcurrentHashMap<>();
    /** Runs once before the next locked read: what another transaction commits while the caller waits for the lock. */
    public volatile Runnable beforeNextLockedRead;
    private final Store joined = new Store(true);
    private final Store separate = new Store(false);

    @Override
    public JobStore joined() {
        return joined;
    }

    @Override
    public synchronized <T> T separately(Function<JobStore, T> work) {
        Map<UUID, JobRow> before = new HashMap<>(rows);
        try {
            return work.apply(separate);
        } catch (RuntimeException failure) {
            rows.clear();
            rows.putAll(before);
            throw failure;
        }
    }

    public JobRow row(UUID id) {
        return rows.get(id);
    }

    private final class Store implements JobStore {

        private final boolean undoOnRollback;

        Store(boolean undoOnRollback) {
            this.undoOnRollback = undoOnRollback;
        }

        @Override
        public void insert(JobRow row) {
            synchronized (InMemoryJobStorage.this) {
                if (rows.putIfAbsent(row.id(), row) != null) {
                    throw new IllegalStateException("duplicate key");
                }
            }
            if (undoOnRollback) {
                RollbackUndo.onRollback(() -> rows.remove(row.id()));
            }
        }

        @Override
        public Optional<JobRow> find(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<JobRow> findForUpdate(UUID id) {
            Runnable before = beforeNextLockedRead;
            if (before != null) {
                beforeNextLockedRead = null;
                before.run();
            }
            return find(id);
        }

        @Override
        public boolean replace(JobRow expected, JobRow next) {
            JobRow previous;
            synchronized (InMemoryJobStorage.this) {
                previous = rows.get(expected.id());
                if (previous == null || !previous.state().equals(expected.state()) || previous.attempt() != expected.attempt()
                        || !Objects.equals(previous.claimId(), expected.claimId())) {
                    return false;
                }
                rows.put(next.id(), next);
            }
            if (undoOnRollback) {
                JobRow undo = previous;
                RollbackUndo.onRollback(() -> rows.put(undo.id(), undo));
            }
            return true;
        }

        @Override
        public List<JobRow> waiting(String lane, int limit) {
            return rows.values().stream().filter(row -> row.state().equals("waiting") && row.lane().equals(lane))
                    .sorted(Comparator.comparing(JobRow::createdAt).thenComparing(JobRow::id)).limit(limit).toList();
        }

        @Override
        public List<JobRow> oldestWaitingPerOwner(String lane) {
            return new ArrayList<>(rows.values().stream()
                    .filter(row -> row.state().equals("waiting") && row.lane().equals(lane))
                    .collect(Collectors.toMap(JobRow::ownerId, row -> row, (a, b) ->
                            Comparator.comparing(JobRow::createdAt).thenComparing(JobRow::id).compare(a, b) <= 0 ? a : b))
                    .values());
        }

        @Override
        public Optional<UUID> lastOfferedOwner(String lane) {
            return rows.values().stream().filter(row -> row.lane().equals(lane) && row.offeredAt() != null)
                    .max(Comparator.comparing(JobRow::offeredAt).thenComparing(JobRow::id)).map(JobRow::ownerId);
        }

        @Override
        public boolean busy(String lane) {
            return rows.values().stream().anyMatch(row -> row.lane().equals(lane)
                    && (row.state().equals("offered") || row.state().equals("running")));
        }

        @Override
        public List<JobRow> lapsed(Instant now, int limit) {
            return rows.values().stream().filter(row -> row.state().equals("running") && !row.claimLapsesAt().isAfter(now))
                    .sorted(Comparator.comparing(JobRow::claimLapsesAt)).limit(limit).toList();
        }

        @Override
        public List<JobRow> unclaimed(Instant offeredBefore, int limit) {
            return rows.values().stream().filter(row -> row.state().equals("offered") && !row.offeredAt().isAfter(offeredBefore))
                    .sorted(Comparator.comparing(JobRow::offeredAt)).limit(limit).toList();
        }

        @Override
        public void lockLane(String lane) {
            // separately() already runs one piece of work at a time
        }
    }
}

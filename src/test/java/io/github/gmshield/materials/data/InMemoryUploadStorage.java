package io.github.gmshield.materials.data;

import io.github.gmshield.shared.data.RollbackUndo;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * {@code upload} in memory, for tests that run without PostgreSQL, with the same conditional changes as the SQL.
 * {@link #joined()} undoes its writes when the caller's transaction rolls back; {@link #separately} undoes everything
 * it wrote when its work throws.
 */
public class InMemoryUploadStorage implements UploadStorage {

    public final Map<UUID, UploadRow> rows = new ConcurrentHashMap<>();
    private final Store store = new Store();

    @Override
    public UploadStore joined() {
        return store;
    }

    @Override
    public synchronized <T> T separately(Function<UploadStore, T> work) {
        Map<UUID, UploadRow> before = new HashMap<>(rows);
        try {
            return work.apply(store);
        } catch (RuntimeException failure) {
            rows.clear();
            rows.putAll(before);
            throw failure;
        }
    }

    public UploadRow row(UUID id) {
        return rows.get(id);
    }

    /** Replaces a row as a test needs it (an expired lease, a stop half way through a removal...). */
    public void change(UUID id, UnaryOperator<UploadRow> change) {
        rows.computeIfPresent(id, (key, row) -> change.apply(row));
    }

    private final class Store implements UploadStore {

        /** A set's members in order; an upload outside a set first. */
        private static final Comparator<UploadRow> IN_ORDER = Comparator
                .comparing(UploadRow::position, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(UploadRow::createdAt).thenComparing(UploadRow::id);

        @Override
        public void insert(UploadRow row) {
            synchronized (InMemoryUploadStorage.this) {
                if (rows.putIfAbsent(row.id(), row) != null) {
                    throw new IllegalStateException("unique key taken");
                }
                RollbackUndo.onRollback(() -> rows.remove(row.id()));
            }
        }

        @Override
        public Optional<UploadRow> find(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<UploadRow> openForVersions(UUID owner, Collection<UUID> versionIds) {
            return rows.values().stream().filter(row -> row.ownerId().equals(owner)
                    && versionIds.contains(row.versionId()) && row.state().equals(UploadRow.OPEN)).toList();
        }

        @Override
        public boolean takeLease(UUID id, UUID owner, long offset, UUID token, Instant until, Instant now) {
            return update(id, row -> row.ownerId().equals(owner) && row.state().equals(UploadRow.OPEN)
                            && row.expiresAt().isAfter(now) && row.offset() == offset && leaseFree(row, now),
                    row -> with(row, row.offset(), row.pieceCount(), row.format(), token, until, row.state(), now));
        }

        @Override
        public boolean renewLease(UUID id, UUID token, Instant until, Instant now) {
            return update(id, row -> token.equals(row.busyToken()) && row.state().equals(UploadRow.OPEN),
                    row -> with(row, row.offset(), row.pieceCount(), row.format(), token, until, row.state(), now));
        }

        @Override
        public void releaseLease(UUID id, UUID token, Instant now) {
            update(id, row -> token.equals(row.busyToken()),
                    row -> with(row, row.offset(), row.pieceCount(), row.format(), null, null, row.state(), now));
        }

        @Override
        public boolean advance(UUID id, UUID token, long from, long to, int pieces, String format, Instant now) {
            return update(id, row -> token.equals(row.busyToken()) && row.offset() == from
                            && row.state().equals(UploadRow.OPEN),
                    row -> with(row, to, pieces, format == null ? row.format() : format, null, null, row.state(), now));
        }

        @Override
        public boolean complete(UUID id, UUID token, Instant now) {
            return update(id, row -> token.equals(row.busyToken()) && row.state().equals(UploadRow.OPEN)
                            && row.offset() == row.length(),
                    row -> with(row, row.offset(), row.pieceCount(), row.format(), null, null, UploadRow.COMPLETE, now));
        }

        @Override
        public boolean removeHeld(UUID id, UUID token, Instant now) {
            return update(id, row -> token.equals(row.busyToken()) && row.state().equals(UploadRow.OPEN),
                    row -> with(row, row.offset(), row.pieceCount(), row.format(), null, null, UploadRow.REMOVING, now));
        }

        @Override
        public boolean removeExpired(UUID id, Instant now) {
            return update(id, row -> row.state().equals(UploadRow.OPEN) && !row.expiresAt().isAfter(now)
                            && leaseFree(row, now),
                    row -> with(row, row.offset(), row.pieceCount(), row.format(), null, null, UploadRow.REMOVING, now));
        }

        @Override
        public Optional<UploadRow> completeForVersion(UUID owner, UUID versionId) {
            return completeOfVersion(owner, versionId).stream().findFirst();
        }

        @Override
        public boolean removeComplete(UUID id, Instant now) {
            return update(id, row -> row.state().equals(UploadRow.COMPLETE),
                    row -> with(row, row.offset(), row.pieceCount(), row.format(), null, null, UploadRow.REMOVING, now));
        }

        @Override
        public boolean delete(UUID id) {
            synchronized (InMemoryUploadStorage.this) {
                UploadRow before = rows.remove(id);
                if (before != null) {
                    RollbackUndo.onRollback(() -> rows.put(id, before));
                }
                return before != null;
            }
        }

        @Override
        public boolean deleteRemoving(UUID id) {
            synchronized (InMemoryUploadStorage.this) {
                UploadRow row = rows.get(id);
                return row != null && row.state().equals(UploadRow.REMOVING) && rows.remove(id, row);
            }
        }

        @Override
        public List<UploadRow> expired(Instant now, int limit) {
            return rows.values().stream()
                    .filter(row -> row.state().equals(UploadRow.OPEN) && !row.expiresAt().isAfter(now) && leaseFree(row, now))
                    .sorted(Comparator.comparing(UploadRow::expiresAt).thenComparing(UploadRow::id)).limit(limit).toList();
        }

        @Override
        public List<UploadRow> removing(int limit) {
            return rows.values().stream().filter(row -> row.state().equals(UploadRow.REMOVING))
                    .sorted(Comparator.comparing(UploadRow::updatedAt).thenComparing(UploadRow::id)).limit(limit).toList();
        }

        // --- image sets (Story 1.21)

        @Override
        public List<UploadRow> allOfVersion(UUID owner, UUID versionId) {
            return rows.values().stream().filter(row -> row.ownerId().equals(owner) && row.versionId().equals(versionId))
                    .sorted(IN_ORDER).toList();
        }

        @Override
        public List<UploadRow> ofVersion(UUID owner, UUID versionId) {
            return rows.values().stream().filter(row -> row.ownerId().equals(owner) && row.versionId().equals(versionId)
                    && !row.state().equals(UploadRow.REMOVING)).sorted(IN_ORDER).toList();
        }

        @Override
        public List<UploadRow> membersOf(UUID owner, Collection<UUID> versionIds) {
            return rows.values().stream().filter(row -> row.ownerId().equals(owner)
                            && versionIds.contains(row.versionId()) && row.member()
                            && !row.state().equals(UploadRow.REMOVING))
                    .sorted(Comparator.comparing(UploadRow::versionId).thenComparing(IN_ORDER)).toList();
        }

        @Override
        public List<UploadRow> completeOfVersion(UUID owner, UUID versionId) {
            return rows.values().stream().filter(row -> row.ownerId().equals(owner) && row.versionId().equals(versionId)
                    && row.state().equals(UploadRow.COMPLETE)).sorted(IN_ORDER).toList();
        }

        @Override
        public boolean failHeld(UUID id, UUID token, String code, Instant now) {
            return update(id, row -> token.equals(row.busyToken()) && row.state().equals(UploadRow.OPEN),
                    row -> failed(row, code, now));
        }

        @Override
        public boolean failExpired(UUID id, String code, Instant now) {
            return update(id, row -> row.state().equals(UploadRow.OPEN) && row.member()
                            && !row.expiresAt().isAfter(now) && leaseFree(row, now),
                    row -> failed(row, code, now));
        }

        @Override
        public boolean removeFailed(UUID id, Instant now) {
            return update(id, row -> row.state().equals(UploadRow.FAILED),
                    row -> with(row, row.offset(), row.pieceCount(), row.format(), row.busyToken(), row.busyUntil(),
                            UploadRow.REMOVING, now));
        }

        @Override
        public int closeGap(UUID owner, UUID versionId, int position, Instant now) {
            int moved = 0;
            for (UploadRow row : List.copyOf(rows.values())) {
                if (row.ownerId().equals(owner) && row.versionId().equals(versionId) && row.member()
                        && row.position() > position && !row.state().equals(UploadRow.REMOVING)) {
                    UploadRow before = row;
                    rows.put(row.id(), new UploadRow(row.id(), row.ownerId(), row.versionId(), row.length(),
                            row.offset(), row.pieceCount(), row.format(), row.expiresAt(), row.busyToken(),
                            row.busyUntil(), row.state(), row.createdAt(), now, row.position() - 1, row.name(),
                            row.failureCode()));
                    RollbackUndo.onRollback(() -> rows.put(before.id(), before));
                    moved++;
                }
            }
            return moved;
        }

        @Override
        public List<UploadRow> abandonedSets(Instant now, int limit) {
            Map<List<UUID>, List<UploadRow>> sets = rows.values().stream().filter(UploadRow::member)
                    .filter(row -> !row.state().equals(UploadRow.REMOVING))
                    .collect(java.util.stream.Collectors.groupingBy(row -> List.of(row.ownerId(), row.versionId())));
            return sets.values().stream()
                    .filter(members -> members.stream().noneMatch(row -> row.state().equals(UploadRow.OPEN)
                            || (row.state().equals(UploadRow.FAILED) && row.expiresAt().isAfter(now))))
                    .filter(members -> members.stream().anyMatch(row -> row.state().equals(UploadRow.FAILED)))
                    .map(members -> members.stream().filter(row -> row.state().equals(UploadRow.FAILED))
                            .min(Comparator.comparing(UploadRow::id)).orElseThrow())
                    .sorted(Comparator.comparing(UploadRow::expiresAt).thenComparing(UploadRow::id)).limit(limit)
                    .toList();
        }

        private boolean update(UUID id, java.util.function.Predicate<UploadRow> when, UnaryOperator<UploadRow> change) {
            synchronized (InMemoryUploadStorage.this) {
                UploadRow before = rows.get(id);
                if (before == null || !when.test(before)) {
                    return false;
                }
                rows.put(id, change.apply(before));
                RollbackUndo.onRollback(() -> rows.put(id, before));
                return true;
            }
        }

        private static boolean leaseFree(UploadRow row, Instant now) {
            return row.busyUntil() == null || !row.busyUntil().isAfter(now);
        }

        private static UploadRow with(UploadRow row, long offset, int pieces, String format, UUID token, Instant until,
                                      String state, Instant now) {
            return new UploadRow(row.id(), row.ownerId(), row.versionId(), row.length(), offset, pieces, format,
                    row.expiresAt(), token, until, state, row.createdAt(), now, row.position(), row.name(),
                    row.failureCode());
        }

        private static UploadRow failed(UploadRow row, String code, Instant now) {
            return new UploadRow(row.id(), row.ownerId(), row.versionId(), row.length(), row.offset(),
                    row.pieceCount(), row.format(), row.expiresAt(), null, null, UploadRow.FAILED, row.createdAt(), now,
                    row.position(), row.name(), code);
        }
    }
}

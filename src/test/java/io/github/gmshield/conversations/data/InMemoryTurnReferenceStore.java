package io.github.gmshield.conversations.data;

import io.github.gmshield.shared.data.RollbackUndo;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@code turn_reference} in memory, for tests that run without PostgreSQL. Like Hibernate's {@code @TenantId}, it sees
 * the rows of the {@link OwnerContext}'s owner only; like the table, a (turn, number) is unique. A write undoes itself
 * when its transaction rolls back.
 */
public class InMemoryTurnReferenceStore implements TurnReferenceStore {

    public final Map<UUID, TurnReferenceRow> rows = new ConcurrentHashMap<>();

    @Override
    public synchronized void insertReferences(List<TurnReferenceRow> references) {
        UUID owner = OwnerContext.require();
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("no transaction");
        }
        for (TurnReferenceRow row : references) {
            boolean repeated = rows.values().stream().anyMatch(other -> other.ownerId().equals(row.ownerId())
                    && other.turnId().equals(row.turnId()) && other.number() == row.number());
            if (!owner.equals(row.ownerId()) || repeated || row.number() < 1 || row.page() < 1
                    || row.passageIds().isEmpty() || rows.putIfAbsent(row.id(), row) != null) {
                throw new IllegalStateException("owner, key or check refused");
            }
            RollbackUndo.onRollback(() -> rows.remove(row.id()));
        }
    }

    @Override
    public List<TurnReferenceRow> references(UUID turnId) {
        UUID owner = OwnerContext.require();
        return rows.values().stream().filter(row -> owner.equals(row.ownerId()) && row.turnId().equals(turnId))
                .sorted(Comparator.comparingInt(TurnReferenceRow::number)).toList();
    }

    /** Every owner's references of a turn, by number (tests). */
    public List<TurnReferenceRow> anyOfTurn(UUID turnId) {
        return rows.values().stream().filter(row -> row.turnId().equals(turnId))
                .sorted(Comparator.comparingInt(TurnReferenceRow::number)).toList();
    }

    @Override
    public synchronized int deleteReferences(UUID turnId) {
        UUID owner = OwnerContext.require();
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("no transaction");
        }
        List<TurnReferenceRow> found = references(turnId);
        for (TurnReferenceRow row : found) {
            if (owner.equals(row.ownerId())) {
                rows.remove(row.id());
                RollbackUndo.onRollback(() -> rows.put(row.id(), row));
            }
        }
        return found.size();
    }
}

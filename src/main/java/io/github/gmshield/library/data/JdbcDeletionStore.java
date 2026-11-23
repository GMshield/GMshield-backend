package io.github.gmshield.library.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link DeletionStore} through {@link JdbcClient}, over the application's data source: inside the owner's (JPA)
 * transaction a statement takes part in it, as {@code jobs.data.JdbcJobStorage.joined()} does; outside one (the
 * sweep's list) it runs on its own. The client is looked up on first use, so the unit tests, which start without a
 * database, can still create the application.
 */
@Repository
class JdbcDeletionStore implements DeletionStore {

    private static final String COLUMNS = "select id, owner_id, kind, target_id, not_before, created_at, done_at"
            + " from deletion ";

    private final ObjectProvider<JdbcClient> jdbc;

    JdbcDeletionStore(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(DeletionRow row) {
        jdbc.getObject().sql("insert into deletion (id, owner_id, kind, target_id, not_before, created_at, done_at)"
                        + " values (:id, :owner, :kind, :target, :notBefore, :createdAt, :doneAt)")
                .param("id", row.id()).param("owner", row.ownerId()).param("kind", row.kind())
                .param("target", row.targetId())
                .param("notBefore", time(row.notBefore()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("createdAt", time(row.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("doneAt", time(row.doneAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    @Override
    public boolean insertIfAbsent(DeletionRow row) {
        return jdbc.getObject().sql("insert into deletion (id, owner_id, kind, target_id, not_before, created_at,"
                        + " done_at) values (:id, :owner, :kind, :target, :notBefore, :createdAt, :doneAt)"
                        + " on conflict (owner_id, kind, target_id) do nothing")
                .param("id", row.id()).param("owner", row.ownerId()).param("kind", row.kind())
                .param("target", row.targetId())
                .param("notBefore", time(row.notBefore()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("createdAt", time(row.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("doneAt", time(row.doneAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .update() == 1;
    }

    @Override
    public Optional<DeletionRow> find(UUID ownerId, UUID id) {
        return jdbc.getObject().sql(COLUMNS + "where id = :id and owner_id = :owner").param("id", id)
                .param("owner", ownerId).query(JdbcDeletionStore::row).optional();
    }

    @Override
    public Optional<DeletionRow> findTarget(UUID ownerId, String kind, UUID targetId) {
        return jdbc.getObject().sql(COLUMNS + "where owner_id = :owner and kind = :kind and target_id = :target")
                .param("owner", ownerId).param("kind", kind).param("target", targetId).query(JdbcDeletionStore::row)
                .optional();
    }

    @Override
    public boolean postpone(UUID ownerId, UUID id, Instant notBefore) {
        return jdbc.getObject().sql("update deletion set not_before = :notBefore where id = :id and owner_id = :owner"
                        + " and done_at is null and not_before < :notBefore")
                .param("notBefore", time(notBefore), Types.TIMESTAMP_WITH_TIMEZONE).param("id", id)
                .param("owner", ownerId).update() == 1;
    }

    @Override
    public boolean done(UUID ownerId, UUID id, Instant at) {
        return jdbc.getObject().sql("update deletion set done_at = :at where id = :id and owner_id = :owner"
                        + " and done_at is null")
                .param("at", time(at), Types.TIMESTAMP_WITH_TIMEZONE).param("id", id).param("owner", ownerId)
                .update() == 1;
    }

    @Override
    public List<DeletionRow> due(Instant now, int limit) {
        return jdbc.getObject().sql(COLUMNS + "where done_at is null and not_before <= :now"
                        + " order by not_before, id limit :limit")
                .param("now", time(now), Types.TIMESTAMP_WITH_TIMEZONE).param("limit", limit)
                .query(JdbcDeletionStore::row).list();
    }

    private static DeletionRow row(ResultSet rs, int rowNumber) throws SQLException {
        return new DeletionRow(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
                rs.getString("kind"), rs.getObject("target_id", UUID.class), instant(rs, "not_before"),
                instant(rs, "created_at"), instant(rs, "done_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime time(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}

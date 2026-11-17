package io.github.gmshield.materials.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code upload} through {@link JdbcClient}. The client and the data source are looked up on first use, so the unit
 * tests, which start without a database, can still create the application.
 *
 * <p>{@link #joined()} uses the application's data source, so it takes part in the caller's (JPA) transaction.
 * {@link #separately} follows {@code jobs.data.JdbcJobStorage}: its transaction manager works on a wrapper of the
 * pool, which Spring keys apart from the pool itself, so it always opens a connection and a transaction of its own.
 */
@Repository
class JdbcUploadStorage implements UploadStorage {

    private final Sql joined;
    private final ObjectProvider<DataSource> dataSource;
    private volatile Separate separate;

    JdbcUploadStorage(ObjectProvider<JdbcClient> jdbc, ObjectProvider<DataSource> dataSource) {
        this.joined = new Sql(jdbc::getObject);
        this.dataSource = dataSource;
    }

    /** A store and a {@code REQUIRES_NEW} template on a connection of their own. */
    private record Separate(Sql store, TransactionTemplate requiresNew) {
    }

    @Override
    public UploadStore joined() {
        return joined;
    }

    @Override
    public <T> T separately(Function<UploadStore, T> work) {
        Separate own = separate();
        return own.requiresNew().execute(status -> work.apply(own.store()));
    }

    private Separate separate() {
        Separate current = separate;
        if (current == null) {
            synchronized (this) {
                current = separate;
                if (current == null) {
                    DataSource own = new DelegatingDataSource(dataSource.getObject());
                    TransactionTemplate requiresNew = new TransactionTemplate(new DataSourceTransactionManager(own));
                    requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    JdbcClient client = JdbcClient.create(own);
                    current = new Separate(new Sql(() -> client), requiresNew);
                    separate = current;
                }
            }
        }
        return current;
    }

    /** The statements, over one client. */
    static final class Sql implements UploadStore {

        private static final String COLUMNS = "select id, owner_id, version_id, length, upload_offset, piece_count, format,"
                + " expires_at, busy_token, busy_until, state, created_at, updated_at from upload ";
        /** A lease nobody holds, or one that lapsed. */
        private static final String LEASE_FREE = " (busy_until is null or busy_until <= :now) ";

        private final Supplier<JdbcClient> jdbc;

        Sql(Supplier<JdbcClient> jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public void insert(UploadRow row) {
            jdbc.get().sql("insert into upload (id, owner_id, version_id, length, upload_offset, piece_count, format,"
                            + " expires_at, busy_token, busy_until, state, created_at, updated_at) values (:id, :owner,"
                            + " :version, :length, :offset, :pieces, :format, :expiresAt, :token, :until, :state,"
                            + " :createdAt, :updatedAt)")
                    .param("id", row.id())
                    .param("owner", row.ownerId())
                    .param("version", row.versionId())
                    .param("length", row.length())
                    .param("offset", row.offset())
                    .param("pieces", row.pieceCount())
                    .param("format", row.format(), Types.VARCHAR)
                    .param("expiresAt", time(row.expiresAt()))
                    .param("token", row.busyToken(), Types.OTHER)
                    .param("until", time(row.busyUntil()), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("state", row.state())
                    .param("createdAt", time(row.createdAt()))
                    .param("updatedAt", time(row.updatedAt()))
                    .update();
        }

        @Override
        public Optional<UploadRow> find(UUID id) {
            return jdbc.get().sql(COLUMNS + "where id = :id").param("id", id).query(Sql::row).optional();
        }

        @Override
        public List<UploadRow> openForVersions(UUID owner, Collection<UUID> versionIds) {
            if (versionIds.isEmpty()) {
                return List.of();
            }
            return jdbc.get().sql(COLUMNS + "where owner_id = :owner and version_id in (:versions) and state = 'open'")
                    .param("owner", owner).param("versions", List.copyOf(versionIds)).query(Sql::row).list();
        }

        @Override
        public boolean takeLease(UUID id, UUID owner, long offset, UUID token, Instant until, Instant now) {
            return jdbc.get().sql("update upload set busy_token = :token, busy_until = :until, updated_at = :now"
                            + " where id = :id and owner_id = :owner and state = 'open' and expires_at > :now"
                            + " and upload_offset = :offset and" + LEASE_FREE)
                    .param("token", token).param("until", time(until)).param("now", time(now))
                    .param("id", id).param("owner", owner).param("offset", offset)
                    .update() == 1;
        }

        @Override
        public boolean renewLease(UUID id, UUID token, Instant until, Instant now) {
            return jdbc.get().sql("update upload set busy_until = :until, updated_at = :now"
                            + " where id = :id and busy_token = :token and state = 'open'")
                    .param("until", time(until)).param("now", time(now)).param("id", id).param("token", token)
                    .update() == 1;
        }

        @Override
        public void releaseLease(UUID id, UUID token, Instant now) {
            jdbc.get().sql("update upload set busy_token = null, busy_until = null, updated_at = :now"
                            + " where id = :id and busy_token = :token")
                    .param("now", time(now)).param("id", id).param("token", token)
                    .update();
        }

        @Override
        public boolean advance(UUID id, UUID token, long from, long to, int pieces, String format, Instant now) {
            return jdbc.get().sql("update upload set upload_offset = :to, piece_count = :pieces,"
                            + " format = coalesce(:format, format), busy_token = null, busy_until = null, updated_at = :now"
                            + " where id = :id and busy_token = :token and upload_offset = :from and state = 'open'")
                    .param("to", to).param("pieces", pieces).param("format", format, Types.VARCHAR)
                    .param("now", time(now)).param("id", id).param("token", token).param("from", from)
                    .update() == 1;
        }

        @Override
        public boolean complete(UUID id, UUID token, Instant now) {
            return jdbc.get().sql("update upload set state = 'complete', busy_token = null, busy_until = null,"
                            + " updated_at = :now where id = :id and busy_token = :token and state = 'open'"
                            + " and upload_offset = length")
                    .param("now", time(now)).param("id", id).param("token", token)
                    .update() == 1;
        }

        @Override
        public boolean removeHeld(UUID id, UUID token, Instant now) {
            return jdbc.get().sql("update upload set state = 'removing', busy_token = null, busy_until = null,"
                            + " updated_at = :now where id = :id and busy_token = :token and state = 'open'")
                    .param("now", time(now)).param("id", id).param("token", token)
                    .update() == 1;
        }

        @Override
        public boolean removeExpired(UUID id, Instant now) {
            return jdbc.get().sql("update upload set state = 'removing', busy_token = null, busy_until = null,"
                            + " updated_at = :now where id = :id and state = 'open' and expires_at <= :now and" + LEASE_FREE)
                    .param("now", time(now)).param("id", id)
                    .update() == 1;
        }

        @Override
        public Optional<UploadRow> completeForVersion(UUID owner, UUID versionId) {
            return jdbc.get().sql(COLUMNS + "where owner_id = :owner and version_id = :version and state = 'complete'"
                            + " order by created_at, id limit 1")
                    .param("owner", owner).param("version", versionId).query(Sql::row).optional();
        }

        @Override
        public boolean removeComplete(UUID id, Instant now) {
            return jdbc.get().sql("update upload set state = 'removing', busy_token = null, busy_until = null,"
                            + " updated_at = :now where id = :id and state = 'complete'")
                    .param("now", time(now)).param("id", id)
                    .update() == 1;
        }

        @Override
        public boolean delete(UUID id) {
            return jdbc.get().sql("delete from upload where id = :id").param("id", id).update() == 1;
        }

        @Override
        public boolean deleteRemoving(UUID id) {
            return jdbc.get().sql("delete from upload where id = :id and state = 'removing'").param("id", id).update() == 1;
        }

        @Override
        public List<UploadRow> expired(Instant now, int limit) {
            return jdbc.get().sql(COLUMNS + "where state = 'open' and expires_at <= :now and" + LEASE_FREE
                            + "order by expires_at, id limit :limit")
                    .param("now", time(now)).param("limit", limit).query(Sql::row).list();
        }

        @Override
        public List<UploadRow> removing(int limit) {
            return jdbc.get().sql(COLUMNS + "where state = 'removing' order by updated_at, id limit :limit")
                    .param("limit", limit).query(Sql::row).list();
        }

        private static UploadRow row(ResultSet rs, int n) throws SQLException {
            return new UploadRow(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
                    rs.getObject("version_id", UUID.class), rs.getLong("length"), rs.getLong("upload_offset"),
                    rs.getInt("piece_count"), rs.getString("format"), instant(rs, "expires_at"),
                    rs.getObject("busy_token", UUID.class), instant(rs, "busy_until"), rs.getString("state"),
                    instant(rs, "created_at"), instant(rs, "updated_at"));
        }

        private static Instant instant(ResultSet rs, String column) throws SQLException {
            OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
            return value == null ? null : value.toInstant();
        }

        private static OffsetDateTime time(Instant instant) {
            return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
        }
    }
}

package io.github.gmshield.jobs.data;

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
 * {@code job} through {@link JdbcClient}. The client and the data source are looked up on first use, so the unit
 * tests, which start without a database, can still create the application.
 *
 * <p>{@link #joined()} uses the application's data source, so it takes part in the caller's (JPA) transaction.
 * {@link #separately} follows {@code vault.data.JdbcDataKeyStore}: its transaction manager works on a wrapper of
 * the pool, which Spring keys apart from the pool itself, so it always opens a connection and a transaction of its
 * own ({@code REQUIRES_NEW}) and never takes over the caller's, not even inside a transaction's after-commit step.
 */
@Repository
class JdbcJobStorage implements JobStorage {

    private final Sql joined;
    private final ObjectProvider<DataSource> dataSource;
    private volatile Separate separate;

    JdbcJobStorage(ObjectProvider<JdbcClient> jdbc, ObjectProvider<DataSource> dataSource) {
        this.joined = new Sql(jdbc::getObject);
        this.dataSource = dataSource;
    }

    /** A store and a {@code REQUIRES_NEW} template on a connection of their own. */
    private record Separate(Sql store, TransactionTemplate requiresNew) {
    }

    @Override
    public JobStore joined() {
        return joined;
    }

    @Override
    public <T> T separately(Function<JobStore, T> work) {
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
    static final class Sql implements JobStore {

        private static final String COLUMNS = "select id, owner_id, kind, lane, state, attempt, trace_id, offered_at,"
                + " claim_id, grant_hash, claim_renew_at, claim_lapses_at, outcome_code, created_at, updated_at,"
                + " model_provider, model_id, model_tier from job ";
        /** Namespace of the lane locks ({@code pg_advisory_xact_lock(int, int)}): the story's number. */
        private static final int LANE_LOCKS = 1012;

        private final Supplier<JdbcClient> jdbc;

        Sql(Supplier<JdbcClient> jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public void insert(JobRow row) {
            jdbc.get().sql("insert into job (id, owner_id, kind, lane, state, attempt, trace_id, offered_at, claim_id,"
                            + " grant_hash, claim_renew_at, claim_lapses_at, outcome_code, created_at, updated_at,"
                            + " model_provider, model_id, model_tier)"
                            + " values (:id, :owner, :kind, :lane, :state, :attempt, :trace, :offeredAt, :claim, :grant,"
                            + " :renewAt, :lapsesAt, :code, :createdAt, :updatedAt, :modelProvider, :modelId, :modelTier)")
                    .param("id", row.id())
                    .param("owner", row.ownerId())
                    .param("kind", row.kind())
                    .param("lane", row.lane())
                    .param("state", row.state())
                    .param("attempt", row.attempt())
                    .param("trace", row.traceId())
                    .param("offeredAt", time(row.offeredAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("claim", row.claimId(), Types.OTHER)
                    .param("grant", row.grantHash(), Types.BINARY)
                    .param("renewAt", time(row.claimRenewAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("lapsesAt", time(row.claimLapsesAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("code", row.outcomeCode(), Types.VARCHAR)
                    .param("createdAt", time(row.createdAt()))
                    .param("updatedAt", time(row.updatedAt()))
                    .param("modelProvider", row.modelProvider(), Types.VARCHAR)
                    .param("modelId", row.modelId(), Types.VARCHAR)
                    .param("modelTier", row.modelTier(), Types.VARCHAR)
                    .update();
        }

        @Override
        public Optional<JobRow> find(UUID id) {
            return jdbc.get().sql(COLUMNS + "where id = :id").param("id", id).query(Sql::row).optional();
        }

        @Override
        public Optional<JobRow> findForUpdate(UUID id) {
            return jdbc.get().sql(COLUMNS + "where id = :id for update").param("id", id).query(Sql::row).optional();
        }

        @Override
        public boolean replace(JobRow expected, JobRow next) {
            if (!expected.id().equals(next.id())) {
                throw new IllegalArgumentException("a replacement is the same job");
            }
            return jdbc.get().sql("update job set state = :state, attempt = :attempt, offered_at = :offeredAt,"
                            + " claim_id = :claim, grant_hash = :grant, claim_renew_at = :renewAt,"
                            + " claim_lapses_at = :lapsesAt, outcome_code = :code, updated_at = :updatedAt,"
                            + " model_provider = :modelProvider, model_id = :modelId, model_tier = :modelTier"
                            + " where id = :id and state = :expectedState and attempt = :expectedAttempt"
                            + " and claim_id is not distinct from :expectedClaim")
                    .param("state", next.state())
                    .param("attempt", next.attempt())
                    .param("offeredAt", time(next.offeredAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("claim", next.claimId(), Types.OTHER)
                    .param("grant", next.grantHash(), Types.BINARY)
                    .param("renewAt", time(next.claimRenewAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("lapsesAt", time(next.claimLapsesAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("code", next.outcomeCode(), Types.VARCHAR)
                    .param("updatedAt", time(next.updatedAt()))
                    .param("modelProvider", next.modelProvider(), Types.VARCHAR)
                    .param("modelId", next.modelId(), Types.VARCHAR)
                    .param("modelTier", next.modelTier(), Types.VARCHAR)
                    .param("id", expected.id())
                    .param("expectedState", expected.state())
                    .param("expectedAttempt", expected.attempt())
                    .param("expectedClaim", expected.claimId(), Types.OTHER)
                    .update() == 1;
        }

        @Override
        public List<JobRow> waiting(String lane, int limit) {
            return jdbc.get().sql(COLUMNS + "where state = 'waiting' and lane = :lane order by created_at, id limit :limit")
                    .param("lane", lane).param("limit", limit).query(Sql::row).list();
        }

        @Override
        public List<JobRow> oldestWaitingPerOwner(String lane) {
            return jdbc.get().sql("select distinct on (owner_id) id, owner_id, kind, lane, state, attempt, trace_id,"
                            + " offered_at, claim_id, grant_hash, claim_renew_at, claim_lapses_at, outcome_code, created_at,"
                            + " updated_at, model_provider, model_id, model_tier from job where state = 'waiting' and lane = :lane order by owner_id, created_at, id")
                    .param("lane", lane).query(Sql::row).list();
        }

        @Override
        public Optional<UUID> lastOfferedOwner(String lane) {
            return jdbc.get().sql("select owner_id from job where lane = :lane and offered_at is not null"
                            + " order by offered_at desc, id desc limit 1")
                    .param("lane", lane).query((rs, n) -> rs.getObject(1, UUID.class)).optional();
        }

        @Override
        public List<UUID> inProgress(Collection<UUID> ids, UUID owner) {
            if (ids.isEmpty()) {
                return List.of();
            }
            return jdbc.get().sql("select id from job where owner_id = :owner and state in ('offered', 'running')"
                            + " and id in (:ids)")
                    .param("owner", owner).param("ids", List.copyOf(ids)).query(UUID.class).list();
        }

        @Override
        public List<UUID> unended(Collection<UUID> ids, UUID owner) {
            if (ids.isEmpty()) {
                return List.of();
            }
            return jdbc.get().sql("select id from job where owner_id = :owner"
                            + " and state in ('waiting', 'offered', 'running') and id in (:ids)")
                    .param("owner", owner).param("ids", List.copyOf(ids)).query(UUID.class).list();
        }

        @Override
        public boolean busy(String lane) {
            // Boolean.TRUE.equals never unboxes: an absent or null answer reads as "not busy" (java:S2259).
            return Boolean.TRUE.equals(jdbc.get()
                    .sql("select exists (select 1 from job where lane = :lane and state in ('offered', 'running'))")
                    .param("lane", lane).query(Boolean.class).optional().orElse(null));
        }

        @Override
        public List<JobRow> lapsed(Instant now, int limit) {
            return jdbc.get().sql(COLUMNS + "where state = 'running' and claim_lapses_at <= :now"
                            + " order by claim_lapses_at, id limit :limit")
                    .param("now", time(now)).param("limit", limit).query(Sql::row).list();
        }

        @Override
        public List<JobRow> unclaimed(Instant offeredBefore, int limit) {
            return jdbc.get().sql(COLUMNS + "where state = 'offered' and offered_at <= :before"
                            + " order by offered_at, id limit :limit")
                    .param("before", time(offeredBefore)).param("limit", limit).query(Sql::row).list();
        }

        @Override
        public void lockLane(String lane) {
            jdbc.get().sql("select pg_advisory_xact_lock(:space, :lane)")
                    .param("space", LANE_LOCKS).param("lane", lane.hashCode())
                    .query((rs, n) -> 1).list();
        }

        private static final String OUTPUT_COLUMNS = "select o.id, o.job_id, o.owner_id, o.claim_id, o.state, o.size_bytes,"
                + " o.piece_count, o.created_at, o.updated_at from job_output o ";

        @Override
        public void insertOutput(JobOutputRow row) {
            jdbc.get().sql("insert into job_output (id, job_id, owner_id, claim_id, state, size_bytes, piece_count,"
                            + " created_at, updated_at) values (:id, :job, :owner, :claim, :state, :size, :pieces,"
                            + " :createdAt, :updatedAt)")
                    .param("id", row.id())
                    .param("job", row.jobId())
                    .param("owner", row.ownerId())
                    .param("claim", row.claimId())
                    .param("state", row.state())
                    .param("size", row.sizeBytes())
                    .param("pieces", row.pieceCount())
                    .param("createdAt", time(row.createdAt()))
                    .param("updatedAt", time(row.updatedAt()))
                    .update();
        }

        @Override
        public Optional<JobOutputRow> findOutput(UUID id) {
            return jdbc.get().sql(OUTPUT_COLUMNS + "where o.id = :id").param("id", id).query(Sql::output).optional();
        }

        @Override
        public boolean finishOutput(UUID id, long sizeBytes, int pieceCount, Instant now) {
            return jdbc.get().sql("update job_output set state = 'stored', size_bytes = :size, piece_count = :pieces,"
                            + " updated_at = :now where id = :id and state = 'writing'")
                    .param("size", sizeBytes).param("pieces", pieceCount).param("now", time(now)).param("id", id)
                    .update() == 1;
        }

        @Override
        public long claimOutputBytes(UUID jobId, UUID claimId) {
            Long total = jdbc.get().sql("select coalesce(sum(size_bytes), 0) from job_output"
                            + " where job_id = :job and claim_id = :claim and state = 'stored'")
                    .param("job", jobId).param("claim", claimId).query(Long.class).optional().orElse(0L);
            return total == null ? 0 : total;
        }

        @Override
        public boolean deleteOutput(UUID id) {
            return jdbc.get().sql("delete from job_output where id = :id").param("id", id).update() == 1;
        }

        @Override
        public boolean adoptOutput(UUID id, UUID jobId) {
            return jdbc.get().sql("delete from job_output where id = :id and job_id = :job and state = 'stored'")
                    .param("id", id).param("job", jobId).update() == 1;
        }

        @Override
        public List<JobOutputRow> orphanOutputs(Instant writingEndedBefore, int limit) {
            return jdbc.get().sql(OUTPUT_COLUMNS + "join job j on j.id = o.job_id"
                            + " where j.state in ('done', 'failed', 'cancelled')"
                            + " and (o.state = 'stored' or j.updated_at <= :before)"
                            + " order by o.created_at, o.id limit :limit")
                    .param("before", time(writingEndedBefore)).param("limit", limit).query(Sql::output).list();
        }

        private static JobOutputRow output(ResultSet rs, int n) throws SQLException {
            return new JobOutputRow(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
                    rs.getObject("owner_id", UUID.class), rs.getObject("claim_id", UUID.class), rs.getString("state"),
                    rs.getLong("size_bytes"), rs.getInt("piece_count"), instant(rs, "created_at"),
                    instant(rs, "updated_at"));
        }

        private static JobRow row(ResultSet rs, int n) throws SQLException {
            return new JobRow(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class), rs.getString("kind"),
                    rs.getString("lane"), rs.getString("state"), rs.getInt("attempt"), rs.getString("trace_id"),
                    instant(rs, "offered_at"), rs.getObject("claim_id", UUID.class), rs.getBytes("grant_hash"),
                    instant(rs, "claim_renew_at"), instant(rs, "claim_lapses_at"), rs.getString("outcome_code"),
                    instant(rs, "created_at"), instant(rs, "updated_at"), rs.getString("model_provider"),
                    rs.getString("model_id"), rs.getString("model_tier"));
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

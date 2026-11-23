package io.github.gmshield;

import io.github.gmshield.jobs.data.JobStream;
import io.github.gmshield.jobs.service.Lane;
import io.github.gmshield.vault.service.Vault;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The integration audit's stores: PostgreSQL read with SQL, and the work streams read from Redis ({@code XRANGE}). The
 * database is shared with the other integration tests, so every read of rows names the audit's own: by its owners, by
 * an id it holds, or by the account's keyed Google fingerprint. The one write ends the ingestion lane's open jobs,
 * whoever left them, as {@code IngestionIntegrationTest} does.
 *
 * <p>What it covers, and no more: the dump holds the tables with an {@code owner_id} column (not {@code account} or
 * {@code audit_entry}, which have none); {@link #offered} reads each lane's whole stream, other tests' messages
 * included, and compares it before and after. A JDBC store's {@code owner_id = :owner} filter is only proved where an
 * audited route reaches that store before any {@code @TenantId} JPA lookup has refused the id; where the JPA lookup
 * comes first ({@code JdbcDeletionStore}'s reads, for one), dropping the JDBC filter changes no answer and no row.
 */
final class PostgresCrossOwnerAuditStores implements CrossOwnerAuditStores {

    /** One ingestion job at a time: an open one, of another test or an accepted version's index change, holds it. */
    private static final String END_INGESTION = "update job set state = 'cancelled', grant_hash = null,"
            + " claim_renew_at = null, claim_lapses_at = null"
            + " where lane = ? and state in ('waiting', 'offered', 'running')";
    /** Every table of the schema with an {@code owner_id} column: a new one is dumped without editing the audit. */
    private static final String OWNED_TABLES = """
            select c.table_name from information_schema.columns c
            join information_schema.tables t on t.table_schema = c.table_schema and t.table_name = c.table_name
            where c.table_schema = current_schema() and c.column_name = 'owner_id' and t.table_type = 'BASE TABLE'
            order by c.table_name""";
    private static final String PRIMARY_KEY = """
            select k.column_name from information_schema.table_constraints c
            join information_schema.key_column_usage k on k.constraint_schema = c.constraint_schema
                and k.constraint_name = c.constraint_name and k.table_name = c.table_name
            where c.table_schema = current_schema() and c.table_name = ? and c.constraint_type = 'PRIMARY KEY'
            order by k.ordinal_position""";

    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final Vault vault;

    PostgresCrossOwnerAuditStores(JdbcClient jdbc, StringRedisTemplate redis, Vault vault) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.vault = vault;
    }

    /**
     * Ends the ingestion lane's open jobs only. The other lanes, the streams and the clock are left as they are: the
     * database and Redis are shared with the other classes (deleting a stream would drop its consumer group), the
     * clock is real, and only the ingestion lane offers one job at a time.
     */
    @Override
    public void start() {
        endIngestion();
    }

    @Override
    public void endIngestion() {
        jdbc.sql(END_INGESTION).param(Lane.INGESTION.code()).update();
    }

    /** By the keyed fingerprint of the Google id, as the application stores it: not through the API under audit. */
    @Override
    public UUID account(String sub) {
        return jdbc.sql("select id from account where google_id_fingerprint = ?")
                .param(vault.fingerprint("account", "google_id", sub)).query(UUID.class).single();
    }

    @Override
    public String versionState(UUID versionId) {
        return jdbc.sql("select state from material_version where id = ?").param(versionId).query(String.class)
                .single();
    }

    @Override
    public UUID passage(UUID versionId) {
        return jdbc.sql("select id from passage where version_id = ? order by ordinal limit 1").param(versionId)
                .query(UUID.class).single();
    }

    @Override
    public UUID turnJob(UUID turnId) {
        return jdbc.sql("select job_id from turn where id = ?").param(turnId).query(UUID.class).single();
    }

    @Override
    public Set<UUID> jobIds(UUID ownerId) {
        return jdbc.sql("select id from job where owner_id = ?").param(ownerId).query(UUID.class).set();
    }

    @Override
    public List<Step> offeredSteps(UUID versionId) {
        return jdbc.sql("select s.job_id, s.stage, s.input_file_id from ingestion_step s join job j on j.id = s.job_id"
                        + " where s.version_id = ? and j.state = 'offered'").param(versionId)
                .query((row, number) -> new Step(row.getObject("job_id", UUID.class), row.getString("stage"),
                        row.getObject("input_file_id", UUID.class))).list();
    }

    /**
     * Every row of {@code owners} in every table with an {@code owner_id} column, by table name and primary key (the
     * key's one value, or the list of its values), each row written as its columns and their values (bytes in hex).
     */
    @Override
    public Map<String, Map<Object, Object>> tables(Collection<UUID> owners) {
        Map<String, Map<Object, Object>> tables = new LinkedHashMap<>();
        for (String table : jdbc.sql(OWNED_TABLES).query(String.class).list()) {
            List<String> key = jdbc.sql(PRIMARY_KEY).param(table).query(String.class).list();
            String order = key.isEmpty() ? "1" : key.stream().map(PostgresCrossOwnerAuditStores::quoted)
                    .collect(Collectors.joining(", "));
            Map<Object, Object> rows = new LinkedHashMap<>();
            for (Map<String, Object> row : jdbc.sql("select * from " + quoted(table)
                    + " where owner_id in (:owners) order by " + order).param("owners", List.copyOf(owners))
                    .query().listOfRows()) {
                String text = text(row);
                rows.put(key.isEmpty() ? text : key.size() == 1 ? row.get(key.getFirst())
                        : key.stream().map(row::get).toList(), text);
            }
            tables.put(table, rows);
        }
        return tables;
    }

    @Override
    public Map<String, List<String>> held() {
        return Map.of("system", List.of("system"), "campaign", List.of("campaign"),
                "material", List.of("material", "imageSetMaterial"), "material_version", List.of("version",
                        "superseded", "imageSet"), "passage", List.of("passage"), "upload", List.of("upload"),
                "conversation", List.of("conversation", "rulesConversation"), "turn", List.of("turn",
                        "dismissedTurn", "waitingTurn"), "search", List.of("search"));
    }

    /** Each lane's whole stream, its messages' job ids in order; a message without a job id is left out. */
    @Override
    public Map<String, List<UUID>> offered() {
        Map<String, List<UUID>> offered = new LinkedHashMap<>();
        for (Lane lane : Lane.values()) {
            List<UUID> jobs = new ArrayList<>();
            var messages = redis.opsForStream().range(lane.stream(), Range.unbounded());
            if (messages != null) {
                messages.stream().map(message -> message.getValue().get(JobStream.FIELD)).filter(Objects::nonNull)
                        .forEach(job -> jobs.add(UUID.fromString(job.toString())));
            }
            offered.put(lane.stream(), jobs);
        }
        return offered;
    }

    @Override
    public List<Job> jobs(Collection<UUID> owners) {
        return jdbc.sql("select id, owner_id, kind from job where owner_id in (:owners)")
                .param("owners", List.copyOf(owners))
                .query((row, number) -> new Job(row.getObject("id", UUID.class), row.getObject("owner_id", UUID.class),
                        row.getString("kind"))).list();
    }

    @Override
    public List<Search> searches(Collection<UUID> owners) {
        return jdbc.sql("select id, owner_id, campaign_id from search where owner_id in (:owners)")
                .param("owners", List.copyOf(owners))
                .query((row, number) -> new Search(row.getObject("id", UUID.class),
                        row.getObject("owner_id", UUID.class), row.getObject("campaign_id", UUID.class))).list();
    }

    /** A name of the schema, quoted (each comes from {@code information_schema}). */
    private static String quoted(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    private static String text(Map<String, Object> row) {
        return row.entrySet().stream().map(column -> column.getKey() + "=" + (column.getValue() instanceof byte[] bytes
                ? HexFormat.of().formatHex(bytes) : String.valueOf(column.getValue())))
                .collect(Collectors.joining(", ", "{", "}"));
    }
}

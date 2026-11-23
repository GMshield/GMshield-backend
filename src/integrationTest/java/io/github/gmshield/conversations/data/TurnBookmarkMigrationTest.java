package io.github.gmshield.conversations.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Story 3.12: V33 on rows that exist before it, on a fresh schema of the test-mode PostgreSQL (postgres:5432) with no
 * application started. Migrated to V32, given two owners' turns (one stopped, one dismissed) written without
 * {@code bookmarked_at}, then migrated to the latest: every existing turn is not saved (null), and so is one inserted
 * afterwards without the column, as the previous release inserts one; the column then takes a time and is cleared
 * again, nothing else of the row changes, and the partial index on (owner, bookmarked_at) covers the saved rows only.
 */
class TurnBookmarkMigrationTest {

    private static final String SCHEMA = "v33_migration_test";
    private static final UUID OWNER_A = UUID.fromString("0190a0b0-0000-7000-8000-0000000000a5");
    private static final UUID OWNER_B = UUID.fromString("0190a0b0-0000-7000-8000-0000000000b5");

    private final DriverManagerDataSource dataSource =
            new DriverManagerDataSource("jdbc:postgresql://postgres:5432/gmshield", "gmshield", "gmshield-test-mode");
    private JdbcClient jdbc;

    @BeforeEach
    void freshSchema() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("drop schema if exists " + SCHEMA + " cascade").update();
    }

    @AfterEach
    void dropSchema() {
        jdbc.sql("drop schema if exists " + SCHEMA + " cascade").update();
    }

    @Test
    void v33LeavesEveryExistingTurnUnsavedAndTakesABookmarkTime() {
        flyway().target("32").load().migrate();
        UUID conversationA = conversation(OWNER_A, campaign(OWNER_A, system(OWNER_A)));
        UUID conversationB = conversation(OWNER_B, campaign(OWNER_B, system(OWNER_B)));
        UUID stopped = turn(OWNER_A, conversationA);
        OffsetDateTime stoppedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("update " + SCHEMA + ".turn set stopped_at = ? where id = ?").params(stoppedAt, stopped).update();
        UUID dismissed = turn(OWNER_A, conversationA);
        OffsetDateTime dismissedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("update " + SCHEMA + ".turn set dismissed_at = ? where id = ?").params(dismissedAt, dismissed)
                .update();
        List<UUID> turns = List.of(stopped, dismissed, turn(OWNER_B, conversationB));

        flyway().load().migrate();

        for (UUID turn : turns) {
            assertThat(bookmarkedAt(turn)).isNull();
        }
        // Nothing else of an existing row changed: the stop and the dismissal are as they were.
        assertThat(jdbc.sql("select stopped_at from " + SCHEMA + ".turn where id = ?").param(stopped)
                .query(OffsetDateTime.class).single().toInstant()).isEqualTo(stoppedAt.toInstant());
        assertThat(jdbc.sql("select dismissed_at from " + SCHEMA + ".turn where id = ?").param(dismissed)
                .query(OffsetDateTime.class).single().toInstant()).isEqualTo(dismissedAt.toInstant());
        UUID later = turn(OWNER_B, conversationB);
        assertThat(bookmarkedAt(later)).isNull();
        OffsetDateTime at = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("update " + SCHEMA + ".turn set bookmarked_at = ? where id = ?").params(at, dismissed).update();
        assertThat(bookmarkedAt(dismissed).toInstant()).isEqualTo(at.toInstant());
        assertThat(jdbc.sql("select dismissed_at from " + SCHEMA + ".turn where id = ?").param(dismissed)
                .query(OffsetDateTime.class).single().toInstant()).isEqualTo(dismissedAt.toInstant());
        jdbc.sql("update " + SCHEMA + ".turn set bookmarked_at = null where id = ?").param(dismissed).update();
        assertThat(bookmarkedAt(dismissed)).isNull();
        String index = jdbc.sql("select indexdef from pg_indexes where schemaname = ? and indexname = ?")
                .params(SCHEMA, "turn_owner_bookmarked").query(String.class).single();
        assertThat(index).contains("(owner_id, bookmarked_at)").contains("WHERE (bookmarked_at IS NOT NULL)");
    }

    private OffsetDateTime bookmarkedAt(UUID turn) {
        return jdbc.sql("select bookmarked_at from " + SCHEMA + ".turn where id = ?").param(turn)
                .query(OffsetDateTime.class).list().getFirst();
    }

    private FluentConfiguration flyway() {
        return Flyway.configure().dataSource(dataSource).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(true)
                .locations("classpath:db/migration");
    }

    private UUID system(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".system (id, owner_id, name, name_fingerprint) values (?, ?, ?, ?)")
                .params(id, owner, new byte[] {1}, id.toString().getBytes(StandardCharsets.UTF_8)).update();
        return id;
    }

    private UUID campaign(UUID owner, UUID system) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".campaign (id, owner_id, system_id, name, name_fingerprint)"
                        + " values (?, ?, ?, ?, ?)")
                .params(id, owner, system, new byte[] {2}, id.toString().getBytes(StandardCharsets.UTF_8)).update();
        return id;
    }

    private UUID conversation(UUID owner, UUID campaign) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".conversation (id, owner_id, campaign_id, created_at) values (?, ?, ?, ?)")
                .params(id, owner, campaign, Timestamp.from(Instant.now())).update();
        return id;
    }

    /** A turn as the previous release inserts it: no bookmarked_at. */
    private UUID turn(UUID owner, UUID conversation) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".turn (id, owner_id, conversation_id, job_id, question, language,"
                        + " campaign_tier, system_tier, model_knowledge, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, owner, conversation, UUID.randomUUID(), new byte[] {3}, "pt-BR", true, true, false,
                        Timestamp.from(Instant.now())).update();
        return id;
    }
}

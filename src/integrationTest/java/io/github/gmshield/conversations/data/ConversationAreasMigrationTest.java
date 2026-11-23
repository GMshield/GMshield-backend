package io.github.gmshield.conversations.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Story 3.10: V30 on rows that exist before it, on a fresh schema of the test-mode PostgreSQL (postgres:5432) with no
 * application started. Migrated to V29, given a conversation with several turns and one with none, then migrated to
 * the latest: each is a {@code questions} conversation at version 0 with no name, its {@code last_asked_at} its newest
 * turn's time (else its own), and a conversation inserted afterwards without the new columns, as the previous release
 * inserts one, takes the defaults.
 */
class ConversationAreasMigrationTest {

    private static final String SCHEMA = "v30_migration_test";
    private static final UUID OWNER = UUID.fromString("0190a0b0-0000-7000-8000-0000000000a3");
    private static final Instant CREATED = Instant.parse("2026-11-10T10:00:00Z");

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
    void v30MakesEveryExistingConversationAQuestionsOneAskedAtItsNewestTurnAndAnInsertTakesTheDefaults() {
        flyway().target("29").load().migrate();
        UUID system = system();
        UUID campaign = campaign(system);
        UUID asked = conversation(campaign, CREATED);
        Instant newest = CREATED.plus(3, ChronoUnit.DAYS);
        turn(asked, CREATED.plus(1, ChronoUnit.HOURS));
        turn(asked, newest);
        turn(asked, CREATED.plus(2, ChronoUnit.DAYS));
        // Before V30 a campaign has at most one conversation.
        UUID empty = conversation(campaign(system), CREATED.plus(5, ChronoUnit.DAYS));

        flyway().load().migrate();

        assertMigrated(asked, newest);
        assertMigrated(empty, CREATED.plus(5, ChronoUnit.DAYS));
        // The previous release's insert names none of the new columns; a second one of the same campaign is allowed.
        Instant before = Instant.now().minusSeconds(60);
        UUID inserted = conversation(campaign, CREATED);
        Map<String, Object> row = row(inserted);
        assertThat(row).containsEntry("area", "questions").containsEntry("version", 0L).containsEntry("name", null);
        assertThat(((Timestamp) row.get("last_asked_at")).toInstant()).isAfter(before);
    }

    private void assertMigrated(UUID conversation, Instant lastAsked) {
        Map<String, Object> row = row(conversation);
        assertThat(row).containsEntry("area", "questions").containsEntry("version", 0L).containsEntry("name", null);
        assertThat(((Timestamp) row.get("last_asked_at")).toInstant()).isEqualTo(lastAsked);
    }

    private Map<String, Object> row(UUID conversation) {
        return jdbc.sql("select area, version, name, last_asked_at from " + SCHEMA + ".conversation where id = ?")
                .param(conversation).query().singleRow();
    }

    private FluentConfiguration flyway() {
        return Flyway.configure().dataSource(dataSource).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(true)
                .locations("classpath:db/migration");
    }

    private UUID system() {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".system (id, owner_id, name, name_fingerprint) values (?, ?, ?, ?)")
                .params(id, OWNER, new byte[] {1}, id.toString().getBytes(StandardCharsets.UTF_8)).update();
        return id;
    }

    private UUID campaign(UUID system) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".campaign (id, owner_id, system_id, name) values (?, ?, ?, ?)")
                .params(id, OWNER, system, new byte[] {2}).update();
        return id;
    }

    private UUID conversation(UUID campaign, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".conversation (id, owner_id, campaign_id, created_at) values (?, ?, ?, ?)")
                .params(id, OWNER, campaign, Timestamp.from(createdAt)).update();
        return id;
    }

    private void turn(UUID conversation, Instant createdAt) {
        jdbc.sql("insert into " + SCHEMA + ".turn (id, owner_id, conversation_id, job_id, question, language,"
                        + " campaign_tier, system_tier, model_knowledge, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), OWNER, conversation, UUID.randomUUID(), new byte[] {3}, "pt-BR", true, true,
                        false, Timestamp.from(createdAt)).update();
    }
}

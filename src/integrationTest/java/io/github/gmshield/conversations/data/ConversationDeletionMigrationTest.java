package io.github.gmshield.conversations.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Story 3.10 part B: V31 on rows that exist before it, on a fresh schema of the test-mode PostgreSQL (postgres:5432)
 * with no application started. Migrated to V30, given two owners' conversations written without a state, then migrated
 * to the latest: every existing conversation is {@code ready}, and so is one inserted afterwards without a state, as the
 * previous release inserts one; a {@code deletion} row of kind {@code conversation} is accepted, an unknown kind is not.
 */
class ConversationDeletionMigrationTest {

    private static final String SCHEMA = "v31_migration_test";
    private static final UUID OWNER_A = UUID.fromString("0190a0b0-0000-7000-8000-0000000000a3");
    private static final UUID OWNER_B = UUID.fromString("0190a0b0-0000-7000-8000-0000000000b3");

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
    void v31MakesEveryExistingConversationReadyAndAcceptsTheConversationKind() {
        flyway().target("30").load().migrate();
        UUID campaignA = campaign(OWNER_A, system(OWNER_A));
        UUID campaignB = campaign(OWNER_B, system(OWNER_B));
        List<UUID> conversations = List.of(conversation(OWNER_A, campaignA), conversation(OWNER_A, campaignA),
                conversation(OWNER_B, campaignB));

        flyway().load().migrate();

        for (UUID conversation : conversations) {
            assertThat(state(conversation)).isEqualTo("ready");
        }
        assertThat(state(conversation(OWNER_B, campaignB))).isEqualTo("ready");
        deletion(OWNER_A, "conversation", conversations.get(0));
        assertThat(jdbc.sql("select count(*) from " + SCHEMA + ".deletion where kind = 'conversation'")
                .query(Long.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> deletion(OWNER_A, "turn", conversations.get(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("update " + SCHEMA + ".conversation set state = 'gone' where id = ?")
                .param(conversations.get(2)).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    private String state(UUID conversation) {
        return jdbc.sql("select state from " + SCHEMA + ".conversation where id = ?").param(conversation)
                .query(String.class).single();
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

    private void deletion(UUID owner, String kind, UUID target) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("insert into " + SCHEMA + ".deletion (id, owner_id, kind, target_id, not_before, created_at)"
                + " values (?, ?, ?, ?, ?, ?)").params(UUID.randomUUID(), owner, kind, target, now, now).update();
    }
}

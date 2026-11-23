package io.github.gmshield.knowledge.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Story 4.4: V39 on record types that exist before it, on a fresh schema of the test-mode PostgreSQL (postgres:5432)
 * with no application started. Migrated to V38, given a core record type, then migrated to the latest: its key is its
 * core key and it has no proposal; a core row inserted afterwards without a key or a proposal, as the previous release
 * inserts one, is accepted.
 */
class RecordTypeKeyMigrationTest {

    private static final String SCHEMA = "v39_migration_test";
    private static final UUID OWNER = UUID.fromString("0190a0b0-0000-7000-8000-0000000000a4");

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
    void v39GivesAnExistingCoreTypeItsCoreKeyAndAcceptsACoreRowWithoutOne() {
        flyway().target("38").load().migrate();
        UUID system = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".system (id, owner_id, name, name_fingerprint) values (?, ?, ?, ?)")
                .params(system, OWNER, new byte[] {1}, system.toString().getBytes(StandardCharsets.UTF_8)).update();
        UUID spell = coreRow(system, "spell");

        flyway().load().migrate();

        assertThat(jdbc.sql("select key, proposal_id from " + SCHEMA + ".record_type where id = ?").param(spell)
                .query().singleRow()).containsEntry("key", "spell").containsEntry("proposal_id", null);
        UUID rule = coreRow(system, "rule");
        assertThat(jdbc.sql("select key from " + SCHEMA + ".record_type where id = ?").param(rule).query()
                .singleRow()).containsEntry("key", null);
    }

    private FluentConfiguration flyway() {
        return Flyway.configure().dataSource(dataSource).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(true)
                .locations("classpath:db/migration");
    }

    /** A core record type as the release before V39 inserts one: no key, no proposal. */
    private UUID coreRow(UUID system, String core) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".record_type (id, owner_id, system_id, core, name, name_fingerprint)"
                + " values (?, ?, ?, ?, ?, ?)").params(id, OWNER, system, core, new byte[] {2},
                (core + id).getBytes(StandardCharsets.UTF_8)).update();
        return id;
    }
}

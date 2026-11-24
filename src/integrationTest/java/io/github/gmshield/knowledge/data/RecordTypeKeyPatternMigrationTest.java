package io.github.gmshield.knowledge.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
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
 * Story 4.4 part B: V40 on rows that exist before it, on a fresh schema of the test-mode PostgreSQL (postgres:5432)
 * with no application started. Migrated to V39, given a core record and a search filtered by a core key, then migrated
 * to the latest: both stay, a record and a search of an approved type's key are stored, and a key that is not a key's
 * text (upper case, a leading digit, too long) is refused by the record's and the search's checks.
 */
class RecordTypeKeyPatternMigrationTest {

    private static final String SCHEMA = "v40_migration_test";
    private static final UUID OWNER = UUID.fromString("0190a0b0-0000-7000-8000-0000000000a5");

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
    void v40KeepsCoreKeysAndTakesAnyKeyOfThePatternWhileRefusingAnythingElse() {
        flyway().target("39").load().migrate();
        UUID system = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".system (id, owner_id, name, name_fingerprint) values (?, ?, ?, ?)")
                .params(system, OWNER, new byte[] {1}, system.toString().getBytes(StandardCharsets.UTF_8)).update();
        UUID campaign = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".campaign (id, owner_id, system_id, name) values (?, ?, ?, ?)")
                .params(campaign, OWNER, system, new byte[] {2}).update();
        UUID material = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".material (id, owner_id, name) values (?, ?, ?)")
                .params(material, OWNER, new byte[] {3}).update();
        UUID version = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".material_version (id, owner_id, material_id, state) values (?, ?, ?, ?)")
                .params(version, OWNER, material, "ready").update();
        UUID spell = record(version, "spell");
        UUID coreSearch = search(campaign, "spell");
        // Before V40 a key that is not core is refused.
        assertThatThrownBy(() -> record(version, "talento")).isInstanceOf(DataIntegrityViolationException.class);

        flyway().load().migrate();

        assertThat(jdbc.sql("select type from " + SCHEMA + ".record where id = ?").param(spell).query(String.class)
                .single()).isEqualTo("spell");
        assertThat(jdbc.sql("select record_type from " + SCHEMA + ".search where id = ?").param(coreSearch)
                .query(String.class).single()).isEqualTo("spell");
        UUID talent = record(version, "talento");
        assertThat(jdbc.sql("select type from " + SCHEMA + ".record where id = ?").param(talent).query(String.class)
                .single()).isEqualTo("talento");
        record(version, "t_3d6_tabela");
        record(version, "a" + "b".repeat(31));
        search(campaign, "talento");
        search(campaign, null);
        for (String refused : new String[] {"Talento", "3d6", "_x", "a-b", "a" + "b".repeat(32), ""}) {
            assertThatThrownBy(() -> record(version, refused)).as(refused)
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> search(campaign, refused)).as(refused)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    private FluentConfiguration flyway() {
        return Flyway.configure().dataSource(dataSource).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(true)
                .locations("classpath:db/migration");
    }

    private UUID record(UUID version, String type) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".record (id, owner_id, version_id, ordinal, first_page, type, name, fields,"
                + " created_at) values (?, ?, ?, 0, 1, ?, ?, ?, ?)").params(id, OWNER, version, type, new byte[] {4},
                new byte[] {5}, OffsetDateTime.now()).update();
        return id;
    }

    private UUID search(UUID campaign, String recordType) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".search (id, owner_id, campaign_id, job_id, query, state, record_type,"
                + " created_at) values (?, ?, ?, ?, ?, 'pending', ?, ?)").params(id, OWNER, campaign, UUID.randomUUID(),
                new byte[] {6}, recordType, OffsetDateTime.now()).update();
        return id;
    }
}

package io.github.gmshield.library.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * Story 3.2: V23 on rows that exist before it, on a fresh schema of the test-mode PostgreSQL (postgres:5432) with no
 * application started. Migrated to V22, given two owners' systems and campaigns (and a third owner with a system but no
 * campaign), then migrated to the latest: both owners with campaigns are listed in {@code campaign_fingerprint_backfill},
 * and every existing campaign has the new columns' defaults.
 */
class CampaignManagementMigrationTest {

    private static final String SCHEMA = "v23_migration_test";
    private static final UUID OWNER_A = UUID.fromString("0190a0b0-0000-7000-8000-0000000000a1");
    private static final UUID OWNER_B = UUID.fromString("0190a0b0-0000-7000-8000-0000000000b1");
    private static final UUID OWNER_C = UUID.fromString("0190a0b0-0000-7000-8000-0000000000c1");

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
    void v23ListsEveryOwnerWithCampaignsAndGivesTheExistingCampaignsTheNewColumnsDefaults() {
        flyway().target("22").load().migrate();
        UUID systemA = system(OWNER_A);
        UUID systemB = system(OWNER_B);
        system(OWNER_C);
        List<UUID> campaigns = List.of(campaign(OWNER_A, systemA), campaign(OWNER_A, systemA), campaign(OWNER_B, systemB));

        flyway().load().migrate();

        assertThat(jdbc.sql("select owner_id from " + SCHEMA + ".campaign_fingerprint_backfill order by owner_id")
                .query(UUID.class).list()).containsExactly(OWNER_A, OWNER_B);
        for (UUID campaign : campaigns) {
            Map<String, Object> row = jdbc.sql("select version, archived_at, chosen_at, name_fingerprint from " + SCHEMA
                    + ".campaign where id = ?").param(campaign).query().singleRow();
            assertThat(row).containsEntry("version", 0L).containsEntry("archived_at", null).containsEntry("chosen_at", null)
                    .containsEntry("name_fingerprint", null);
        }
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
        jdbc.sql("insert into " + SCHEMA + ".campaign (id, owner_id, system_id, name) values (?, ?, ?, ?)")
                .params(id, owner, system, new byte[] {2}).update();
        return id;
    }
}

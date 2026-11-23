package io.github.gmshield.materials.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
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
 * Story 3.3: V25 on rows that exist before it, on a fresh schema of the test-mode PostgreSQL (postgres:5432) with no
 * application started. Migrated to V24, given materials written without a version (one of a campaign, one with no
 * place), then migrated to the latest: every existing material is at version 0 with its campaign kept, and so is a
 * material inserted afterwards without a version, as the previous release inserts one.
 */
class MaterialBelongingMigrationTest {

    private static final String SCHEMA = "v25_migration_test";
    private static final UUID OWNER = UUID.fromString("0190a0b0-0000-7000-8000-0000000000a3");

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
    void v25PutsEveryExistingMaterialAtVersionZeroAndAMaterialInsertedWithoutAVersionStartsThere() {
        flyway().target("24").load().migrate();
        UUID system = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".system (id, owner_id, name, name_fingerprint) values (?, ?, ?, ?)")
                .params(system, OWNER, new byte[] {1}, system.toString().getBytes(StandardCharsets.UTF_8)).update();
        UUID campaign = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".campaign (id, owner_id, system_id, name) values (?, ?, ?, ?)")
                .params(campaign, OWNER, system, new byte[] {2}).update();
        UUID ofCampaign = material(campaign);
        UUID loose = material(null);

        flyway().load().migrate();

        for (UUID material : List.of(ofCampaign, loose, material(campaign))) {
            assertThat(jdbc.sql("select version from " + SCHEMA + ".material where id = ?").param(material)
                    .query(Long.class).single()).isZero();
        }
        assertThat(jdbc.sql("select campaign_id from " + SCHEMA + ".material where id = ?").param(ofCampaign)
                .query(UUID.class).single()).isEqualTo(campaign);
    }

    private FluentConfiguration flyway() {
        return Flyway.configure().dataSource(dataSource).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(true)
                .locations("classpath:db/migration");
    }

    private UUID material(UUID campaign) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".material (id, owner_id, name, campaign_id) values (?, ?, ?, ?)")
                .params(id, OWNER, new byte[] {3}, campaign).update();
        return id;
    }
}

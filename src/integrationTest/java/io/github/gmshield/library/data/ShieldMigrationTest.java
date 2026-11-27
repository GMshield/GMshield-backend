package io.github.gmshield.library.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
 * Story 6.1: V53 on rows that exist before it, on a fresh schema of the test-mode PostgreSQL (postgres:5432) with no
 * application started. Migrated to V52, given two owners' campaigns (one of them deleting) and an owner with a system but
 * no campaign, then migrated to the latest: each campaign has exactly one unnamed Shield of its owner, with a UUID v7 id
 * and version 0; then the table's keys: one unnamed Shield per campaign, a name unique within its campaign, the
 * campaign's owner, and a campaign that still has Shields cannot be deleted.
 */
class ShieldMigrationTest {

    private static final String SCHEMA = "v53_migration_test";
    private static final UUID OWNER_A = UUID.fromString("0190a0b0-0000-7000-8000-0000000006a1");
    private static final UUID OWNER_B = UUID.fromString("0190a0b0-0000-7000-8000-0000000006b1");
    private static final UUID OWNER_C = UUID.fromString("0190a0b0-0000-7000-8000-0000000006c1");

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
    void v53GivesEveryExistingCampaignOneUnnamedShieldOfItsOwner() {
        flyway().target("52").load().migrate();
        UUID systemA = system(OWNER_A);
        UUID systemB = system(OWNER_B);
        system(OWNER_C);
        UUID deleting = campaign(OWNER_A, systemA);
        jdbc.sql("update " + SCHEMA + ".campaign set state = 'deleting' where id = ?").param(deleting).update();
        List<UUID> campaigns = List.of(campaign(OWNER_A, systemA), deleting, campaign(OWNER_B, systemB));

        flyway().load().migrate();

        for (UUID campaign : campaigns) {
            Map<String, Object> row = jdbc.sql("select s.id, s.owner_id, s.name, s.name_fingerprint, s.version,"
                    + " s.created_at, c.owner_id as campaign_owner from " + SCHEMA + ".shield s join " + SCHEMA
                    + ".campaign c on c.id = s.campaign_id where s.campaign_id = ?").param(campaign).query().singleRow();
            assertThat(row).containsEntry("owner_id", row.get("campaign_owner")).containsEntry("name", null)
                    .containsEntry("name_fingerprint", null).containsEntry("version", 0L);
            assertThat(row.get("created_at")).isNotNull();
            assertThat(((UUID) row.get("id")).version()).isEqualTo(7);
        }
        assertThat(jdbc.sql("select count(distinct id) from " + SCHEMA + ".shield").query(Long.class).single())
                .isEqualTo(3);
        assertThat(jdbc.sql("select count(*) from " + SCHEMA + ".shield where owner_id = ?").param(OWNER_C)
                .query(Long.class).single()).isZero();
    }

    @Test
    void theKeysAllowOneUnnamedShieldPerCampaignAUniqueNameAndNoCampaignDeletedUnderItsShields() {
        flyway().load().migrate();
        UUID system = system(OWNER_A);
        UUID campaign = campaign(OWNER_A, system);
        UUID other = campaign(OWNER_A, system);
        UUID ofB = campaign(OWNER_B, system(OWNER_B));
        shield(OWNER_A, campaign, null, null);
        shield(OWNER_A, other, null, null);
        shield(OWNER_A, campaign, new byte[] {1}, new byte[] {9});
        shield(OWNER_A, other, new byte[] {2}, new byte[] {9});

        assertThatThrownBy(() -> shield(OWNER_A, campaign, null, null)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("shield_campaign_unnamed_key");
        assertThatThrownBy(() -> shield(OWNER_A, campaign, new byte[] {3}, new byte[] {9}))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("shield_campaign_name_fingerprint_key");
        // Within its owner only: another owner's campaign cannot hold it.
        assertThatThrownBy(() -> shield(OWNER_A, ofB, new byte[] {4}, new byte[] {8}))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("shield_campaign_fkey");
        // A name and its fingerprint come together.
        assertThatThrownBy(() -> shield(OWNER_A, campaign, new byte[] {5}, null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("shield_name_check");
        assertThatThrownBy(() -> jdbc.sql("delete from " + SCHEMA + ".campaign where id = ?").param(campaign).update())
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("shield_campaign_fkey");

        jdbc.sql("delete from " + SCHEMA + ".shield where campaign_id = ?").param(campaign).update();
        assertThat(jdbc.sql("delete from " + SCHEMA + ".campaign where id = ?").param(campaign).update()).isOne();
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

    private void shield(UUID owner, UUID campaign, byte[] name, byte[] fingerprint) {
        jdbc.sql("insert into " + SCHEMA + ".shield (id, owner_id, campaign_id, name, name_fingerprint)"
                + " values (?, ?, ?, ?, ?)").params(UUID.randomUUID(), owner, campaign, name, fingerprint).update();
    }
}

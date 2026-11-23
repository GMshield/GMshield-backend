package io.github.gmshield.materials.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.conversations.service.Conversations;
import io.github.gmshield.jobs.service.JobClaims;
import io.github.gmshield.jobs.service.JobPassage;
import io.github.gmshield.jobs.service.JobPassageReads;
import io.github.gmshield.library.service.Language;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.MaterialSystemRow;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.scope.service.CampaignScopes;
import io.github.gmshield.scope.service.ScopeSettings;
import io.github.gmshield.scope.service.Tier;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Story 3.5 against a real PostgreSQL and Redis (test mode): V27's tables through JPA. The starting settings with no
 * row, the first change inserting the row at version 1, the material switches replaced, a stale version refused with
 * nothing written, another owner's campaign not found; a question freezing the settings on its turn
 * ({@code turn.other_tier}, {@code turn_material_off}) so a change made while it is answered applies from the next
 * one; and a campaign's and a material's deletion removing their rows. The properties are
 * {@code SystemDeletionIntegrationTest}'s, so the two share one Spring context (another one would exhaust the
 * database's connections).
 *
 * <p>The raw rows are read here with SQL to prove what is stored; the application never reads a library table with
 * native SQL.
 */
@SpringBootTest(properties = {"GMSHIELD_MODE=test", "gmshield.jobs.sweep.enabled=false",
    "gmshield.materials.upload-sweep.enabled=false",
    "gmshield.materials.version-removal-sweep.enabled=false",
    "gmshield.library.deletion-sweep.enabled=false",
    "gmshield.materials.index-retry-sweep.enabled=false",
    "gmshield.files.dir=${java.io.tmpdir}/gmshield-deletion-it"})
@ActiveProfiles("test-mode")
class ScopeSettingsIntegrationTest {

    @Autowired
    private Library library;
    @Autowired
    private CampaignScopes campaignScopes;
    @Autowired
    private Conversations conversations;
    @Autowired
    private SystemDeletion deletion;
    @Autowired
    private MaterialStore store;
    @Autowired
    private JobClaims claims;
    @Autowired
    private JobPassageReads passages;
    @Autowired
    private Vault vault;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactions;

    private Instant clock = Instant.now().truncatedTo(ChronoUnit.MICROS);

    private record Owner(UUID id, UUID campaign, UUID system) {
    }

    /** A ready material: its id, its version and one passage. */
    private record Ready(UUID materialId, UUID versionId, UUID passageId) {
    }

    @Test
    void theSettingsAreStoredOnTheirVersionAndAStaleChangeWritesNothingOnPostgres() throws Exception {
        Owner a = owner();
        Ready own = ready(a.id(), a.campaign(), null);
        Ready ofSystem = ready(a.id(), null, a.system());
        UUID sibling = as(a.id(), () -> library.createCampaign(a.system(), "Mesa irmã")).orElseThrow().id();
        Ready ofSibling = ready(a.id(), sibling, null);

        CampaignScopes.View starting = as(a.id(), () -> campaignScopes.view(a.campaign())).orElseThrow();
        assertThat(starting.version()).isZero();
        assertThat(starting.settings()).isEqualTo(ScopeSettings.STARTING);
        assertThat(starting.materials()).extracting(CampaignScopes.Material::id, CampaignScopes.Material::tier)
                .containsExactly(org.assertj.core.api.Assertions.tuple(own.materialId(), Tier.CAMPAIGN),
                        org.assertj.core.api.Assertions.tuple(ofSystem.materialId(), Tier.SYSTEM),
                        org.assertj.core.api.Assertions.tuple(ofSibling.materialId(), Tier.OTHER));
        assertThat(count("scope_setting", a.campaign())).isZero();

        CampaignScopes.View first = change(a, 0, true, true, true, true, List.of(ofSystem.materialId(),
                ofSibling.materialId()));
        assertThat(first.version()).isEqualTo(1);
        assertThat(jdbc.sql("select owner_id, campaign_tier, system_tier, other_tier, model_knowledge, version"
                + " from scope_setting where campaign_id = ?").param(a.campaign()).query().singleRow())
                .containsEntry("owner_id", a.id()).containsEntry("other_tier", true)
                .containsEntry("model_knowledge", true).containsEntry("version", 1L);
        assertThat(offOf(a.campaign())).containsExactlyInAnyOrder(ofSystem.materialId(), ofSibling.materialId());

        CampaignScopes.View second = change(a, 1, true, true, false, false, List.of(ofSystem.materialId()));
        assertThat(second.version()).isEqualTo(2);
        assertThat(offOf(a.campaign())).containsExactly(ofSystem.materialId());
        // A stale version, an unknown material and another owner change nothing.
        assertThatThrownBy(() -> change(a, 1, false, false, false, false, List.of()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.problem().code()).isEqualTo("request.stale_version"));
        assertThatThrownBy(() -> change(a, 2, true, true, false, false, List.of(Ids.newId())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.problem().code()).isEqualTo("scope.material_unknown"));
        assertThat(as(Ids.newId(), () -> campaignScopes.view(a.campaign()))).isEmpty();
        assertThat(as(Ids.newId(), () -> campaignScopes.change(a.campaign(),
                new CampaignScopes.Change(2, false, false, false, false, List.of())))).isEmpty();
        assertThat(jdbc.sql("select version from scope_setting where campaign_id = ?").param(a.campaign())
                .query(Long.class).single()).isEqualTo(2L);
        assertThat(offOf(a.campaign())).containsExactly(ofSystem.materialId());
        assertThat(as(a.id(), () -> campaignScopes.settings(a.campaign())))
                .isEqualTo(new ScopeSettings(true, true, false, false, Set.of(ofSystem.materialId())));
        // The same switches with only the materials off changed: the version rises by exactly one, and the previous
        // version is stale from then on.
        CampaignScopes.View materialsOnly = change(a, 2, true, true, false, false, List.of());
        assertThat(materialsOnly.version()).isEqualTo(3);
        assertThat(jdbc.sql("select version from scope_setting where campaign_id = ?").param(a.campaign())
                .query(Long.class).single()).isEqualTo(3L);
        assertThat(offOf(a.campaign())).isEmpty();
        assertThatThrownBy(() -> change(a, 2, true, true, false, false, List.of(ofSystem.materialId())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.problem().code()).isEqualTo("request.stale_version"));
        assertThat(offOf(a.campaign())).isEmpty();
    }

    @Test
    void aQuestionFreezesTheSettingsSoAChangeAppliesFromTheNextQuestionOnPostgres() throws Exception {
        Owner a = owner();
        Ready own = ready(a.id(), a.campaign(), null);
        UUID sibling = as(a.id(), () -> library.createCampaign(a.system(), "Mesa irmã")).orElseThrow().id();
        Ready ofSibling = ready(a.id(), sibling, null);
        change(a, 0, true, true, true, false, List.of());

        UUID firstTurn = as(a.id(), () -> conversations.ask(a.campaign(), "Quanto dano?")).turnId();
        JobClaims.Claim first = claims.claim(jobOf(firstTurn), null, List.of("azure-openai"));
        change(a, 1, true, true, false, false, List.of(own.materialId()));
        UUID secondTurn = as(a.id(), () -> conversations.ask(a.campaign(), "E o alcance?")).turnId();

        assertThat(jdbc.sql("select other_tier from turn where id = ?").param(firstTurn).query(Boolean.class).single())
                .isTrue();
        assertThat(jdbc.sql("select other_tier from turn where id = ?").param(secondTurn).query(Boolean.class)
                .single()).isFalse();
        assertThat(jdbc.sql("select material_id from turn_material_off where turn_id = ?").param(firstTurn)
                .query(UUID.class).list()).isEmpty();
        assertThat(jdbc.sql("select owner_id, material_id from turn_material_off where turn_id = ?").param(secondTurn)
                .query().singleRow()).containsEntry("owner_id", a.id()).containsEntry("material_id", own.materialId());
        // The first turn keeps its frozen scope after the change: its own material and the sibling's still read.
        List<JobPassage> read = passages.read(first.jobId(), first.claimId(), first.grant(),
                List.of(own.passageId(), ofSibling.passageId()));
        assertThat(read).extracting(JobPassage::tier).containsExactly("campaign", "other");
        // The second turn has the new one: the other tier off, its own material off.
        JobClaims.Claim second = claims.claim(jobOf(secondTurn), null, List.of("azure-openai"));
        JsonNode tiers = second.input().path("tiers");
        assertThat(tiers.get(0).path("sources").isEmpty()).isTrue();
        assertThat(tiers.get(2).path("tier").asString()).isEqualTo("other");
        assertThat(tiers.get(2).path("sources").isEmpty()).isTrue();
        assertThatThrownBy(() -> passages.read(second.jobId(), second.claimId(), second.grant(),
                List.of(own.passageId()))).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.problem().code()).isEqualTo("scope.passage_refused"));
    }

    @Test
    void aCampaignsAndAMaterialsDeletionRemoveTheirScopeRowsOnPostgres() throws Exception {
        Owner a = owner();
        Ready ofSystem = ready(a.id(), null, a.system());
        UUID going = as(a.id(), () -> library.createCampaign(a.system(), "Mesa que vai")).orElseThrow().id();
        Ready own = ready(a.id(), a.campaign(), null);
        change(a, 0, true, true, true, false, List.of(ofSystem.materialId(), own.materialId()));
        as(a.id(), () -> campaignScopes.change(going, new CampaignScopes.Change(0, true, true, true, false,
                List.of(ofSystem.materialId())))).orElseThrow();
        UUID turn = as(a.id(), () -> conversations.ask(going, "Quanto dano?")).turnId();
        assertThat(jdbc.sql("select count(*) from turn_material_off where turn_id = ?").param(turn).query(Long.class)
                .single()).isOne();

        assertThat(as(a.id(), () -> deletion.deleteCampaign(going))).isTrue();
        UUID campaignDeletion = deletionOf(going);
        assertThat(deletion.run(a.id(), campaignDeletion)).isTrue();

        assertThat(count("scope_setting", going)).isZero();
        assertThat(count("scope_material_off", going)).isZero();
        assertThat(jdbc.sql("select count(*) from turn_material_off where turn_id = ?").param(turn).query(Long.class)
                .single()).isZero();
        assertThat(count("scope_setting", a.campaign())).isOne();
        // A material's deletion removes its switch in every campaign; the others stay.
        assertThat(as(a.id(), () -> deletion.deleteMaterial(ofSystem.materialId()))).isTrue();
        assertThat(deletion.run(a.id(), deletionOf(ofSystem.materialId()))).isTrue();
        assertThat(offOf(a.campaign())).containsExactly(own.materialId());
    }

    private CampaignScopes.View change(Owner owner, long version, boolean campaignTier, boolean systemTier,
                                       boolean otherTier, boolean modelKnowledge, List<UUID> off) throws Exception {
        return as(owner.id(), () -> campaignScopes.change(owner.campaign(), new CampaignScopes.Change(version,
                campaignTier, systemTier, otherTier, modelKnowledge, off))).orElseThrow();
    }

    private List<UUID> offOf(UUID campaign) {
        return jdbc.sql("select material_id from scope_material_off where campaign_id = ?").param(campaign)
                .query(UUID.class).list();
    }

    private long count(String table, UUID campaign) {
        return jdbc.sql("select count(*) from " + table + " where campaign_id = ?").param(campaign).query(Long.class)
                .single();
    }

    private UUID jobOf(UUID turn) {
        return jdbc.sql("select job_id from turn where id = ?").param(turn).query(UUID.class).single();
    }

    private UUID deletionOf(UUID target) {
        return jdbc.sql("select id from deletion where target_id = ?").param(target).query(UUID.class).single();
    }

    private Owner owner() throws Exception {
        UUID id = Ids.newId();
        return as(id, () -> {
            new TransactionTemplate(transactions).executeWithoutResult(status -> library.createStarter(Language.PT_BR));
            Library.Campaign campaign = library.currentCampaign().orElseThrow();
            return new Owner(id, campaign.id(), campaign.system().id());
        });
    }

    private Ready ready(UUID owner, UUID campaign, UUID system) throws Exception {
        return as(owner, () -> new TransactionTemplate(transactions).execute(status -> {
            UUID materialId = Ids.newId();
            store.insertMaterial(new MaterialRow(materialId, owner, vault.encryptText(new Binding(Uploads.MATERIAL,
                    Uploads.FIELD_NAME, materialId, owner), "Livro"), next(), "rulebook", null, campaign));
            if (system != null) {
                store.insertMaterialSystem(new MaterialSystemRow(Ids.newId(), owner, materialId, system, next()));
            }
            UUID versionId = Ids.newId();
            Instant at = next();
            store.insertVersion(new VersionRow(versionId, owner, materialId, "ready", "pdf", 1000L, at, at, 1, "5",
                    null));
            UUID passageId = Ids.newId();
            store.insertPassages(List.of(new PassageRow(passageId, owner, versionId, 0, 1,
                    vault.encryptText(Passages.text(passageId, owner), "Texto"),
                    vault.encrypt(Passages.place(passageId, owner), Passages.placeJson(new Passages.Place(List.of("A"),
                            null, List.of(0, 5)))), next())));
            return new Ready(materialId, versionId, passageId);
        }));
    }

    private Instant next() {
        clock = clock.plus(1, ChronoUnit.MILLIS);
        return clock;
    }

    private static <T> T as(UUID owner, Callable<T> work) throws Exception {
        return OwnerContext.callAs(owner, work::call);
    }
}

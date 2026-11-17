package io.github.gmshield.materials.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.library.service.Language;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.MaterialSystemRow;
import io.github.gmshield.materials.data.NewestVersion;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/**
 * Story 1.14 against a real PostgreSQL (test mode: postgres:5432): V7's columns and their checks, a material's
 * campaign and its game system within the owner, the newest-version query behind the tray and its cursor, and the
 * cross-owner test of {@code material_system}.
 *
 * <p>The raw rows are read here with SQL to prove what is stored; the application never reads {@code material},
 * {@code material_version} or {@code material_system} with native SQL.
 */
@SpringBootTest(properties = {"GMSHIELD_MODE=test", "gmshield.jobs.sweep.enabled=false",
    "gmshield.materials.upload-sweep.enabled=false", "gmshield.files.dir=${java.io.tmpdir}/gmshield-material-it"})
@ActiveProfiles("test-mode")
class MaterialIntegrationTest {

    @Autowired
    private Uploads uploads;
    @Autowired
    private Materials materials;
    @Autowired
    private MaterialStore store;
    @Autowired
    private Library library;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private JdbcClient jdbc;

    /** An owner with the starter system and campaign, as a first sign-in makes them. */
    private record Owner(UUID id, UUID campaign, UUID system) {
    }

    @Test
    void theDetailsAreStoredInTheirColumnsAndASystemIsOneMaterialSystemRow() throws Exception {
        Owner a = owner();

        UUID withCampaign = material(a.id(), as(a.id(), () -> uploads.create(10, "Livro.pdf",
                new Uploads.Details("rulebook", "detect", a.campaign().toString(), null))));
        UUID withSystem = material(a.id(), as(a.id(), () -> uploads.create(10, "Bestiário.pdf",
                new Uploads.Details("supplement", "pt-BR", null, a.system().toString()))));
        UUID bare = material(a.id(), as(a.id(), () -> uploads.create(10, "old.pdf")));

        Map<String, Object> first = jdbc.sql("select kind, language, campaign_id from material where id = ?")
                .param(withCampaign).query().singleRow();
        assertThat(first.get("kind")).isEqualTo("rulebook");
        assertThat(first.get("language")).isNull();
        assertThat(first.get("campaign_id")).isEqualTo(a.campaign());
        Map<String, Object> second = jdbc.sql("select kind, language, campaign_id from material where id = ?")
                .param(withSystem).query().singleRow();
        assertThat(second.get("language")).isEqualTo("pt-BR");
        assertThat(second.get("campaign_id")).isNull();
        assertThat(jdbc.sql("select owner_id, system_id from material_system where material_id = ?").param(withSystem)
                .query().listOfRows()).containsExactly(Map.of("owner_id", a.id(), "system_id", a.system()));
        assertThat(as(a.id(), () -> store.systemsOf(withSystem))).extracting(MaterialSystemRow::systemId)
                .containsExactly(a.system());
        assertThat(jdbc.sql("select kind, language, campaign_id from material where id = ?").param(bare).query().singleRow()
                .values()).containsOnlyNulls();
    }

    @Test
    void anotherOwnersCampaignOrSystemIsInvalidMetadataAndStoresNothing() throws Exception {
        Owner a = owner();
        Owner b = owner();
        long before = jdbc.sql("select count(*) from material where owner_id = ?").param(b.id()).query(Long.class).single();

        for (Uploads.Details details : List.of(new Uploads.Details(null, null, a.campaign().toString(), null),
                new Uploads.Details(null, null, null, a.system().toString()),
                new Uploads.Details(null, null, b.campaign().toString(), b.system().toString()))) {
            assertThatThrownBy(() -> as(b.id(), () -> uploads.create(10, "x.pdf", details)))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).problem().code())
                            .isEqualTo("upload.metadata_invalid"));
        }
        assertThat(jdbc.sql("select count(*) from material where owner_id = ?").param(b.id()).query(Long.class).single())
                .isEqualTo(before);
    }

    @Test
    void theDatabaseChecksTheDetailsAndKeepsACampaignAndASystemWithinTheOwner() throws Exception {
        Owner a = owner();
        Owner b = owner();
        UUID bMaterial = material(b.id(), as(b.id(), () -> uploads.create(10, "b.pdf")));

        for (String sql : List.of("update material set kind = 'novel' where id = ?",
                "update material set language = 'fr' where id = ?",
                "update material set campaign_id = '" + a.campaign() + "' where id = ?")) {
            assertThatThrownBy(() -> jdbc.sql(sql).param(bMaterial).update()).as(sql).isNotNull();
        }
        // A material_system row of B naming A's system, or A's material, is refused by the owner-scoped keys.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThatThrownBy(() -> as(b.id(), () -> new TransactionTemplate(transactions).execute(status -> {
            store.insertMaterialSystem(new MaterialSystemRow(Ids.newId(), b.id(), bMaterial, a.system(), now));
            return null;
        }))).satisfies(failure -> assertThat(messages(failure)).anyMatch(text -> text.contains("material_system_system_fkey")));
        UUID aMaterial = material(a.id(), as(a.id(), () -> uploads.create(10, "a.pdf")));
        assertThatThrownBy(() -> as(b.id(), () -> new TransactionTemplate(transactions).execute(status -> {
            store.insertMaterialSystem(new MaterialSystemRow(Ids.newId(), b.id(), aMaterial, b.system(), now));
            return null;
        }))).satisfies(failure -> assertThat(messages(failure))
                .anyMatch(text -> text.contains("material_system_material_fkey")));
        // Hibernate refuses a row of another owner before it is written.
        assertThatThrownBy(() -> as(b.id(), () -> new TransactionTemplate(transactions).execute(status -> {
            store.insertMaterialSystem(new MaterialSystemRow(Ids.newId(), a.id(), aMaterial, a.system(), now));
            return null;
        }))).satisfies(failure -> assertThat(messages(failure))
                .anyMatch(text -> text.contains("assigned tenant id differs from current tenant id")));
        assertThat(jdbc.sql("select count(*) from material_system where material_id in (?, ?)").param(aMaterial)
                .param(bMaterial).query(Long.class).single()).isZero();
    }

    @Test
    void materialSystemIsSeenByItsOwnerOnlyAndGoesWithItsMaterial() throws Exception {
        Owner a = owner();
        Owner b = owner();
        UUID linked = material(a.id(), as(a.id(), () -> uploads.create(10, "a.pdf",
                new Uploads.Details(null, null, null, a.system().toString()))));

        assertThat(as(a.id(), () -> store.systemsOf(linked))).hasSize(1);
        assertThat(as(b.id(), () -> store.systemsOf(linked))).isEmpty();
        assertThat(as(b.id(), () -> store.newest(linked))).isEmpty();
        assertThat(as(b.id(), () -> materials.find(linked))).isEmpty();

        // Removing the material (a refused format does) removes its row first.
        as(a.id(), () -> new TransactionTemplate(transactions).execute(status -> {
            deleteUploadsOf(linked);
            for (VersionRow version : versionsOf(linked)) {
                store.deleteVersion(version.id());
            }
            return store.deleteMaterial(linked);
        }));
        assertThat(jdbc.sql("select count(*) from material_system where material_id = ?").param(linked)
                .query(Long.class).single()).isZero();
        // A release that does not know the table removes the material alone: the reference goes with it.
        UUID older = material(a.id(), as(a.id(), () -> uploads.create(10, "c.pdf",
                new Uploads.Details(null, null, null, a.system().toString()))));
        deleteUploadsOf(older);
        jdbc.sql("delete from material_version where material_id = ?").param(older).update();
        jdbc.sql("delete from material where id = ?").param(older).update();
        assertThat(jdbc.sql("select count(*) from material_system where material_id = ?").param(older)
                .query(Long.class).single()).isZero();
    }

    @Test
    void theNewestVersionDecidesTheStateAndTheListPagesNewestFirst() throws Exception {
        Owner a = owner();
        Owner b = owner();
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Thread.sleep(2); // ids made in the same millisecond sort randomly (Ids)
            created.add(material(a.id(), as(a.id(), () -> uploads.create(10, "m.pdf",
                    new Uploads.Details("other", null, null, null)))));
        }
        material(b.id(), as(b.id(), () -> uploads.create(10, "b.pdf")));
        // The oldest material's version is ready, and a newer version of it is processing; the second is ready.
        Instant later = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        as(a.id(), () -> new TransactionTemplate(transactions).execute(status -> {
            store.changeVersion(versionsOf(created.get(0)).getFirst().id(), "ready", "pdf", 10L, later.minusSeconds(30));
            store.insertVersion(new VersionRow(Ids.newId(), a.id(), created.get(0), "processing", null, null, later, later));
            store.changeVersion(versionsOf(created.get(1)).getFirst().id(), "ready", "pdf", 10L, later);
            return null;
        }));

        List<NewestVersion> moving = as(a.id(), () -> store.newestIn(Set.of("uploading", "processing"), null, 10));
        assertThat(moving).extracting(found -> found.material().id())
                .containsExactly(created.get(4), created.get(3), created.get(2), created.get(0));
        assertThat(moving.getLast().version().state()).isEqualTo("processing");
        assertThat(as(a.id(), () -> store.newestIn(Set.of("ready"), null, 10))).extracting(found -> found.material().id())
                .containsExactly(created.get(1));

        Materials.Page first = as(a.id(), () -> materials.list(List.of("uploading", "processing"), null, 2));
        assertThat(first.items()).extracting(Materials.Material::id).containsExactly(created.get(4), created.get(3));
        assertThat(first.items()).allSatisfy(item -> {
            assertThat(item.upload()).isNotNull();
            assertThat(item.upload().length()).isEqualTo(10);
            assertThat(item.kind()).isEqualTo("other");
        });
        Materials.Page second = as(a.id(), () -> materials.list(List.of("uploading", "processing"), first.nextCursor(), 2));
        assertThat(second.items()).extracting(Materials.Material::id).containsExactly(created.get(2), created.get(0));
        assertThat(second.items().getLast().upload()).isNull();
        assertThat(second.nextCursor()).isNull();
        assertThat(as(a.id(), () -> materials.find(created.get(0))).orElseThrow().state()).isEqualTo("processing");
    }

    // --- helpers ---

    private Owner owner() throws Exception {
        UUID id = Ids.newId();
        return as(id, () -> {
            new TransactionTemplate(transactions).executeWithoutResult(status -> library.createStarter(Language.EN));
            Library.Campaign campaign = library.currentCampaign().orElseThrow();
            return new Owner(id, campaign.id(), campaign.system().id());
        });
    }

    /** The material of an upload just created. */
    private UUID material(UUID owner, Uploads.Created created) {
        return jdbc.sql("select v.material_id from upload u join material_version v on v.id = u.version_id where u.id = ?"
                + " and u.owner_id = ?").param(created.uploadId()).param(owner).query(UUID.class).single();
    }

    private void deleteUploadsOf(UUID material) {
        jdbc.sql("delete from upload where version_id in (select id from material_version where material_id = ?)")
                .param(material).update();
    }

    private List<VersionRow> versionsOf(UUID material) {
        return jdbc.sql("select id from material_version where material_id = ? order by created_at, id").param(material)
                .query(UUID.class).list().stream().map(id -> store.findVersion(id).orElseThrow()).toList();
    }

    private static <T> T as(UUID owner, Callable<T> work) throws Exception {
        return OwnerContext.callAs(owner, work::call);
    }

    private static List<String> messages(Throwable failure) {
        List<String> found = new ArrayList<>();
        for (Throwable cause = failure; cause != null && !found.contains(String.valueOf(cause.getMessage()));
             cause = cause.getCause()) {
            found.add(String.valueOf(cause.getMessage()));
        }
        return found;
    }
}

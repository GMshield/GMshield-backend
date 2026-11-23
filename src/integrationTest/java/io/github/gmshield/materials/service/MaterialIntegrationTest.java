package io.github.gmshield.materials.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.library.service.Language;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.MaterialSystemRow;
import io.github.gmshield.materials.data.CurrentVersion;
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
 * cross-owner test of {@code material_system}. Story 3.3: a move and a several-systems attach round-trip, the names
 * read for a page, a stale version, and two changes of one material at once.
 *
 * <p>The raw rows are read here with SQL to prove what is stored; the application never reads {@code material},
 * {@code material_version} or {@code material_system} with native SQL.
 */
@SpringBootTest(properties = {"GMSHIELD_MODE=test", "gmshield.jobs.sweep.enabled=false",
    "gmshield.materials.upload-sweep.enabled=false",
    "gmshield.materials.version-removal-sweep.enabled=false",
    "gmshield.library.deletion-sweep.enabled=false",
    "gmshield.materials.index-retry-sweep.enabled=false",
    "gmshield.files.dir=${java.io.tmpdir}/gmshield-material-it"})
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
    private MaterialBelongings belongings;
    @Autowired
    private MaterialActions actions;
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
                new Uploads.Details("rulebook", "detect", a.campaign().toString(), (String) null))));
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

        for (Uploads.Details details : List.of(new Uploads.Details(null, null, a.campaign().toString(), (String) null),
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

        Map<String, String> violations = Map.of(
                "update material set kind = 'novel' where id = ?", "material_kind_check",
                "update material set language = 'fr' where id = ?", "material_language_check",
                "update material set campaign_id = '" + a.campaign() + "' where id = ?", "material_campaign_fkey");
        violations.forEach((sql, constraint) -> assertThatThrownBy(() -> jdbc.sql(sql).param(bMaterial).update())
                .as(sql).satisfies(failure -> assertThat(messages(failure)).anyMatch(text -> text.contains(constraint))));
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
        assertThat(as(b.id(), () -> store.current(linked))).isEmpty();
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
    void theCurrentVersionDecidesTheStateAndTheListPagesNewestFirst() throws Exception {
        Owner a = owner();
        Owner b = owner();
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Thread.sleep(2); // ids made in the same millisecond sort randomly (Ids)
            created.add(material(a.id(), as(a.id(), () -> uploads.create(10, "m.pdf",
                    new Uploads.Details("other", null, null, (String) null)))));
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

        List<CurrentVersion> moving = as(a.id(), () -> store.currentIn(Set.of("uploading", "processing"), null, 10));
        assertThat(moving).extracting(found -> found.material().id())
                .containsExactly(created.get(4), created.get(3), created.get(2), created.get(0));
        assertThat(moving.getLast().version().state()).isEqualTo("processing");
        assertThat(as(a.id(), () -> store.currentIn(Set.of("ready"), null, 10))).extracting(found -> found.material().id())
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
        // A numbered page (the same rows by position) and the list's total, from the database's own count.
        assertThat(first.total()).isEqualTo(4);
        assertThat(second.total()).isEqualTo(4);
        Materials.Page byPosition = as(a.id(), () -> materials.list(List.of("uploading", "processing"), null, 2, 2));
        assertThat(byPosition.items()).extracting(Materials.Material::id).containsExactly(created.get(2), created.get(0));
        assertThat(byPosition.nextCursor()).isNull();
        assertThat(as(a.id(), () -> store.currentFrom(Set.of("uploading", "processing"), 1, 2)))
                .extracting(found -> found.material().id()).containsExactly(created.get(3), created.get(2));
        assertThat(as(a.id(), () -> store.currentCount(Set.of("uploading", "processing")))).isEqualTo(4);
        assertThat(as(a.id(), () -> store.currentCount(Set.of("ready")))).isEqualTo(1);
        assertThat(as(a.id(), () -> store.currentCount(Set.of("failed")))).isZero();
        // A material counts once whatever its versions, and never for another owner.
        assertThat(as(a.id(), () -> store.currentCount(Materials.STATES))).isEqualTo(5);
        assertThat(as(b.id(), () -> store.currentCount(Materials.STATES))).isEqualTo(1);
        assertThat(as(a.id(), () -> materials.find(created.get(0))).orElseThrow().state()).isEqualTo("processing");
    }

    // --- Story 3.3: where a material belongs

    @Test
    void aMoveAndASeveralSystemsAttachRoundTripAndTheVersionRisesWithEach() throws Exception {
        Owner a = owner();
        UUID second = as(a.id(), () -> library.createSystem(new Library.SystemFields("Pathfinder", null, null))).id();
        UUID material = material(a.id(), as(a.id(), () -> uploads.create(10, "notas.md",
                new Uploads.Details(null, null, a.campaign().toString(), (String) null))));
        long points = jdbc.sql("select count(*) from job where owner_id = ?").param(a.id()).query(Long.class).single();

        Materials.Material moved = as(a.id(), () -> belongings.change(material, 0, null,
                List.of(a.system().toString(), second.toString()))).orElseThrow();

        assertThat(moved.version()).isEqualTo(1);
        assertThat(moved.belonging().campaign()).isNull();
        assertThat(moved.belonging().systems()).extracting(Materials.Place::name)
                .containsExactlyInAnyOrder("Untitled system", "Pathfinder");
        Map<String, Object> row = jdbc.sql("select campaign_id, version from material where id = ?").param(material)
                .query().singleRow();
        assertThat(row.get("campaign_id")).isNull();
        assertThat(row.get("version")).isEqualTo(1L);
        assertThat(jdbc.sql("select system_id from material_system where material_id = ?").param(material)
                .query(UUID.class).list()).containsExactlyInAnyOrder(a.system(), second);
        UUID keptRow = jdbc.sql("select id from material_system where material_id = ? and system_id = ?")
                .param(material).param(second).query(UUID.class).single();

        // One system goes, one stays as it was; then back to the campaign, which takes every system away.
        as(a.id(), () -> belongings.change(material, 1, null, List.of(second.toString())));
        assertThat(jdbc.sql("select id from material_system where material_id = ?").param(material)
                .query(UUID.class).list()).containsExactly(keptRow);
        Materials.Material back = as(a.id(), () -> belongings.change(material, 2, a.campaign().toString(), null))
                .orElseThrow();
        assertThat(back.version()).isEqualTo(3);
        assertThat(back.belonging().campaign().id()).isEqualTo(a.campaign());
        assertThat(back.belonging().campaign().system().id()).isEqualTo(a.system());
        assertThat(jdbc.sql("select count(*) from material_system where material_id = ?").param(material)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select campaign_id from material where id = ?").param(material).query(UUID.class)
                .single()).isEqualTo(a.campaign());
        // Nothing but the material's place was written: no job (no index change, no removal).
        assertThat(jdbc.sql("select count(*) from job where owner_id = ?").param(a.id()).query(Long.class).single())
                .isEqualTo(points);
        // A page of the list names every place.
        assertThat(as(a.id(), () -> materials.list(null, null, 10)).items()).singleElement()
                .satisfies(item -> assertThat(item.belonging().campaign().name()).isEqualTo("First campaign"));
    }

    @Test
    void aStaleVersionIsRefusedAndTwoChangesNamingOneVersionAtOnceLetOnlyOneThrough() throws Exception {
        Owner a = owner();
        UUID material = material(a.id(), as(a.id(), () -> uploads.create(10, "notas.md",
                new Uploads.Details(null, null, a.campaign().toString(), (String) null))));
        as(a.id(), () -> belongings.change(material, 0, null, List.of(a.system().toString())));

        assertThatThrownBy(() -> as(a.id(), () -> belongings.change(material, 0, a.campaign().toString(), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).problem().code())
                        .isEqualTo("request.stale_version"));
        assertThat(jdbc.sql("select version from material where id = ?").param(material).query(Long.class).single())
                .isEqualTo(1L);

        // Two changes on version 1 at once: the material's lock lets one write, and the other is stale.
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<String>> outcomes = new ArrayList<>();
            for (String target : List.of(a.campaign().toString(), "systems")) {
                outcomes.add(pool.submit(() -> {
                    try {
                        as(a.id(), () -> target.equals("systems")
                                ? belongings.change(material, 1, null, List.of(a.system().toString()))
                                : belongings.change(material, 1, target, null));
                        return "changed";
                    } catch (ApiException refused) {
                        return refused.problem().code();
                    }
                }));
            }
            List<String> results = new ArrayList<>();
            for (java.util.concurrent.Future<String> outcome : outcomes) {
                results.add(outcome.get());
            }
            assertThat(results).containsExactlyInAnyOrder("changed", "request.stale_version");
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("select version from material where id = ?").param(material).query(Long.class).single())
                .isEqualTo(2L);
    }

    // --- Story 3.4: a second version

    @Test
    void aReplacementIsASecondVersionAndTheCurrentOneIsTheNewestThatIsNotDiscarded() throws Exception {
        Owner a = owner();
        Owner b = owner();
        UUID material = material(a.id(), as(a.id(), () -> uploads.create(10, "Livro.pdf",
                new Uploads.Details("rulebook", null, a.campaign().toString(), (String) null))));
        UUID first = as(a.id(), () -> versionsOf(material)).getFirst().id();
        as(a.id(), () -> new TransactionTemplate(transactions).execute(status ->
                store.changeVersion(first, "ready", "pdf", 10L, Instant.now().truncatedTo(ChronoUnit.MICROS))));

        // The real replacement flow: a new version of the same material, which keeps its row.
        Uploads.Created created = as(a.id(), () -> uploads.create(20, "novo.pdf",
                Uploads.Details.replacing(material.toString())));

        assertThat(material(a.id(), created)).isEqualTo(material);
        assertThat(jdbc.sql("select count(*) from material where owner_id = ?").param(a.id()).query(Long.class)
                .single()).isOne();
        List<VersionRow> versions = as(a.id(), () -> versionsOf(material));
        assertThat(versions).extracting(VersionRow::state).containsExactly("ready", "uploading");
        UUID second = versions.get(1).id();
        Materials.Material uploading = as(a.id(), () -> materials.find(material)).orElseThrow();
        assertThat(uploading.state()).isEqualTo("uploading");
        assertThat(uploading.upload().length()).isEqualTo(20);
        assertThat(uploading.serving()).isEqualTo(new Materials.Serving(null, 10L));
        assertThat(uploading.belonging().campaign().id()).isEqualTo(a.campaign());
        // A second one, at once or later, waits for it; another owner's is not found.
        assertThat(refusal(a.id(), () -> uploads.create(20, "x.pdf", Uploads.Details.replacing(material.toString()))))
                .isEqualTo("material.version_in_progress");
        assertThat(refusal(b.id(), () -> uploads.create(20, "x.pdf", Uploads.Details.replacing(material.toString()))))
                .isEqualTo("request.not_found");

        // A discarded newest version is not the current one.
        as(a.id(), () -> new TransactionTemplate(transactions).execute(status ->
                store.changeVersion(second, "discarded", null, null, Instant.now().truncatedTo(ChronoUnit.MICROS))));
        assertThat(as(a.id(), () -> store.current(material)).orElseThrow().version().id()).isEqualTo(first);
        assertThat(as(a.id(), () -> store.currentIn(Set.of("ready"), null, 10))).extracting(found -> found.version().id())
                .containsExactly(first);
        assertThat(as(a.id(), () -> store.currentIn(Set.of("discarded"), null, 10))).isEmpty();
        assertThat(as(a.id(), () -> store.currentAttached(Set.of("ready"), a.system(), List.of(a.campaign()), null,
                10))).extracting(found -> found.version().id()).containsExactly(first);
        assertThat(as(a.id(), () -> materials.find(material)).orElseThrow().serving()).isNull();

        // Two versions created at the same instant: the greater id is the newer, in the query and in the read.
        VersionRow ready = as(a.id(), () -> store.findVersion(first)).orElseThrow();
        UUID twin = new UUID(first.getMostSignificantBits(), first.getLeastSignificantBits() + 1);
        as(a.id(), () -> new TransactionTemplate(transactions).execute(status -> {
            store.insertVersion(new VersionRow(twin, a.id(), material, "waiting", "pdf", 10L, ready.createdAt(),
                    ready.createdAt()));
            return null;
        }));
        assertThat(as(a.id(), () -> store.current(material)).orElseThrow().version().id()).isEqualTo(twin);
        assertThat(as(a.id(), () -> store.currentIn(Set.of("waiting"), null, 10))).extracting(found -> found.version().id())
                .containsExactly(twin);
        assertThat(as(a.id(), () -> store.currentIn(Set.of("ready"), null, 10))).isEmpty();

        // Every version discarded: the newest one, and no replacement.
        as(a.id(), () -> new TransactionTemplate(transactions).execute(status -> {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            store.changeVersion(first, "discarded", null, null, now);
            return store.changeVersion(twin, "discarded", null, null, now);
        }));
        assertThat(as(a.id(), () -> store.current(material)).orElseThrow().version().id()).isEqualTo(second);
        assertThat(as(a.id(), () -> store.currentIn(Set.of("discarded"), null, 10))).extracting(found -> found.version().id())
                .containsExactly(second);
        assertThat(refusal(a.id(), () -> uploads.create(20, "x.pdf", Uploads.Details.replacing(material.toString()))))
                .isEqualTo("material.not_ready");
    }

    @Test
    void twoReplacementsOfOneMaterialAtOnceLetOnlyOneThrough() throws Exception {
        Owner a = owner();
        UUID material = material(a.id(), as(a.id(), () -> uploads.create(10, "Livro.pdf")));
        UUID first = as(a.id(), () -> versionsOf(material)).getFirst().id();
        as(a.id(), () -> new TransactionTemplate(transactions).execute(status ->
                store.changeVersion(first, "ready", "pdf", 10L, Instant.now().truncatedTo(ChronoUnit.MICROS))));

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<String>> outcomes = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                outcomes.add(pool.submit(() -> {
                    String refused = refusal(a.id(), () -> uploads.create(20, "novo.pdf",
                            Uploads.Details.replacing(material.toString())));
                    return refused == null ? "created" : refused;
                }));
            }
            List<String> results = new ArrayList<>();
            for (java.util.concurrent.Future<String> outcome : outcomes) {
                results.add(outcome.get());
            }
            assertThat(results).containsExactlyInAnyOrder("created", "material.version_in_progress");
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("select count(*) from material_version where material_id = ?").param(material)
                .query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void aReprocessOfAReadyMaterialWaitsForTheMaterialAReplacementHoldsAndIsThenRefused() throws Exception {
        Owner a = owner();
        UUID material = material(a.id(), as(a.id(), () -> uploads.create(10, "Livro.pdf")));
        UUID first = as(a.id(), () -> versionsOf(material)).getFirst().id();
        as(a.id(), () -> new TransactionTemplate(transactions).execute(status ->
                store.changeVersion(first, "ready", "pdf", 10L, Instant.now().truncatedTo(ChronoUnit.MICROS))));
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            // A replacement holds the material's lock, its version inserted but not committed.
            java.util.concurrent.Future<String> replacement = pool.submit(() -> {
                as(a.id(), () -> new TransactionTemplate(transactions).execute(status -> {
                    uploads.insertReplacement(a.id(), material, Ids.newId(), Instant.now().truncatedTo(ChronoUnit.MICROS));
                    locked.countDown();
                    try {
                        release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                }));
                return "created";
            });
            assertThat(locked.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // The reprocess still reads the ready version as current, then waits for the material's lock.
            java.util.concurrent.Future<String> reprocess = pool.submit(() -> {
                String refused = refusal(a.id(), () -> actions.act(material, Materials.REPROCESS));
                return refused == null ? "reprocessed" : refused;
            });
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while (jdbc.sql("select count(*) from pg_stat_activity where wait_event_type = 'Lock'").query(Long.class)
                    .single() == 0) {
                assertThat(System.nanoTime()).as("the reprocess waits for the material").isLessThan(deadline);
                Thread.sleep(20);
            }
            release.countDown();

            assertThat(replacement.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("created");
            assertThat(reprocess.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("material.version_in_progress");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("select state from material_version where material_id = ? order by created_at, id")
                .param(material).query(String.class).list()).containsExactly("ready", "uploading");
    }

    /** The code {@code work} is refused with, or null when it goes through. */
    private static String refusal(UUID owner, Callable<?> work) {
        try {
            as(owner, work);
            return null;
        } catch (ApiException refused) {
            return refused.problem().code();
        } catch (Exception other) {
            throw new IllegalStateException(other);
        }
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

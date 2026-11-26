package io.github.gmshield.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.knowledge.data.KnowledgeStore;
import io.github.gmshield.knowledge.data.RecordCorrection;
import io.github.gmshield.knowledge.data.RecordStore;
import io.github.gmshield.knowledge.data.RecordTypeRow;
import io.github.gmshield.knowledge.service.CoreRecordTypes;
import io.github.gmshield.knowledge.service.CoreTypeBackfill;
import io.github.gmshield.knowledge.service.Ontology;
import io.github.gmshield.knowledge.service.Proposals;
import io.github.gmshield.knowledge.service.Records;
import io.github.gmshield.library.service.Language;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.MaterialSystemRow;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.materials.service.ExtractedRecords;
import io.github.gmshield.materials.service.MaterialActions;
import io.github.gmshield.materials.service.RecordIndexing;
import io.github.gmshield.materials.service.RecordTypeKeys;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 4.1 against a real PostgreSQL and Redis (test mode): V34 and the core vocabulary. A new system (the starter one
 * and the GM's) has the eight core record types from the transaction that creates it, and loses them when it rolls
 * back; the name column holds a vault ciphertext, never the name; the fill at start gives a system from before V34 its
 * core types, only the missing ones, once, and leaves a system being deleted alone, and it runs on the application's
 * ready event; the table refuses an unknown core
 * key, a second core type of a key, a name the system has and another owner's system; a core type is neither renamed
 * nor removed, a type that is not core is renamed on its version and removed; another owner reads nothing; and a
 * system's deletion removes its record types before its row. Story 4.5: a correction is stored in place, encrypted, with
 * its version, time and extracted match key, and a records run of its page; and the acceptance of a version reprocessing
 * made takes the corrections over (a matched record, and a copy on the same page).
 *
 * <p>The raw rows are read here with SQL to prove what is stored; the application never reads a library table with
 * native SQL. The properties are {@code SystemDeletionIntegrationTest}'s, so the two share one application context.
 */
@SpringBootTest(properties = {"GMSHIELD_MODE=test", "gmshield.jobs.sweep.enabled=false",
    "gmshield.materials.upload-sweep.enabled=false",
    "gmshield.materials.version-removal-sweep.enabled=false",
    "gmshield.library.deletion-sweep.enabled=false",
    "gmshield.materials.index-retry-sweep.enabled=false",
    "gmshield.files.dir=${java.io.tmpdir}/gmshield-deletion-it"})
@ActiveProfiles("test-mode")
class OntologyIntegrationTest {

    @Autowired
    private Library library;
    @Autowired
    private Ontology ontology;
    @Autowired
    private CoreTypeBackfill backfill;
    @Autowired
    private KnowledgeStore store;
    @Autowired
    private SystemDeletion deletion;
    @Autowired
    private Vault vault;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private ConfigurableApplicationContext context;

    /** An owner with the starter system, as a first sign-in makes it. */
    private record Owner(UUID id, UUID system) {
    }

    @Test
    void aNewSystemHasTheEightCoreTypesStoredEncryptedOnPostgres() throws Exception {
        Owner a = owner();
        UUID created = as(a.id(), () -> library.createSystem(new Library.SystemFields("Tormenta", "20", null))).id();

        for (UUID system : List.of(a.system(), created)) {
            List<Map<String, Object>> rows = jdbc.sql("select id, owner_id, core, name, name_fingerprint, version"
                    + " from record_type where system_id = ? order by core").param(system).query().listOfRows();
            assertThat(rows).hasSize(8);
            assertThat(rows).extracting(row -> row.get("core")).containsExactlyInAnyOrderElementsOf(CoreRecordTypes.KEYS);
            for (Map<String, Object> row : rows) {
                String name = CoreRecordTypes.name((String) row.get("core"));
                byte[] stored = (byte[]) row.get("name");
                assertThat(row.get("owner_id")).isEqualTo(a.id());
                assertThat(row.get("version")).isEqualTo(0L);
                // The name column holds no plaintext, in any common encoding.
                assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(name);
                assertThat(new String(stored, StandardCharsets.UTF_16BE)).doesNotContain(name);
                assertThat(vault.decryptText(new Binding("record_type", "name", (UUID) row.get("id"), a.id()), stored))
                        .isEqualTo(name);
                assertThat((byte[]) row.get("name_fingerprint"))
                        .isEqualTo(vault.fingerprint("record_type", "name", Library.uniqueForm(name)));
            }
            Ontology.RecordTypePage page = as(a.id(), () -> ontology.recordTypes(system, 0, 50)).orElseThrow();
            assertThat(page.total()).isEqualTo(8);
            assertThat(page.items()).extracting(Ontology.RecordType::core).containsExactlyElementsOf(CoreRecordTypes.KEYS);
            assertThat(page.items()).extracting(Ontology.RecordType::records).containsOnly(0L);
            assertThat(as(a.id(), () -> ontology.recordTypes(system, 6, 3)).orElseThrow().items())
                    .extracting(Ontology.RecordType::core).containsExactly("dungeon", "note");
        }
    }

    @Test
    void whenTheStarterLibraryRollsBackItsCoreTypesGoWithIt() {
        UUID owner = Ids.newId();

        assertThatThrownBy(() -> as(owner, () -> {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                library.createStarter(Language.EN);
                // Inside the transaction the eight are there, beside the system.
                assertThat(count("record_type", "owner_id = ?", owner)).isEqualTo(8);
                throw new IllegalStateException("the account failed");
            });
            return null;
        })).hasMessage("the account failed");

        assertThat(count("system", "owner_id = ?", owner)).isZero();
        assertThat(count("record_type", "owner_id = ?", owner)).isZero();
    }

    // --- the fill at start

    @Test
    void theFillGivesAnOldSystemItsMissingCoreTypesOnceAndLeavesADeletingOneAlone() throws Exception {
        UUID owner = Ids.newId();
        UUID old = oldSystem(owner, "Antigo");
        UUID partial = oldSystem(owner, "Parcial");
        UUID going = oldSystem(owner, "Indo");
        jdbc.sql("update system set state = 'deleting' where id = ?").param(going).update();
        assertThat(as(owner, () -> ontology.fillCore(partial))).isEqualTo(8);
        jdbc.sql("delete from record_type where system_id = ? and core in ('spell', 'note')").param(partial).update();
        List<UUID> kept = ids(partial);

        assertThat(backfill.run()).isGreaterThanOrEqualTo(2);

        assertThat(cores(old)).containsExactlyInAnyOrderElementsOf(CoreRecordTypes.KEYS);
        assertThat(cores(partial)).containsExactlyInAnyOrderElementsOf(CoreRecordTypes.KEYS);
        assertThat(ids(partial)).containsAll(kept);
        assertThat(count("record_type", "system_id = ?", going)).isZero();
        assertThat(count("record_type", "system_id = ? and owner_id <> ?", old, owner)).isZero();
        List<UUID> before = ids(old);

        backfill.run();
        assertThat(as(owner, () -> ontology.fillCore(old))).isZero();

        assertThat(ids(old)).isEqualTo(before);
        // Every ready system of the owner now has the eight: nothing of theirs is listed for the next start.
        assertThat(jdbc.sql("select count(*) from system s where s.owner_id = ? and s.state = 'ready' and (select count(*)"
                + " from record_type r where r.owner_id = s.owner_id and r.system_id = s.id and r.core is not null) < 8")
                .param(owner).query(Long.class).single()).isZero();
    }

    @Test
    void theFillRunsWhenTheApplicationIsReady() {
        UUID owner = Ids.newId();
        UUID old = oldSystem(owner, "Pronto");

        context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));

        assertThat(cores(old)).containsExactlyInAnyOrderElementsOf(CoreRecordTypes.KEYS);
    }

    // --- what the table refuses

    @Test
    void theTableRefusesAnUnknownCoreKeyASecondCoreTypeATakenNameAndAnotherOwnersSystem() throws Exception {
        Owner a = owner();
        Owner b = owner();

        assertThatThrownBy(() -> insert(a.id(), a.system(), "weapon", "Weapon"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> write(a.id(), () -> store.insertRecordTypes(List.of(row(a.id(), a.system(), "spell",
                "Magia")))))
                .hasMessageContaining("knowledge.core_taken");
        assertThatThrownBy(() -> write(a.id(), () -> store.insertRecordTypes(List.of(row(a.id(), a.system(), null,
                " SPELL ")))))
                .hasMessageContaining("knowledge.name_taken");
        // A record type names its system with its owner: another owner's system is refused by the key.
        assertThatThrownBy(() -> insert(a.id(), b.system(), null, "Veículo"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("record_type", "system_id = ?", a.system())).isEqualTo(8);
        assertThat(count("record_type", "system_id = ?", b.system())).isEqualTo(8);
    }

    // --- rename and removal

    @Test
    void aCoreTypeStaysWhileATypeThatIsNotCoreIsRenamedOnItsVersionAndRemoved() throws Exception {
        Owner a = owner();
        Owner b = owner();
        UUID spell = as(a.id(), () -> ontology.recordTypes(a.system(), 1, 1)).orElseThrow().items().getFirst().id();
        RecordTypeRow vehicle = row(a.id(), a.system(), null, "Veículo");
        write(a.id(), () -> store.insertRecordTypes(List.of(vehicle)));

        assertThat(code(() -> as(a.id(), () -> ontology.rename(spell, 0, "Magia")))).isEqualTo("ontology.core_record_type");
        assertThat(code(() -> as(a.id(), () -> ontology.delete(spell)))).isEqualTo("ontology.core_record_type");
        assertThat(count("record_type", "id = ? and version = 0", spell)).isOne();

        Ontology.RecordType renamed = as(a.id(), () -> ontology.rename(vehicle.id(), 0, "Montaria")).orElseThrow();
        assertThat(renamed.version()).isEqualTo(1);
        assertThat(as(a.id(), () -> ontology.recordType(vehicle.id())).orElseThrow().name()).isEqualTo("Montaria");
        assertThat(code(() -> as(a.id(), () -> ontology.rename(vehicle.id(), 0, "Corcel"))))
                .isEqualTo("request.stale_version");
        assertThat(code(() -> as(a.id(), () -> ontology.rename(vehicle.id(), 1, "Character sheet"))))
                .isEqualTo("ontology.record_type_name_taken");
        byte[] stored = jdbc.sql("select name from record_type where id = ?").param(vehicle.id()).query(byte[].class)
                .single();
        assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("Montaria");

        // Another owner: nothing is read, renamed or removed.
        assertThat(as(b.id(), () -> ontology.recordType(vehicle.id()))).isEmpty();
        assertThat(as(b.id(), () -> ontology.recordTypes(a.system(), 0, 50))).isEmpty();
        assertThat(as(b.id(), () -> ontology.rename(vehicle.id(), 1, "Tomado"))).isEmpty();
        assertThat(as(b.id(), () -> ontology.delete(vehicle.id()))).isFalse();
        assertThat(count("record_type", "id = ? and version = 1", vehicle.id())).isOne();

        assertThat(as(a.id(), () -> ontology.delete(vehicle.id()))).isTrue();
        assertThat(count("record_type", "id = ?", vehicle.id())).isZero();
        assertThat(count("record_type", "system_id = ?", a.system())).isEqualTo(8);
    }

    // --- Story 4.4: a core row the release before V39 wrote, with no key

    @Autowired
    private Proposals proposals;
    @Autowired
    private RecordTypeKeys recordTypeKeys;
    @Autowired
    private Records records;
    @Autowired
    private MaterialStore materials;
    @Autowired
    private ExtractedRecords extracted;

    @Test
    void aCoreRowWithoutAKeyReadsWithItsCoreKeyAndNoApprovedTypeTakesThatKey() throws Exception {
        Owner a = owner();
        UUID system = oldSystem(a.id(), "Antigo");
        UUID spell = Ids.newId();
        jdbc.sql("insert into record_type (id, owner_id, system_id, core, name, name_fingerprint) values (?, ?, ?, ?, ?,"
                + " ?)").params(spell, a.id(), system, "spell", vault.encryptText(new Binding("record_type", "name", spell,
                a.id()), "spell"), vault.fingerprint("record_type", "name", "spell")).update();

        Ontology.RecordType read = as(a.id(), () -> ontology.recordTypes(system, 0, 50)).orElseThrow().items().getFirst();
        assertThat(read.core()).isEqualTo("spell");
        assertThat(read.key()).isEqualTo("spell");
        assertThat(read.origin()).isNull();
        // Story 4.4 part B: its key is its core key wherever keys are read, and a spell record of the system opens.
        assertThat(as(a.id(), () -> recordTypeKeys.keysOf(system))).contains("spell").doesNotContainNull();
        UUID record = spellRecord(a.id(), system);
        Records.Detail detail = as(a.id(), () -> records.record(system, record)).orElseThrow();
        assertThat(detail.recordType().id()).isEqualTo(spell);
        assertThat(detail.recordType().key()).isEqualTo("spell");
        assertThat(detail.recordType().core()).isEqualTo("spell");

        // A pending proposal approved as "Spell!": its slug is the core key, so it takes the next one.
        UUID proposal = Ids.newId();
        jdbc.sql("insert into proposal (id, owner_id, system_id, kind, name, name_fingerprint, examples, state,"
                + " created_at) values (?, ?, ?, 'record_type', ?, ?, ?, 'pending', now())")
                .params(proposal, a.id(), system, vault.encryptText(new Binding("proposal", "name", proposal, a.id()),
                        "Spell!"), vault.fingerprint("record_type", "name", Library.uniqueForm("Spell!")),
                        vault.encrypt(new Binding("proposal", "examples", proposal, a.id()),
                                "[]".getBytes(StandardCharsets.UTF_8))).update();
        UUID approved = as(a.id(), () -> proposals.approve(proposal, 0, null)).orElseThrow().resultId();
        assertThat(jdbc.sql("select key from record_type where id = ?").param(approved).query(String.class).single())
                .isEqualTo("spell_2");
    }

    // --- Story 4.5: correct a record, and carry corrections over to a reprocessed version

    @Autowired
    private MaterialActions actions;

    @Test
    void aCorrectionIsStoredInPlaceEncryptedAndAReprocessedVersionTakesItOverAtAcceptanceOnPostgres() throws Exception {
        Owner a = owner();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID material = Ids.newId();
        UUID first = Ids.newId();
        List<UUID> oldPassages = List.of(Ids.newId(), Ids.newId());
        write(a.id(), () -> {
            materials.insertMaterial(new MaterialRow(material, a.id(), vault.encryptText(new Binding("material", "name",
                    material, a.id()), "Livro"), now, "rulebook", null, null));
            materials.insertMaterialSystem(new MaterialSystemRow(Ids.newId(), a.id(), material, a.system(), now));
            materials.insertVersion(new VersionRow(first, a.id(), material, "ready", "pdf", 1000L, now, now));
            materials.insertPassages(List.of(passage(a.id(), first, oldPassages.get(0), 0, 1, "Magias"),
                    passage(a.id(), first, oldPassages.get(1), 1, 2, "Magias")));
            extracted.replaceRange(first, 1, 2, List.of(
                    new ExtractedRecords.Extracted("spell", "Luz", List.of(), List.of(oldPassages.get(0)), 1),
                    new ExtractedRecords.Extracted("spell", "Sombra", List.of(), List.of(oldPassages.get(1)), 2)));
        });
        UUID light = recordNamed(first, a.id(), "Luz");
        UUID shadow = recordNamed(first, a.id(), "Sombra");

        Records.Detail corrected = as(a.id(), () -> records.correct(a.system(), light, new Records.Correction(0,
                "Luz Divina", "spell", List.of(new Records.Field("Alcance", "Toque"))))).orElseThrow();
        as(a.id(), () -> records.correct(a.system(), shadow, new Records.Correction(0, "Sombra Viva", "spell",
                List.of())));

        assertThat(corrected.version()).isEqualTo(1);
        Map<String, Object> row = jdbc.sql("select version, corrected_at, type, extracted_type, name,"
                + " name_fingerprint, extracted_name_fingerprint, first_page from record where id = ?").param(light)
                .query().singleRow();
        assertThat(row.get("version")).isEqualTo(1L);
        assertThat(row.get("corrected_at")).isNotNull();
        assertThat(row.get("extracted_type")).isEqualTo("spell");
        assertThat((byte[]) row.get("extracted_name_fingerprint"))
                .isEqualTo(vault.fingerprint("record", "name", Library.uniqueForm("Luz")));
        assertThat((byte[]) row.get("name_fingerprint"))
                .isEqualTo(vault.fingerprint("record", "name", Library.uniqueForm("Luz Divina")));
        assertThat(new String((byte[]) row.get("name"), StandardCharsets.ISO_8859_1)).doesNotContain("Luz Divina");
        assertThat(vault.decryptText(new Binding("record", "name", light, a.id()), (byte[]) row.get("name")))
                .isEqualTo("Luz Divina");
        assertThat(count("record_passage", "record_id = ? and passage_id = ?", light, oldPassages.get(0))).isOne();
        // A records run of each corrected record's first page joined the version's set.
        assertThat(jdbc.sql("select first_ordinal from index_change where version_id = ? and records"
                + " and first_ordinal = last_ordinal order by first_ordinal").param(first).query(Integer.class).list())
                .containsExactly(1, 2);
        // A stale version is refused and changes nothing.
        assertThat(code(() -> as(a.id(), () -> records.correct(a.system(), light, new Records.Correction(0,
                "Outra", "spell", List.of()))))).isEqualTo("request.stale_version");
        assertThat(count("record", "id = ? and version = 1", light)).isOne();
        // A second correction on the current version keeps the extracted key of the first.
        assertThat(as(a.id(), () -> records.correct(a.system(), light, new Records.Correction(1, "Luz Divina", "spell",
                List.of(new Records.Field("Alcance", "Toque"), new Records.Field("Duração", "1 hora"))))).orElseThrow()
                .version()).isEqualTo(2);
        Map<String, Object> again = jdbc.sql("select version, extracted_type, extracted_name_fingerprint from record"
                + " where id = ?").param(light).query().singleRow();
        assertThat(again.get("version")).isEqualTo(2L);
        assertThat(again.get("extracted_type")).isEqualTo("spell");
        assertThat((byte[]) again.get("extracted_name_fingerprint"))
                .isEqualTo(vault.fingerprint("record", "name", Library.uniqueForm("Luz")));

        // A version reprocessing made from it, in review: "Luz" read again on page 1, nothing on page 2's "Sombra".
        UUID second = Ids.newId();
        List<UUID> newPassages = List.of(Ids.newId(), Ids.newId());
        write(a.id(), () -> {
            materials.insertVersion(new VersionRow(second, a.id(), material, "needs_review", "pdf", 1000L, now, now),
                    first);
            materials.insertPassages(List.of(passage(a.id(), second, newPassages.get(0), 0, 1, "Magias"),
                    passage(a.id(), second, newPassages.get(1), 1, 2, "Outro")));
            extracted.replaceRange(second, 1, 2, List.of(new ExtractedRecords.Extracted("spell", "Luz", List.of(),
                    List.of(newPassages.get(0)), 1)));
        });
        assertThat(jdbc.sql("select reprocessed_from from material_version where id = ?").param(second)
                .query(UUID.class).single()).isEqualTo(first);

        as(a.id(), () -> actions.act(material, "accept"));

        UUID matched = recordNamed(second, a.id(), "Luz Divina");
        UUID copied = recordNamed(second, a.id(), "Sombra Viva");
        assertThat(jdbc.sql("select extracted_type from record where id = ?").param(matched).query(String.class)
                .single()).isEqualTo("spell");
        assertThat(jdbc.sql("select passage_id from record_passage where record_id = ?").param(matched)
                .query(UUID.class).list()).containsExactly(newPassages.get(0));
        assertThat(jdbc.sql("select passage_id from record_passage where record_id = ?").param(copied)
                .query(UUID.class).list()).containsExactly(newPassages.get(1));
        assertThat(jdbc.sql("select first_page from record where id = ?").param(copied).query(Integer.class)
                .single()).isEqualTo(2);
        assertThat(as(a.id(), () -> records.record(a.system(), matched)).orElseThrow().fields())
                .extracting(Records.Field::name).containsExactly("Alcance", "Duração");
        // Both keep the corrected record's time and extracted key.
        for (Object[] carried : List.of(new Object[] {matched, "Luz"}, new Object[] {copied, "Sombra"})) {
            Map<String, Object> stored = jdbc.sql("select corrected_at, extracted_type, extracted_name_fingerprint"
                    + " from record where id = ?").param(carried[0]).query().singleRow();
            assertThat(stored.get("corrected_at")).as("%s", carried[1]).isNotNull();
            assertThat(stored.get("extracted_type")).as("%s", carried[1]).isEqualTo("spell");
            assertThat((byte[]) stored.get("extracted_name_fingerprint")).as("%s", carried[1])
                    .isEqualTo(vault.fingerprint("record", "name", Library.uniqueForm((String) carried[1])));
        }
        // The replaced version's records stay.
        assertThat(count("record", "version_id = ?", first)).isEqualTo(2);
        assertThat(count("record", "version_id = ?", second)).isEqualTo(2);
    }

    @Autowired
    private RecordIndexing recordIndexing;
    @Autowired
    private RecordStore recordStore;

    @Test
    void twoOverlappingCorrectionsOnOneVersionStoreOneAndRefuseTheOtherAsStaleOnPostgres() throws Exception {
        Owner a = owner();
        UUID record = spellRecord(a.id(), a.system());
        UUID version = jdbc.sql("select version_id from record where id = ?").param(record).query(UUID.class).single();
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // The first correction holds the version's lock and its write, not committed yet.
            Future<Long> first = pool.submit(() -> as(a.id(), () -> new TransactionTemplate(transactions).execute(
                    status -> {
                        assertThat(recordIndexing.lockReady(version)).isTrue();
                        long stored = recordStore.correct(record, 0, new RecordCorrection("spell",
                                vault.encryptText(new Binding("record", "name", record, a.id()), "Luz Primeira"),
                                vault.fingerprint("record", "name", Library.uniqueForm("Luz Primeira")),
                                vault.encrypt(new Binding("record", "fields", record, a.id()),
                                        "[]".getBytes(StandardCharsets.UTF_8)), Instant.now()))
                                .orElseThrow().version();
                        written.countDown();
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException stopped) {
                            Thread.currentThread().interrupt();
                        }
                        return stored;
                    })));
            assertThat(written.await(30, TimeUnit.SECONDS)).isTrue();
            // The second reads the record at version 0, then waits for the version's lock.
            Future<String> second = pool.submit(() -> code(() -> as(a.id(), () -> records.correct(a.system(), record,
                    new Records.Correction(0, "Luz Segunda", "spell", List.of())))));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (jdbc.sql("select count(*) from pg_stat_activity where wait_event_type = 'Lock'").query(Long.class)
                    .single() == 0) {
                assertThat(System.nanoTime()).as("the second correction waits for the version").isLessThan(deadline);
                Thread.sleep(20);
            }
            release.countDown();

            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(1L);
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo("request.stale_version");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        Map<String, Object> row = jdbc.sql("select version, name from record where id = ?").param(record).query()
                .singleRow();
        assertThat(row.get("version")).isEqualTo(1L);
        assertThat(vault.decryptText(new Binding("record", "name", record, a.id()), (byte[]) row.get("name")))
                .isEqualTo("Luz Primeira");
        // Only the stored correction added a records run of the page.
        assertThat(count("index_change", "version_id = ? and records and first_ordinal = 1 and last_ordinal = 1",
                version)).isZero();
    }

    private PassageRow passage(UUID owner, UUID version, UUID id, int ordinal, int page, String section) {
        byte[] place = ("{\"sectionPath\":[\"" + section + "\"],\"range\":[0,4]}").getBytes(StandardCharsets.UTF_8);
        return new PassageRow(id, owner, version, ordinal, page, vault.encryptText(new Binding("passage", "text", id,
                owner), "Texto."), vault.encrypt(new Binding("passage", "place", id, owner), place),
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /** The id of the version's record with this name, its name decrypted. */
    private UUID recordNamed(UUID version, UUID owner, String name) {
        return jdbc.sql("select id, name from record where version_id = ?").param(version).query().listOfRows()
                .stream().filter(row -> name.equals(vault.decryptText(new Binding("record", "name",
                        (UUID) row.get("id"), owner), (byte[]) row.get("name"))))
                .map(row -> (UUID) row.get("id")).findFirst().orElseThrow();
    }

    // --- deletion

    @Test
    void aDeletedSystemsRecordTypesGoBeforeItsRow() throws Exception {
        Owner a = owner();
        UUID system = as(a.id(), () -> library.createSystem(new Library.SystemFields("Passageiro", null, null))).id();
        write(a.id(), () -> store.insertRecordTypes(List.of(row(a.id(), system, null, "Veículo"))));
        // Story 4.4: a term with an alias, each naming its proposal; they go first, the proposals last.
        UUID proposal = approvedProposal(a.id(), system, "Pontos de Vida");
        UUID term = Ids.newId();
        jdbc.sql("insert into term (id, owner_id, system_id, name, name_fingerprint, definition, proposal_id,"
                + " created_at) values (?, ?, ?, ?, ?, ?, ?, now())").params(term, a.id(), system, new byte[] {1},
                new byte[] {1}, new byte[] {1}, proposal).update();
        jdbc.sql("insert into term_alias (id, owner_id, system_id, term_id, name, name_fingerprint, proposal_id,"
                + " created_at) values (?, ?, ?, ?, ?, ?, ?, now())").params(Ids.newId(), a.id(), system, term,
                new byte[] {2}, new byte[] {2}, proposal).update();

        assertThat(as(a.id(), () -> deletion.delete(system))).isTrue();
        assertThat(as(a.id(), () -> ontology.recordTypes(system, 0, 50))).isEmpty();
        UUID deletionId = jdbc.sql("select id from deletion where target_id = ?").param(system).query(UUID.class)
                .single();

        assertThat(deletion.run(a.id(), deletionId)).isTrue();

        assertThat(count("system", "id = ?", system)).isZero();
        assertThat(count("record_type", "system_id = ?", system)).isZero();
        assertThat(count("term_alias", "system_id = ?", system)).isZero();
        assertThat(count("term", "system_id = ?", system)).isZero();
        assertThat(count("proposal", "system_id = ?", system)).isZero();
        assertThat(count("record_type", "system_id = ?", a.system())).isEqualTo(8);
    }

    // --- helpers

    private Owner owner() throws Exception {
        UUID id = Ids.newId();
        return as(id, () -> {
            new TransactionTemplate(transactions).executeWithoutResult(status -> library.createStarter(Language.PT_BR));
            return new Owner(id, library.currentCampaign().orElseThrow().system().id());
        });
    }

    /** A system as V34 found it: ready, with no record types. */
    private UUID oldSystem(UUID owner, String name) {
        UUID id = Ids.newId();
        jdbc.sql("insert into system (id, owner_id, name, name_fingerprint) values (?, ?, ?, ?)")
                .params(id, owner, vault.encryptText(new Binding("system", "name", id, owner), name),
                        vault.fingerprint("system", "name", Library.uniqueForm(name))).update();
        return id;
    }

    /**
     * A record type row: a core one, or (from Story 4.4) one that is not core, approved from a proposal stored here with
     * SQL, as an approval leaves it.
     */
    private RecordTypeRow row(UUID owner, UUID system, String core, String name) {
        UUID id = Ids.newId();
        UUID proposal = core == null ? approvedProposal(owner, system, name) : null;
        return new RecordTypeRow(id, owner, system, core, core == null ? "t" + id.toString().substring(0, 8) : core,
                proposal, vault.encryptText(new Binding("record_type", "name", id, owner), name),
                vault.fingerprint("record_type", "name", Library.uniqueForm(name)), 0,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /** An approved record type proposal of the system, with no material (Story 4.4): its id. */
    private UUID approvedProposal(UUID owner, UUID system, String name) {
        UUID id = Ids.newId();
        jdbc.sql("insert into proposal (id, owner_id, system_id, kind, name, name_fingerprint, examples, state,"
                + " decided_at, created_at) values (?, ?, ?, 'record_type', ?, ?, ?, 'approved', now(), now())")
                .params(id, owner, system, vault.encryptText(new Binding("proposal", "name", id, owner), name),
                        vault.fingerprint("record_type", "name", Library.uniqueForm(name)),
                        vault.encrypt(new Binding("proposal", "examples", id, owner),
                                "[]".getBytes(StandardCharsets.UTF_8))).update();
        return id;
    }

    /**
     * A ready material of {@code system} with one passage and one spell record read from it (Story 4.4 part B): the
     * record's id.
     */
    private UUID spellRecord(UUID owner, UUID system) throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID material = Ids.newId();
        UUID version = Ids.newId();
        UUID passage = Ids.newId();
        write(owner, () -> {
            materials.insertMaterial(new MaterialRow(material, owner, vault.encryptText(new Binding("material", "name",
                    material, owner), "Livro"), now, "rulebook", null, null));
            materials.insertMaterialSystem(new MaterialSystemRow(Ids.newId(), owner, material, system, now));
            materials.insertVersion(new VersionRow(version, owner, material, "ready", "pdf", 1000L, now, now));
            byte[] place = "{\"sectionPath\":[\"Magias\"],\"range\":[0,4]}".getBytes(StandardCharsets.UTF_8);
            materials.insertPassages(List.of(new PassageRow(passage, owner, version, 0, 1,
                    vault.encryptText(new Binding("passage", "text", passage, owner), "Luz."),
                    vault.encrypt(new Binding("passage", "place", passage, owner), place), now)));
            extracted.replaceRange(version, 1, 1, List.of(new ExtractedRecords.Extracted("spell", "Luz", List.of(),
                    List.of(passage), 1)));
        });
        return jdbc.sql("select id from record where version_id = ?").param(version).query(UUID.class).single();
    }

    /** A raw insert, past the application, to prove what the table itself refuses. */
    private void insert(UUID owner, UUID system, String core, String name) {
        RecordTypeRow row = row(owner, system, core, name);
        jdbc.sql("insert into record_type (id, owner_id, system_id, core, name, name_fingerprint) values (?, ?, ?, ?, ?, ?)")
                .params(row.id(), owner, system, core, row.name(), row.nameFingerprint()).update();
    }

    private List<String> cores(UUID system) {
        return jdbc.sql("select core from record_type where system_id = ?").param(system).query(String.class).list();
    }

    private List<UUID> ids(UUID system) {
        return jdbc.sql("select id from record_type where system_id = ? order by id").param(system).query(UUID.class)
                .list();
    }

    private long count(String table, String where, Object... params) {
        return jdbc.sql("select count(*) from " + table + " where " + where).params(params).query(Long.class).single();
    }

    private static String code(Callable<?> work) {
        try {
            work.call();
        } catch (ApiException refused) {
            return refused.problem().code();
        } catch (Exception other) {
            throw new AssertionError(other);
        }
        throw new AssertionError("not refused");
    }

    private void write(UUID owner, Runnable work) throws Exception {
        as(owner, () -> new TransactionTemplate(transactions).execute(status -> {
            work.run();
            return null;
        }));
    }

    private static <T> T as(UUID owner, Callable<T> work) throws Exception {
        return OwnerContext.callAs(owner, work::call);
    }
}

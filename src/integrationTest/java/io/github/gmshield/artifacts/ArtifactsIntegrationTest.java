package io.github.gmshield.artifacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.GmshieldApplication;
import io.github.gmshield.artifacts.data.ArtifactIndexStore;
import io.github.gmshield.artifacts.data.ArtifactShareStore;
import io.github.gmshield.artifacts.data.ArtifactVersionRow;
import io.github.gmshield.artifacts.data.ArtifactVersionStore;
import io.github.gmshield.artifacts.data.StaleArtifacts;
import io.github.gmshield.artifacts.data.VersionTaken;
import io.github.gmshield.artifacts.service.ArtifactIndexReconcile;
import io.github.gmshield.artifacts.service.ArtifactIndexRetrySweep;
import io.github.gmshield.artifacts.service.Artifacts;
import io.github.gmshield.jobs.service.JobClaims;
import io.github.gmshield.jobs.service.JobFiles;
import io.github.gmshield.jobs.service.JobRecord;
import io.github.gmshield.jobs.service.JobRecordReads;
import io.github.gmshield.conversations.data.TurnReferenceRow;
import io.github.gmshield.conversations.data.TurnReferenceStore;
import io.github.gmshield.conversations.service.Conversations;
import io.github.gmshield.conversations.service.TurnReference;
import io.github.gmshield.knowledge.data.ArtifactRow;
import io.github.gmshield.knowledge.data.KnowledgeStore;
import io.github.gmshield.knowledge.data.RecordStore;
import io.github.gmshield.knowledge.data.RecordTypeRow;
import io.github.gmshield.knowledge.service.Ontology;
import io.github.gmshield.knowledge.service.RecordRelations;
import io.github.gmshield.knowledge.service.Records;
import io.github.gmshield.library.service.Language;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.scope.service.CampaignArtifacts;
import io.github.gmshield.scope.service.ScopeSettings;
import io.github.gmshield.scope.service.Scopes;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.MaterialSystemRow;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.materials.service.ExtractedRecords;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story 5.1 part A against a real PostgreSQL and Redis (test mode): V43 and an artifact written by hand. The artifact
 * round-trips through {@link Artifacts} (created, listed newest first with its types, opened), and a list pages by
 * offset and, within a type, by cursor with the type's total; a new application context
 * started after the save (a restart) reads it back as written; its {@code record} row has its campaign, its type key and
 * no version, place, name or fields, and its {@code record_version} row holds only ciphertexts bound to the artifact; a
 * type the system lacks stores nothing; the table refuses a record with both or neither of a version and a campaign,
 * an artifact of another owner's campaign, an artifact with a name, and a version of another owner's record. With an
 * artifact in the table, no read of a system's records answers it (every {@link RecordStore} read by id, the record
 * type's list and count, both record pages and the port the answers and searches read through), an approved type
 * that an artifact uses cannot be deleted, and a campaign's deletion removes it, its versions first.
 *
 * <p>Story 5.1 part B (V44): a save commits its {@code index.artifact} job and {@code artifact_index} row with it, and a
 * rollback leaves neither; the row's keys hold its record and version within the owner; the backfill's reads (the owners
 * across the table, the owner's newest versions not indexed under a recipe) and the version's lock work on PostgreSQL;
 * an artifact's {@code turn_reference} round-trips by value (no material column, its version kept) and the table's check
 * refuses a row of both kinds; a campaign's deletion cancels the artifacts' index jobs, removes their rows before the
 * versions and records one {@code index.artifact_removal} per artifact.
 *
 * <p>Story 5.2 part A (V45): V45's statement fills a version's type from its record and the column is then required;
 * an edit round-trips (version 2 stored only encrypted beside version 1, the identity retyped, version 2's index job
 * committed and version 1's cancelled), and a restore and the versions page (by offset and cursor) work on PostgreSQL;
 * a number the record already has is the store's {@code VersionTaken}, and two edits of the same version store one and
 * refuse the other as stale.
 *
 * <p>Story 5.2 part B (V46): the kind check takes {@code artifact}; a delete marks the row deleting, records the
 * deletion and cancels the artifact's open index job in one transaction, and the pass removes its index rows, versions
 * and identity with one {@code index.artifact_removal}; every live read leaves a deleting artifact out while the all
 * reads still answer it, and a campaign's deletion takes it.
 *
 * <p>Story 5.5 part A (V50): an override is one {@code record_relation} row; the table refuses a second mark of the
 * same artifact and another kind; a second artifact of the campaign is refused naming the first; an answer's records
 * read carries the marks on both sides on PostgreSQL; and the row goes with the artifact's row (its deletion's pass) and
 * with the record's row (its version's removal).
 *
 * <p>Story 5.5 part B (V51): a relation is one {@code record_relation} row of kind {@code related}, read from both
 * sides; relating the other way stores nothing; the table keeps one override per artifact beside many relations and
 * refuses a second link of a kind between the same records in the same direction; and the row goes with either
 * artifact's row and with the record's row.
 *
 * <p>Story 5.6 part A (V52): a share is one {@code artifact_share} row per pair, of the owner; the table refuses a
 * second row for a pair and a share across owners; the port, the scope and the read in the target campaign answer it on
 * PostgreSQL, never to another owner; the target campaign's key has no cascade, so its deletion's pass removes the shares
 * into it; and the artifact's key cascades.
 *
 * <p>Story 5.6 part B: a campaign's list holds the artifacts shared into it beside its own, by id in SQL, with the type
 * filter, the cursor and the offset, counted in its total and types; the other owner's store reads answer none of
 * them, and {@code ArtifactShareStore#ofAll} reads the shares of many artifacts on PostgreSQL.
 *
 * <p>The raw rows are read here with SQL to prove what is stored; the application never reads a library table with
 * native SQL. The properties are {@code SystemDeletionIntegrationTest}'s, so the classes share one application context.
 */
@SpringBootTest(properties = {"GMSHIELD_MODE=test", "gmshield.jobs.sweep.enabled=false",
    "gmshield.materials.upload-sweep.enabled=false",
    "gmshield.materials.version-removal-sweep.enabled=false",
    "gmshield.library.deletion-sweep.enabled=false",
    "gmshield.materials.index-retry-sweep.enabled=false",
    "gmshield.files.dir=${java.io.tmpdir}/gmshield-deletion-it"})
@ActiveProfiles("test-mode")
class ArtifactsIntegrationTest {

    private static final String KAEL = "Espada de Kael";

    @Autowired
    private Library library;
    @Autowired
    private Artifacts artifacts;
    @Autowired
    private RecordStore records;
    @Autowired
    private Records systemRecords;
    @Autowired
    private Ontology ontology;
    @Autowired
    private KnowledgeStore types;
    @Autowired
    private ExtractedRecords extracted;
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
    @Autowired
    private ArtifactIndexStore index;
    @Autowired
    private ArtifactVersionStore versions;
    @Autowired
    private StaleArtifacts stale;
    @Autowired
    private Conversations conversations;
    @Autowired
    private TurnReferenceStore turnReferences;
    @Autowired
    private JobClaims claims;
    @Autowired
    private JobFiles jobFiles;
    @Autowired
    private JobRecordReads recordReads;
    @Autowired
    private ArtifactIndexRetrySweep retrySweep;
    @Autowired
    private ArtifactIndexReconcile reconcile;
    @Autowired
    private CampaignArtifacts campaignArtifacts;
    @Autowired
    private MaterialStore materials;
    @Autowired
    private Scopes scopes;
    @Autowired
    private ArtifactShareStore shares;

    /** An owner with the starter system and campaign, as a first sign-in makes them. */
    private record Owner(UUID id, UUID system, UUID campaign) {
    }

    @Test
    void anArtifactRoundTripsIsStoredEncryptedAndReadsBackInANewContext() throws Exception {
        Owner a = owner();
        Artifacts.Artifact created = as(a.id(), () -> artifacts.create(a.campaign(), kael())).orElseThrow();
        Artifacts.Artifact note = as(a.id(), () -> artifacts.create(a.campaign(), new Artifacts.Written("note",
                "Lembrete", List.of(), null))).orElseThrow();

        assertThat(created.version()).isEqualTo(1);
        assertThat(as(a.id(), () -> artifacts.get(a.campaign(), created.id()))).contains(created);
        Artifacts.Page page = as(a.id(), () -> artifacts.list(a.campaign(), null, null, 0, 20)).orElseThrow();
        assertThat(page.items()).extracting(Artifacts.Summary::id).containsExactly(note.id(), created.id());
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.types()).containsExactly(new Artifacts.TypeCount("item", 1), new Artifacts.TypeCount("note", 1));
        Artifacts.Page items = as(a.id(), () -> artifacts.list(a.campaign(), "item", null, 0, 1)).orElseThrow();
        assertThat(items.items()).extracting(Artifacts.Summary::name).containsExactly(KAEL);
        assertThat(items.nextCursor()).isNull();
        Artifacts.Page after = as(a.id(), () -> artifacts.list(a.campaign(), null, note.id(), 0, 20)).orElseThrow();
        assertThat(after.items()).extracting(Artifacts.Summary::id).containsExactly(created.id());

        // The identity row: the campaign and the type key, no version, place, name or fields.
        Map<String, Object> row = jdbc.sql("select owner_id, campaign_id, version_id, ordinal, first_page, type, name,"
                + " fields from record where id = ?").param(created.id()).query().singleRow();
        assertThat(row).containsEntry("owner_id", a.id()).containsEntry("campaign_id", a.campaign())
                .containsEntry("type", "item");
        assertThat(row.get("version_id")).isNull();
        assertThat(row.get("ordinal")).isNull();
        assertThat(row.get("first_page")).isNull();
        assertThat(row.get("name")).isNull();
        assertThat(row.get("fields")).isNull();
        // Version 1: ciphertexts only, each bound to its field, the artifact and the owner.
        Map<String, Object> version = jdbc.sql("select owner_id, number, name, fields, content from record_version"
                + " where record_id = ?").param(created.id()).query().singleRow();
        assertThat(version).containsEntry("owner_id", a.id()).containsEntry("number", 1);
        for (String column : List.of("name", "fields", "content")) {
            String stored = new String((byte[]) version.get(column), StandardCharsets.ISO_8859_1);
            assertThat(stored).doesNotContain("Kael").doesNotContain("cortante").doesNotContain("montanhas");
        }
        assertThat(vault.decryptText(new Binding("record_version", "name", created.id(), a.id()),
                (byte[]) version.get("name"))).isEqualTo(KAEL);
        assertThatThrownBy(() -> vault.decryptText(new Binding("record_version", "content", created.id(), a.id()),
                (byte[]) version.get("name"))).isNotNull();
        assertThat(jdbc.sql("select content from record_version where record_id = ?").param(note.id())
                .query().singleRow().get("content")).isNull();

        // A restart: a new application context on the same database reads it back as written. Test mode's vault keys
        // are random for each start and held in memory only, so the new context is handed this one's, as a production
        // restart reads the same configured keys again.
        Object keys = context.getBean("randomVaultKeys");
        try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(GmshieldApplication.class)
                .web(WebApplicationType.NONE).profiles("test-mode")
                .initializers(fresh -> fresh.addBeanFactoryPostProcessor(factory -> {
                    ((BeanDefinitionRegistry) factory).removeBeanDefinition("randomVaultKeys");
                    factory.registerSingleton("randomVaultKeys", keys);
                }))
                .properties("GMSHIELD_MODE=test", "gmshield.jobs.sweep.enabled=false",
                        "gmshield.materials.upload-sweep.enabled=false",
                        "gmshield.materials.version-removal-sweep.enabled=false",
                        "gmshield.library.deletion-sweep.enabled=false",
                        "gmshield.materials.index-retry-sweep.enabled=false",
                        "gmshield.files.dir=${java.io.tmpdir}/gmshield-deletion-it",
                        "spring.datasource.hikari.maximum-pool-size=2")
                .run()) {
            Artifacts again = restarted.getBean(Artifacts.class);
            assertThat(as(a.id(), () -> again.get(a.campaign(), created.id()))).contains(created);
            assertThat(as(a.id(), () -> again.list(a.campaign(), null, null, 0, 20)).orElseThrow().items())
                    .extracting(Artifacts.Summary::id).containsExactly(note.id(), created.id());
        }
    }

    @Test
    void theListPagesByOffsetAndByCursorWithinATypeWithItsTotalOnPostgres() throws Exception {
        Owner a = owner();
        List<UUID> made = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String type = i % 2 == 0 ? "item" : "note";
            int number = i;
            made.add(as(a.id(), () -> artifacts.create(a.campaign(), new Artifacts.Written(type, "Artefato " + number,
                    List.of(), null))).orElseThrow().id());
        }
        // Newest first: 4, 3, 2, 1, 0; the items are 4, 2 and 0.
        Artifacts.Page skipped = as(a.id(), () -> artifacts.list(a.campaign(), null, null, 2, 2)).orElseThrow();
        assertThat(skipped.items()).extracting(Artifacts.Summary::id).containsExactly(made.get(2), made.get(1));
        assertThat(skipped.total()).isEqualTo(5);
        assertThat(skipped.nextCursor()).isEqualTo(made.get(1));

        Artifacts.Page items = as(a.id(), () -> artifacts.list(a.campaign(), "item", null, 0, 2)).orElseThrow();
        assertThat(items.items()).extracting(Artifacts.Summary::id).containsExactly(made.get(4), made.get(2));
        assertThat(items.total()).isEqualTo(3);
        assertThat(items.nextCursor()).isEqualTo(made.get(2));
        Artifacts.Page after = as(a.id(), () -> artifacts.list(a.campaign(), "item", items.nextCursor(), 0, 2))
                .orElseThrow();
        assertThat(after.items()).extracting(Artifacts.Summary::id).containsExactly(made.get(0));
        assertThat(after.total()).isEqualTo(3);
        assertThat(after.nextCursor()).isNull();
        assertThat(after.types()).containsExactly(new Artifacts.TypeCount("item", 3), new Artifacts.TypeCount("note", 2));
    }

    @Test
    void aTypeTheSystemLacksStoresNothingAndAnotherOwnerReadsNothing() throws Exception {
        Owner a = owner();
        Owner b = owner();

        assertThatThrownBy(() -> as(a.id(), () -> artifacts.create(a.campaign(), new Artifacts.Written("weapon", KAEL,
                List.of(), null)))).isInstanceOf(ApiException.class)
                .satisfies(refused -> assertThat(((ApiException) refused).problem().params())
                        .containsEntry("field", "recordType"));
        assertThatThrownBy(() -> as(a.id(), () -> artifacts.create(a.campaign(), new Artifacts.Written("item", KAEL,
                List.of(), "a".repeat(20_001))))).isInstanceOf(ApiException.class);
        assertThat(count("record", "campaign_id = ?", a.campaign())).isZero();
        assertThat(count("record_version", "owner_id = ?", a.id())).isZero();

        Artifacts.Artifact created = as(a.id(), () -> artifacts.create(a.campaign(), kael())).orElseThrow();
        assertThat(as(b.id(), () -> artifacts.get(a.campaign(), created.id()))).isEmpty();
        assertThat(as(b.id(), () -> artifacts.list(a.campaign(), null, null, 0, 20))).isEmpty();
        assertThat(as(b.id(), () -> artifacts.create(a.campaign(), kael()))).isEmpty();
        assertThat(as(b.id(), () -> artifacts.get(b.campaign(), created.id()))).isEmpty();
        assertThat(as(b.id(), () -> records.findArtifact(a.campaign(), created.id()))).isEmpty();
        assertThat(count("record", "owner_id = ? and campaign_id is not null", b.id())).isZero();
    }

    @Test
    void theTableRefusesAMixedRecordAnotherOwnersCampaignAnArtifactWithANameAndAnotherOwnersVersion()
            throws Exception {
        Owner a = owner();
        Owner b = owner();
        UUID artifact = as(a.id(), () -> artifacts.create(a.campaign(), kael())).orElseThrow().id();

        // Neither a version nor a campaign, and an artifact with a name: the checks refuse both.
        assertThatThrownBy(() -> jdbc.sql("insert into record (id, owner_id, type, created_at) values (?, ?, 'item',"
                + " now())").params(Ids.newId(), a.id()).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("insert into record (id, owner_id, campaign_id, type, name, created_at)"
                + " values (?, ?, ?, 'item', ?, now())").params(Ids.newId(), a.id(), a.campaign(), new byte[] {1})
                .update()).isInstanceOf(DataIntegrityViolationException.class);
        // Another owner's campaign.
        assertThatThrownBy(() -> jdbc.sql("insert into record (id, owner_id, campaign_id, type, created_at) values"
                + " (?, ?, ?, 'item', now())").params(Ids.newId(), b.id(), a.campaign()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        // A version of another owner's record, and a second version 1.
        assertThatThrownBy(() -> jdbc.sql("insert into record_version (record_id, owner_id, number, record_type, name,"
                + " fields, created_at) values (?, ?, 2, 'item', ?, ?, now())")
                .params(artifact, b.id(), new byte[] {1}, new byte[] {1}).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("insert into record_version (record_id, owner_id, number, record_type, name,"
                + " fields, created_at) values (?, ?, 1, 'item', ?, ?, now())")
                .params(artifact, a.id(), new byte[] {1}, new byte[] {1}).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        // Story 5.2 (V45): a version without a type, or with a type that is not a key's text.
        assertThatThrownBy(() -> jdbc.sql("insert into record_version (record_id, owner_id, number, name, fields,"
                + " created_at) values (?, ?, 2, ?, ?, now())").params(artifact, a.id(), new byte[] {1}, new byte[] {1})
                .update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("insert into record_version (record_id, owner_id, number, record_type, name,"
                + " fields, created_at) values (?, ?, 2, 'Item!', ?, ?, now())")
                .params(artifact, a.id(), new byte[] {1}, new byte[] {1}).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void noReadOfTheSystemsRecordsAnswersAnArtifact() throws Exception {
        Owner a = owner();
        UUID artifact = as(a.id(), () -> artifacts.create(a.campaign(), kael())).orElseThrow().id();
        List<UUID> ids = List.of(artifact);

        as(a.id(), () -> new TransactionTemplate(transactions).execute(status -> {
            assertThat(records.find(artifact)).isEmpty();
            assertThat(records.versionOf(artifact)).isEmpty();
            assertThat(records.findAll(ids, RecordStore.Reach.ALL)).isEmpty();
            assertThat(records.findAll(ids, RecordStore.Reach.LIVE)).isEmpty();
            assertThat(records.passagesOf(artifact)).isEmpty();
            assertThat(records.delete(artifact, Instant.now(), true)).isFalse();
            assertThat(records.pointsRemoved(ids, Instant.now())).isZero();
            assertThat(records.correct(artifact, 0, null)).isEmpty();
            return null;
        }));
        as(a.id(), () -> {
            assertThat(systemRecords.record(a.system(), artifact)).isEmpty();
            assertThat(systemRecords.inCampaign(a.campaign(), artifact)).isEmpty();
            assertThat(extracted.located(ids, ExtractedRecords.Reach.ALL)).isEmpty();
            assertThat(extracted.read(ids, ExtractedRecords.Reach.ALL)).isEmpty();
            assertThat(extracted.names(ids)).isEmpty();
            assertThat(extracted.deleted(ids)).isEmpty();
            Ontology.RecordType item = ontology.recordTypes(a.system(), 0, 50).orElseThrow().items().stream()
                    .filter(type -> "item".equals(type.core())).findFirst().orElseThrow();
            assertThat(item.records()).isZero();
            assertThat(systemRecords.records(item.id(), 0, 50).orElseThrow().total()).isZero();
            return null;
        });
        // The row is there all along: only the artifact reads answer it.
        assertThat(as(a.id(), () -> artifacts.get(a.campaign(), artifact))).isPresent();
    }

    @Test
    void aCampaignsDeletionRemovesItsArtifactsVersionsFirst() throws Exception {
        Owner a = owner();
        UUID other = as(a.id(), () -> library.createCampaign(a.system(), "Outra mesa")).orElseThrow().id();
        UUID going = as(a.id(), () -> artifacts.create(other, kael())).orElseThrow().id();
        UUID staying = as(a.id(), () -> artifacts.create(a.campaign(), kael())).orElseThrow().id();

        assertThat(as(a.id(), () -> deletion.deleteCampaign(other))).isTrue();
        assertThat(as(a.id(), () -> artifacts.get(other, going))).isEmpty();
        assertThat(deletion.sweep()).isGreaterThanOrEqualTo(1);

        assertThat(count("campaign", "id = ?", other)).isZero();
        assertThat(count("record", "id = ?", going)).isZero();
        assertThat(count("record_version", "record_id = ?", going)).isZero();
        assertThat(count("record", "id = ?", staying)).isOne();
        assertThat(count("record_version", "record_id = ?", staying)).isOne();
        // Story 5.1 part B: its index job cancelled, its row gone before the versions, one removal by owner and source.
        assertThat(count("artifact_index", "record_id = ?", going)).isZero();
        assertThat(count("artifact_index", "record_id = ?", staying)).isOne();
        assertThat(jdbc.sql("select j.state from job j join artifact_index i on i.job_id = j.id where i.record_id = ?")
                .param(staying).query(String.class).single()).isIn("waiting", "offered");
        Map<String, Object> removal = jdbc.sql("select r.owner_id, j.kind from artifact_index_removal r join job j"
                + " on j.id = r.job_id where r.artifact_id = ?").param(going).query().singleRow();
        assertThat(removal).containsEntry("owner_id", a.id()).containsEntry("kind", "index.artifact_removal");
        assertThat(jdbc.sql("select count(*) from job where owner_id = ? and kind = 'index.artifact' and state ="
                + " 'cancelled'").param(a.id()).query(Long.class).single()).isOne();
    }

    // --- Story 5.1 part B

    @Test
    void aSaveCommitsItsIndexJobWithItARollbackLeavesNeitherAndTheBackfillReadsWork() throws Exception {
        Owner a = owner();
        UUID created = as(a.id(), () -> artifacts.create(a.campaign(), kael())).orElseThrow().id();

        Map<String, Object> row = jdbc.sql("select job_id, owner_id, number, recipe, retries from artifact_index"
                + " where record_id = ?").param(created).query().singleRow();
        assertThat(row).containsEntry("owner_id", a.id()).containsEntry("number", 1);
        assertThat(row.get("recipe")).isNull();
        assertThat(((Number) row.get("retries")).intValue()).isZero();
        UUID job = (UUID) row.get("job_id");
        assertThat(jdbc.sql("select kind, lane from job where id = ?").param(job).query().singleRow())
                .containsEntry("kind", "index.artifact").containsEntry("lane", "ingestion");

        // A rollback of the save's transaction leaves no artifact, no version, no index row and no job.
        long jobs = count("job", "owner_id = ?", a.id());
        as(a.id(), () -> new TransactionTemplate(transactions).execute(status -> {
            assertThat(artifacts.create(a.campaign(), new Artifacts.Written("note", "Rascunho", List.of(), null)))
                    .isPresent();
            status.setRollbackOnly();
            return null;
        }));
        assertThat(count("record", "campaign_id = ?", a.campaign())).isOne();
        assertThat(count("record_version", "owner_id = ?", a.id())).isOne();
        assertThat(count("artifact_index", "owner_id = ?", a.id())).isOne();
        assertThat(count("job", "owner_id = ?", a.id())).isEqualTo(jobs);

        // The backfill's reads: the owner is stale under a recipe no row reports, and its version is listed.
        assertThat(stale.owners("2")).contains(a.id());
        as(a.id(), () -> new TransactionTemplate(transactions).execute(status -> {
            assertThat(versions.lock(created, 1)).isPresent();
            assertThat(index.notIndexedUnder("2")).contains(new ArtifactIndexStore.VersionKey(created, 1));
            assertThat(index.recordRecipe(job, "2")).isTrue();
            assertThat(index.notIndexedUnder("2")).doesNotContain(new ArtifactIndexStore.VersionKey(created, 1));
            assertThat(index.notIndexedUnder("3")).contains(new ArtifactIndexStore.VersionKey(created, 1));
            return null;
        }));
        assertThat(stale.owners("2")).doesNotContain(a.id());
        assertThat(stale.owners("3")).contains(a.id());

        // The row's keys: its record and version, within the owner.
        Owner b = owner();
        assertThatThrownBy(() -> jdbc.sql("insert into artifact_index (job_id, owner_id, record_id, number,"
                + " created_at) values (?, ?, ?, 2, now())").params(Ids.newId(), a.id(), created).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("insert into artifact_index (job_id, owner_id, record_id, number,"
                + " created_at) values (?, ?, ?, 1, now())").params(Ids.newId(), b.id(), created).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anArtifactsReferenceRoundTripsByValueAndTheCheckRefusesAReferenceOfBothKinds() throws Exception {
        Owner a = owner();
        UUID artifact = as(a.id(), () -> artifacts.create(a.campaign(), kael())).orElseThrow().id();
        UUID turn = as(a.id(), () -> conversations.ask(a.campaign(), "Quanto dano?")).turnId();
        UUID rowId = Ids.newId();

        as(a.id(), () -> new TransactionTemplate(transactions).execute(status -> {
            turnReferences.insertReferences(List.of(TurnReferenceRow.artifact(rowId, a.id(), turn, 1,
                    Instant.now().truncatedTo(ChronoUnit.MICROS), "campaign", new TurnReferenceRow.Recorded(artifact,
                            "item", vault.encryptText(new Binding("turn_reference", "record_name", rowId, a.id()), KAEL),
                            vault.encrypt(new Binding("turn_reference", "record_fields", rowId, a.id()),
                                    "[{\"name\":\"Dano\",\"value\":\"1d8\"}]".getBytes(StandardCharsets.UTF_8)), 1))));
            return null;
        }));

        Map<String, Object> stored = jdbc.sql("select version_id, material_id, page, passage_ids, material_name,"
                + " section_path, record_id, artifact_id, artifact_version, record_type from turn_reference where id = ?")
                .param(rowId).query().singleRow();
        assertThat(stored).containsEntry("artifact_id", artifact).containsEntry("artifact_version", 1)
                .containsEntry("record_type", "item");
        for (String column : List.of("version_id", "material_id", "page", "passage_ids", "material_name",
                "section_path", "record_id")) {
            assertThat(stored.get(column)).as(column).isNull();
        }
        List<TurnReference> read = as(a.id(), () -> conversations.references(turn));
        assertThat(read).singleElement().satisfies(reference -> {
            assertThat(reference.kind()).isEqualTo(TurnReference.ARTIFACT);
            assertThat(reference.record().id()).isEqualTo(artifact);
            assertThat(reference.record().artifactVersion()).isEqualTo(1);
            assertThat(reference.record().name()).isEqualTo(KAEL);
            assertThat(reference.record().fields()).extracting(TurnReference.Field::name).containsExactly("Dano");
            assertThat(reference.removed()).isFalse();
            assertThat(reference.tier()).isEqualTo("campaign");
        });
        // A row that is an artifact's and names a material version too is refused.
        assertThatThrownBy(() -> jdbc.sql("insert into turn_reference (id, owner_id, turn_id, number, version_id,"
                + " material_id, page, passage_ids, material_name, section_path, created_at, artifact_id,"
                + " artifact_version, record_type, record_name, record_fields) values (?, ?, ?, 2, ?, ?, 1,"
                + " array[?]::uuid[], ?, ?, now(), ?, 1, 'item', ?, ?)").params(Ids.newId(), a.id(), turn, Ids.newId(),
                Ids.newId(), Ids.newId(), new byte[] {1}, new byte[] {1}, artifact, new byte[] {1}, new byte[] {1})
                .update()).isInstanceOf(DataIntegrityViolationException.class);
        // And an artifact's without its version.
        assertThatThrownBy(() -> jdbc.sql("insert into turn_reference (id, owner_id, turn_id, number, created_at,"
                + " artifact_id, record_type, record_name, record_fields) values (?, ?, ?, 3, now(), ?, 'item', ?, ?)")
                .params(Ids.newId(), a.id(), turn, artifact, new byte[] {1}, new byte[] {1}).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anApprovedTypeThatAnArtifactUsesIsInUseOnPostgres() throws Exception {
        Owner a = owner();
        UUID used = approvedType(a, "arma_magica", "Arma mágica");
        UUID unused = approvedType(a, "pocao_rara", "Poção rara");
        as(a.id(), () -> artifacts.create(a.campaign(), new Artifacts.Written("arma_magica", "Arco élfico", List.of(),
                null))).orElseThrow();

        assertThatThrownBy(() -> as(a.id(), () -> ontology.delete(used))).isInstanceOf(ApiException.class)
                .satisfies(refused -> assertThat(((ApiException) refused).problem().code())
                        .isEqualTo("ontology.record_type_in_use"));
        assertThat(count("record_type", "id = ?", used)).isOne();
        assertThat(as(a.id(), () -> ontology.delete(unused))).isTrue();
        assertThat(count("record_type", "id = ?", unused)).isZero();
    }

    @Test
    void anArtifactsIndexJobReadsItsLineOnPostgresAndAnAnswerReadsTheArtifactAsACampaignRecord() throws Exception {
        freeTheIngestionLane();
        Owner a = owner();
        UUID created = as(a.id(), () -> artifacts.create(a.campaign(), kael())).orElseThrow().id();
        UUID job = jdbc.sql("select job_id from artifact_index where record_id = ?").param(created).query(UUID.class)
                .single();

        // The worker's claim: a records run's input, and its one line read from PostgreSQL, the content last.
        JobClaims.Claim claim = claims.claim(job);
        assertThat(claim.input().path("source").asString()).isEqualTo(created.toString());
        assertThat(claim.input().path("kind").asString()).isEqualTo("artifact");
        assertThat(claim.input().path("language").isNull()).isTrue();
        assertThat(claim.input().path("records").asString()).isEqualTo("records");
        ByteArrayOutputStream read = new ByteArrayOutputStream();
        jobFiles.open(job, claim.claimId(), claim.grant(), "records").input().writeTo(read);
        JsonNode line = JsonMapper.builder().build().readTree(read.toString(StandardCharsets.UTF_8).strip());
        assertThat(line.path("id").asString()).isEqualTo(created.toString());
        assertThat(line.path("page").asInt()).isEqualTo(1);
        assertThat(line.path("type").asString()).isEqualTo("item");
        assertThat(line.path("name").asString()).isEqualTo(KAEL);
        assertThat(line.path("fields").toString()).isEqualTo("[{\"name\":\"Dano\",\"value\":\"1d8 cortante\"},"
                + "{\"name\":\"Peso\",\"value\":\"1,5 kg\"},{\"name\":\"content\",\"value\":"
                + "\"Forjada nas montanhas do norte.\"}]");
        claims.report(job, claim.claimId(), claim.grant(), "done", null,
                JsonMapper.builder().build().readTree("{\"indexRecipe\":\"it-a1\",\"points\":1}"));
        assertThat(jdbc.sql("select recipe from artifact_index where job_id = ?").param(job).query(String.class)
                .single()).isEqualTo("it-a1");

        // An answer of the campaign lists it among the campaign tier's sources and reads it as a campaign record.
        UUID turn = as(a.id(), () -> conversations.ask(a.campaign(), "O que faz a Espada de Kael?")).turnId();
        UUID answerJob = jdbc.sql("select job_id from turn where id = ?").param(turn).query(UUID.class).single();
        JobClaims.Claim answer = claims.claim(answerJob, null, List.of("azure-openai"));
        assertThat(answer.input().path("tiers").get(0).path("sources").toString()).contains(created.toString());
        List<JobRecord> records = recordReads.read(answerJob, answer.claimId(), answer.grant(), List.of(created));
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.tier()).isEqualTo("campaign");
            assertThat(record.type()).isEqualTo("item");
            assertThat(record.name()).isEqualTo(KAEL);
            assertThat(record.page()).isEqualTo(1);
            assertThat(record.sectionPath()).isEmpty();
            assertThat(record.fields()).extracting(JobRecord.Field::name).containsExactly("Dano", "Peso", "content");
        });
        freeTheIngestionLane();
    }

    @Test
    void failedIndexAndRemovalJobsMoveToNewJobsAndANewRecipeIndexesAgainOnPostgres() throws Exception {
        freeTheIngestionLane();
        Owner a = owner();
        UUID other = as(a.id(), () -> library.createCampaign(a.system(), "Outra mesa")).orElseThrow().id();
        UUID going = as(a.id(), () -> artifacts.create(other, kael())).orElseThrow().id();
        UUID staying = as(a.id(), () -> artifacts.create(a.campaign(), kael())).orElseThrow().id();
        assertThat(as(a.id(), () -> deletion.deleteCampaign(other))).isTrue();
        deletion.sweep();
        UUID failedIndex = jdbc.sql("select job_id from artifact_index where record_id = ?").param(staying)
                .query(UUID.class).single();
        UUID failedRemoval = jdbc.sql("select job_id from artifact_index_removal where artifact_id = ?").param(going)
                .query(UUID.class).single();
        jdbc.sql("update job set state = 'failed', outcome_code = 'index.store_unavailable' where id in (?, ?)")
                .params(failedIndex, failedRemoval).update();
        // Other tests' failed artifact jobs are left out, so the sweep's count is this owner's alone.
        jdbc.sql("update job set state = 'cancelled' where kind in ('index.artifact', 'index.artifact_removal')"
                + " and state = 'failed' and owner_id <> ?").param(a.id()).update();

        assertThat(retrySweep.sweep()).isEqualTo(2);

        Map<String, Object> index = jdbc.sql("select i.job_id, i.recipe, i.retries, j.kind from artifact_index i join"
                + " job j on j.id = i.job_id where i.record_id = ?").param(staying).query().singleRow();
        assertThat(index.get("job_id")).isNotEqualTo(failedIndex);
        assertThat(index).containsEntry("kind", "index.artifact").containsEntry("recipe", null);
        assertThat(((Number) index.get("retries")).intValue()).isOne();
        Map<String, Object> removal = jdbc.sql("select r.job_id, r.owner_id, j.kind from artifact_index_removal r join"
                + " job j on j.id = r.job_id where r.artifact_id = ?").param(going).query().singleRow();
        assertThat(removal.get("job_id")).isNotEqualTo(failedRemoval);
        assertThat(removal).containsEntry("owner_id", a.id()).containsEntry("kind", "index.artifact_removal");
        assertThat(jdbc.sql("select state from job where id = ?").param(failedRemoval).query(String.class).single())
                .isEqualTo("failed");
        // A row at 3 retries whose job failed is not retried again.
        UUID retried = (UUID) index.get("job_id");
        jdbc.sql("update artifact_index set retries = 3 where job_id = ?").param(retried).update();
        jdbc.sql("update job set state = 'failed' where id = ?").param(retried).update();
        jdbc.sql("update job set state = 'cancelled' where kind = 'index.artifact_removal' and state in ('waiting',"
                + " 'offered', 'failed')").update();
        assertThat(retrySweep.sweep()).isZero();

        // Indexed under one recipe, the version is indexed again under a new one: its row replaced by a new job's.
        jdbc.sql("update artifact_index set recipe = 'it-r1' where job_id = ?").param(retried).update();
        jdbc.sql("update job set state = 'done' where id = ?").param(retried).update();
        assertThat(reconcile.reconcile("it-r1")).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.sql("select job_id from artifact_index where record_id = ?").param(staying).query(UUID.class)
                .single()).isEqualTo(retried);
        assertThat(reconcile.reconcile("it-r2")).isGreaterThanOrEqualTo(1);
        Map<String, Object> again = jdbc.sql("select i.job_id, i.recipe, i.retries, j.kind, j.state from artifact_index i"
                + " join job j on j.id = i.job_id where i.record_id = ?").param(staying).query().singleRow();
        assertThat(again.get("job_id")).isNotEqualTo(retried);
        assertThat(again).containsEntry("kind", "index.artifact").containsEntry("recipe", null);
        assertThat(((Number) again.get("retries")).intValue()).isZero();
        assertThat((String) again.get("state")).isIn("waiting", "offered");
        // The reconcile visited every owner: the jobs it gave other tests' artifacts are ended here.
        freeTheIngestionLane();
    }

    // --- Story 5.2

    @Test
    void v45FillsEachVersionsTypeFromItsRecordAndThenRequiresIt() throws Exception {
        Owner a = owner();
        UUID created = as(a.id(), () -> artifacts.create(a.campaign(), kael())).orElseThrow().id();
        assertThat(jdbc.sql("select record_type from record_version where record_id = ?").param(created).query()
                .singleRow()).containsEntry("record_type", "item");
        String fill = migration("V45__artifact_versions.sql").lines().filter(line -> line.startsWith("update "))
                .findFirst().orElseThrow();

        // The migration's own statement, run again on a version as the previous release wrote it (no type), in a
        // transaction rolled back: DDL is transactional on PostgreSQL.
        as(a.id(), () -> new TransactionTemplate(transactions).execute(status -> {
            jdbc.sql("alter table record_version alter column record_type drop not null").update();
            jdbc.sql("update record_version set record_type = null where record_id = ?").param(created).update();
            jdbc.sql("update record set type = 'note' where id = ?").param(created).update();
            jdbc.sql(fill.replaceAll(";\\s*$", "")).update();
            assertThat(jdbc.sql("select record_type from record_version where record_id = ?").param(created).query()
                    .singleRow()).containsEntry("record_type", "note");
            jdbc.sql("alter table record_version alter column record_type set not null").update();
            status.setRollbackOnly();
            return null;
        }));
        assertThat(jdbc.sql("select is_nullable from information_schema.columns where table_name = 'record_version'"
                + " and column_name = 'record_type'").query(String.class).single()).isEqualTo("NO");
        assertThat(jdbc.sql("select type from record where id = ?").param(created).query(String.class).single())
                .isEqualTo("item");
    }

    @Test
    void anEditRoundTripsEncryptedARestoreCopiesAnEarlierVersionAndTheVersionsPageOnPostgres() throws Exception {
        Owner a = owner();
        freeTheIngestionLane();
        UUID id = as(a.id(), () -> artifacts.create(a.campaign(), kael())).orElseThrow().id();
        UUID firstJob = (UUID) jdbc.sql("select job_id from artifact_index where record_id = ? and number = 1")
                .param(id).query().singleRow().get("job_id");

        Artifacts.Artifact edited = as(a.id(), () -> artifacts.update(a.campaign(), id, 1, new Artifacts.Written(
                "note", "Espada lembrada", List.of(new Artifacts.Field("Onde", "Torre")), "Guardada na torre.")))
                .orElseThrow();

        assertThat(edited.version()).isEqualTo(2);
        assertThat(edited.currentVersion()).isEqualTo(2);
        List<Map<String, Object>> rows = jdbc.sql("select number, record_type, name, fields, content from"
                + " record_version where record_id = ? order by number").param(id).query().listOfRows();
        assertThat(rows).extracting(row -> row.get("number")).containsExactly(1, 2);
        assertThat(rows).extracting(row -> row.get("record_type")).containsExactly("item", "note");
        for (String column : List.of("name", "fields", "content")) {
            String stored = new String((byte[]) rows.get(1).get(column), StandardCharsets.ISO_8859_1);
            assertThat(stored).doesNotContain("lembrada").doesNotContain("Torre").doesNotContain("torre");
        }
        assertThat(vault.decryptText(new Binding("record_version", "content", id, a.id()),
                (byte[]) rows.get(1).get("content"))).isEqualTo("Guardada na torre.");
        assertThat(jdbc.sql("select type from record where id = ?").param(id).query(String.class).single())
                .isEqualTo("note");
        // Version 2's index job committed with it; version 1's unended job was cancelled.
        Map<String, Object> second = jdbc.sql("select i.job_id, j.kind, j.state from artifact_index i join job j"
                + " on j.id = i.job_id where i.record_id = ? and i.number = 2").param(id).query().singleRow();
        assertThat(second).containsEntry("kind", "index.artifact");
        assertThat((String) second.get("state")).isIn("waiting", "offered");
        assertThat(jdbc.sql("select state from job where id = ?").param(firstJob).query(String.class).single())
                .isEqualTo("cancelled");
        // The newest-version reads with two versions: the list and the question sources read version 2 only.
        assertNewest(a, id, 2, "Espada lembrada");
        // The backfill's candidates are newest versions only: version 2 under a recipe no row reports, never version 1;
        // once version 2 reports it, the owner has no candidate left, though version 1 never did.
        String recipe = "it-5-2-" + Ids.newId().toString().substring(0, 8);
        UUID secondJob = (UUID) second.get("job_id");
        assertThat(stale.owners(recipe)).contains(a.id());
        as(a.id(), () -> new TransactionTemplate(transactions).execute(status -> {
            assertThat(index.notIndexedUnder(recipe)).contains(new ArtifactIndexStore.VersionKey(id, 2))
                    .doesNotContain(new ArtifactIndexStore.VersionKey(id, 1));
            assertThat(index.recordRecipe(secondJob, recipe)).isTrue();
            assertThat(index.notIndexedUnder(recipe)).doesNotContain(new ArtifactIndexStore.VersionKey(id, 2),
                    new ArtifactIndexStore.VersionKey(id, 1));
            return null;
        }));
        assertThat(stale.owners(recipe)).doesNotContain(a.id());

        // A restore copies version 1 as version 3; the page reads every version, newest first.
        Artifacts.Artifact restored = as(a.id(), () -> artifacts.restore(a.campaign(), id, 1, 2)).orElseThrow();
        assertThat(restored.version()).isEqualTo(3);
        assertThat(restored.recordType()).isEqualTo("item");
        assertThat(restored.name()).isEqualTo(KAEL);
        assertThat(restored.fields()).isEqualTo(kael().fields());
        assertThat(restored.content()).isEqualTo(kael().content());
        assertThat(jdbc.sql("select type from record where id = ?").param(id).query(String.class).single())
                .isEqualTo("item");
        assertNewest(a, id, 3, KAEL);
        Artifacts.VersionPage page = as(a.id(), () -> artifacts.versions(a.campaign(), id, null, 0, 2))
                .orElseThrow();
        assertThat(page.items()).extracting(Artifacts.VersionSummary::number).containsExactly(3, 2);
        assertThat(page.items()).extracting(Artifacts.VersionSummary::name).containsExactly(KAEL, "Espada lembrada");
        assertThat(page.nextCursor()).isEqualTo(2);
        assertThat(page.total()).isEqualTo(3);
        Artifacts.VersionPage after = as(a.id(), () -> artifacts.versions(a.campaign(), id, 2, 0, 2)).orElseThrow();
        assertThat(after.items()).extracting(Artifacts.VersionSummary::number).containsExactly(1);
        assertThat(after.nextCursor()).isNull();
        Artifacts.VersionPage numbered = as(a.id(), () -> artifacts.versions(a.campaign(), id, null, 1, 1))
                .orElseThrow();
        assertThat(numbered.items()).extracting(Artifacts.VersionSummary::recordType).containsExactly("note");
        // The cited version reads as it was, beside the newest number; an unknown one is none.
        Artifacts.Artifact cited = as(a.id(), () -> artifacts.get(a.campaign(), id, 2)).orElseThrow();
        assertThat(cited.version()).isEqualTo(2);
        assertThat(cited.currentVersion()).isEqualTo(3);
        assertThat(cited.content()).isEqualTo("Guardada na torre.");
        assertThat(as(a.id(), () -> artifacts.get(a.campaign(), id, 9))).isEmpty();
        // Another owner reads and writes nothing of it.
        Owner b = owner();
        assertThat(as(b.id(), () -> artifacts.versions(a.campaign(), id, null, 0, 20))).isEmpty();
        assertThat(as(b.id(), () -> artifacts.restore(b.campaign(), id, 1, 3))).isEmpty();
        assertThat(count("record_version", "record_id = ?", id)).isEqualTo(3);
        freeTheIngestionLane();
    }

    @Test
    void aTakenNumberIsVersionTakenAndTwoEditsOfTheSameVersionStoreOneAndRefuseTheOther() throws Exception {
        Owner a = owner();
        freeTheIngestionLane();
        UUID id = as(a.id(), () -> artifacts.create(a.campaign(), kael())).orElseThrow().id();

        // The key on (record, number): the store answers a taken number as VersionTaken.
        assertThatThrownBy(() -> as(a.id(), () -> new TransactionTemplate(transactions).execute(status -> {
            versions.insert(new ArtifactVersionRow(id, a.id(), 1, "item", new byte[] {1}, new byte[] {1}, null,
                    Instant.now().truncatedTo(ChronoUnit.MICROS)));
            return null;
        }))).isInstanceOf(VersionTaken.class);

        // Tab A's edit holds its transaction open; tab B's edit of the same version waits, then is stale.
        CountDownLatch saved = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService tabs = Executors.newFixedThreadPool(2);
        try {
            Future<Artifacts.Artifact> tabA = tabs.submit(() -> as(a.id(), () -> new TransactionTemplate(transactions)
                    .execute(status -> {
                        Artifacts.Artifact edited = artifacts.update(a.campaign(), id, 1, new Artifacts.Written(
                                "item", "Aba A", List.of(), null)).orElseThrow();
                        saved.countDown();
                        try {
                            assertThat(commit.await(20, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        return edited;
                    })));
            assertThat(saved.await(20, TimeUnit.SECONDS)).isTrue();
            Future<Artifacts.Artifact> tabB = tabs.submit(() -> as(a.id(), () -> artifacts.update(a.campaign(), id, 1,
                    new Artifacts.Written("note", "Aba B", List.of(), null)).orElseThrow()));
            Thread.sleep(500);
            commit.countDown();

            assertThat(tabA.get(30, TimeUnit.SECONDS).version()).isEqualTo(2);
            assertThatThrownBy(() -> tabB.get(30, TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class)
                    .cause().satisfies(refused -> assertThat(((ApiException) refused).problem().code())
                            .isEqualTo("request.stale_version"));
        } finally {
            tabs.shutdownNow();
        }
        assertThat(count("record_version", "record_id = ?", id)).isEqualTo(2);
        assertThat(jdbc.sql("select type from record where id = ?").param(id).query(String.class).single())
                .isEqualTo("item");
        freeTheIngestionLane();
    }

    // --- Story 5.2 part B

    @Test
    void v46AllowsAnArtifactDeletionAndTheDeletePassRemovesTheRowsWithOneRemoval() throws Exception {
        freeTheIngestionLane();
        Owner a = owner();
        UUID id = as(a.id(), () -> artifacts.create(a.campaign(), kael())).orElseThrow().id();
        as(a.id(), () -> artifacts.update(a.campaign(), id, 1, new Artifacts.Written("note", "Espada lembrada",
                List.of(), null))).orElseThrow();
        UUID staying = as(a.id(), () -> artifacts.create(a.campaign(), new Artifacts.Written("note", "Lembrete",
                List.of(), null))).orElseThrow().id();
        UUID openJob = jdbc.sql("select job_id from artifact_index where record_id = ? and number = 2").param(id)
                .query(UUID.class).single();

        // V46: the kind check now takes 'artifact', and still refuses any other kind.
        assertThat(migration("V46__artifact_deletion.sql")).contains("'artifact'");
        assertThatThrownBy(() -> jdbc.sql("insert into deletion (id, owner_id, kind, target_id, not_before, created_at)"
                + " values (?, ?, 'record', ?, now(), now())").params(Ids.newId(), a.id(), id).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(as(a.id(), () -> artifacts.delete(a.campaign(), id))).isTrue();

        // The GM's transaction: the row marked deleting, the deletion recorded, the open index job cancelled.
        assertThat(jdbc.sql("select deleted_at from record where id = ?").param(id).query().singleRow()
                .get("deleted_at")).isNotNull();
        Map<String, Object> recorded = jdbc.sql("select owner_id, kind, done_at from deletion where target_id = ?")
                .param(id).query().singleRow();
        assertThat(recorded).containsEntry("owner_id", a.id()).containsEntry("kind", "artifact");
        assertThat(recorded.get("done_at")).isNull();
        assertThat(jdbc.sql("select state from job where id = ?").param(openJob).query(String.class).single())
                .isEqualTo("cancelled");
        // A second delete changes nothing.
        assertThat(as(a.id(), () -> artifacts.delete(a.campaign(), id))).isFalse();
        assertThat(count("deletion", "target_id = ?", id)).isOne();

        // The pass (a restart before it leaves the record to the sweep).
        assertThat(deletion.sweep()).isGreaterThanOrEqualTo(1);

        assertThat(count("record", "id = ?", id)).isZero();
        assertThat(count("record_version", "record_id = ?", id)).isZero();
        assertThat(count("artifact_index", "record_id = ?", id)).isZero();
        Map<String, Object> removal = jdbc.sql("select r.owner_id, j.kind from artifact_index_removal r join job j"
                + " on j.id = r.job_id where r.artifact_id = ?").param(id).query().singleRow();
        assertThat(removal).containsEntry("owner_id", a.id()).containsEntry("kind", "index.artifact_removal");
        assertThat(jdbc.sql("select done_at from deletion where target_id = ?").param(id).query().singleRow()
                .get("done_at")).isNotNull();
        assertThat(count("record", "id = ?", staying)).isOne();
        assertThat(count("record_version", "record_id = ?", staying)).isOne();
        // Again: nothing to do, and no second removal.
        deletion.sweep();
        assertThat(count("artifact_index_removal", "artifact_id = ?", id)).isOne();
        freeTheIngestionLane();
    }

    @Test
    void aDeletingArtifactIsInNoLiveReadButTheAllReadsAndACampaignsDeletionStillTakeIt() throws Exception {
        freeTheIngestionLane();
        Owner a = owner();
        UUID other = as(a.id(), () -> library.createCampaign(a.system(), "Outra mesa")).orElseThrow().id();
        UUID id = as(a.id(), () -> artifacts.create(other, kael())).orElseThrow().id();
        UUID live = as(a.id(), () -> artifacts.create(other, new Artifacts.Written("note", "Lembrete", List.of(),
                null))).orElseThrow().id();
        UUID used = approvedType(a, "arma_magica", "Arma mágica");
        UUID typed = as(a.id(), () -> artifacts.create(other, new Artifacts.Written("arma_magica", "Arco élfico",
                List.of(), null))).orElseThrow().id();

        assertThat(as(a.id(), () -> artifacts.delete(other, id))).isTrue();
        assertThat(as(a.id(), () -> artifacts.delete(other, typed))).isTrue();

        // Every live read leaves the deleting artifacts out; the all reads (a deletion's removal) still answer them.
        as(a.id(), () -> new TransactionTemplate(transactions).execute(status -> {
            assertThat(records.findArtifact(other, id)).isEmpty();
            assertThat(records.findArtifacts(List.of(id, live), RecordStore.Reach.LIVE))
                    .extracting(ArtifactRow::id).containsExactly(live);
            assertThat(records.findArtifacts(List.of(id, live), RecordStore.Reach.ALL))
                    .extracting(ArtifactRow::id).containsExactlyInAnyOrder(id, live);
            assertThat(records.artifactIdsOf(List.of(other), RecordStore.Reach.LIVE)).containsExactly(live);
            assertThat(records.artifactIdsOf(List.of(other), RecordStore.Reach.ALL))
                    .containsExactlyInAnyOrder(id, live, typed);
            assertThat(records.artifacts(other, List.of(), null, null, 0, 20)).extracting(ArtifactRow::id)
                    .containsExactly(live);
            assertThat(records.artifacts(other, List.of(), "item", null, 0, 20)).isEmpty();
            // The cursor-paged reads, from an id newer than the deleting ones (the newest artifact, typed).
            assertThat(records.artifacts(other, List.of(), null, Ids.newId(), 0, 20)).extracting(ArtifactRow::id)
                    .containsExactly(live);
            assertThat(records.artifacts(other, List.of(), "item", Ids.newId(), 0, 20)).isEmpty();
            assertThat(records.artifacts(other, List.of(), null, typed, 0, 20)).extracting(ArtifactRow::id)
                    .containsExactly(live).doesNotContain(id);
            assertThat(records.artifactCount(other, List.of(), null)).isOne();
            assertThat(records.artifactCount(other, List.of(), "item")).isZero();
            assertThat(records.artifactsIn(List.of(other))).isOne();
            assertThat(records.artifactsOfTypeIn(List.of(other), "arma_magica")).isZero();
            assertThat(records.artifactTypes(other, List.of())).containsOnlyKeys("note");
            assertThat(records.retypeArtifact(id, "note")).isEmpty();
            assertThat(records.markArtifactDeleting(id, Instant.now())).isFalse();
            return null;
        }));
        assertThat(as(a.id(), () -> artifacts.get(other, id))).isEmpty();
        assertThat(as(a.id(), () -> artifacts.versions(other, id, null, 0, 20))).isEmpty();
        assertThat(as(a.id(), () -> artifacts.update(other, id, 1, new Artifacts.Written("note", "X", List.of(),
                null)))).isEmpty();
        assertThat(as(a.id(), () -> artifacts.restore(other, id, 1, 1))).isEmpty();
        assertThat(as(a.id(), () -> campaignArtifacts.ids(other))).containsExactly(live);
        assertThat(as(a.id(), () -> campaignArtifacts.live(other, List.of(id, live)))).containsExactly(live);
        assertThat(as(a.id(), () -> campaignArtifacts.read(other, List.of(id, live))))
                .extracting(CampaignArtifacts.Artifact::id).containsExactly(live);
        assertThat(as(a.id(), () -> deletion.previewCampaign(other)).orElseThrow().artifacts()).isOne();
        // The type of a deleting artifact is not in use.
        assertThat(as(a.id(), () -> ontology.delete(used))).isTrue();

        // A campaign's deletion before the artifacts' passes takes them too; their own deletions then end as done.
        assertThat(as(a.id(), () -> deletion.deleteCampaign(other))).isTrue();
        deletion.sweep();
        deletion.sweep();
        assertThat(count("record", "campaign_id = ?", other)).isZero();
        assertThat(count("record_version", "record_id = ?", id)).isZero();
        assertThat(count("deletion", "target_id = ? and done_at is null", id)).isZero();
        assertThat(count("deletion", "target_id = ? and done_at is null", typed)).isZero();
        assertThat(count("artifact_index_removal", "artifact_id = ?", id)).isOne();
        freeTheIngestionLane();
    }

    @Test
    void anEditThatWaitedBehindADeleteIsNotFoundAndStoresNoVersionAndNoIndexJob() throws Exception {
        Owner a = owner();
        freeTheIngestionLane();
        UUID id = as(a.id(), () -> artifacts.create(a.campaign(), kael())).orElseThrow().id();

        // The delete holds its transaction open; an edit of the same version waits on its locks, then finds it
        // deleting.
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService tabs = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> deleting = tabs.submit(() -> as(a.id(), () -> new TransactionTemplate(transactions)
                    .execute(status -> {
                        boolean recorded = artifacts.delete(a.campaign(), id);
                        deleted.countDown();
                        try {
                            assertThat(commit.await(20, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        return recorded;
                    })));
            assertThat(deleted.await(20, TimeUnit.SECONDS)).isTrue();
            Future<Optional<Artifacts.Artifact>> editing = tabs.submit(() -> as(a.id(), () -> artifacts.update(
                    a.campaign(), id, 1, new Artifacts.Written("note", "Tarde demais", List.of(), null))));
            Thread.sleep(500);
            assertThat(editing.isDone()).isFalse();
            commit.countDown();

            assertThat(deleting.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(editing.get(30, TimeUnit.SECONDS)).isEmpty();
        } finally {
            tabs.shutdownNow();
        }
        assertThat(count("record_version", "record_id = ?", id)).isOne();
        assertThat(jdbc.sql("select count(*) from artifact_index where record_id = ? and number = 2").param(id)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select type from record where id = ?").param(id).query(String.class).single())
                .isEqualTo("item");
        freeTheIngestionLane();
    }

    /**
     * The newest-version reads on PostgreSQL (Story 5.2): the campaign's list shows the artifact once, at version
     * {@code number} with its name, and the question sources' read gives that version's name and number.
     */
    private void assertNewest(Owner owner, UUID id, int number, String name) throws Exception {
        Artifacts.Page page = as(owner.id(), () -> artifacts.list(owner.campaign(), null, null, 0, 20)).orElseThrow();
        assertThat(page.items()).filteredOn(item -> item.id().equals(id)).singleElement().satisfies(item -> {
            assertThat(item.version()).isEqualTo(number);
            assertThat(item.name()).isEqualTo(name);
        });
        assertThat(page.total()).isOne();
        List<CampaignArtifacts.Artifact> read = as(owner.id(), () -> campaignArtifacts.read(owner.campaign(),
                List.of(id)));
        assertThat(read).singleElement().satisfies(artifact -> {
            assertThat(artifact.version()).isEqualTo(number);
            assertThat(artifact.name()).isEqualTo(name);
        });
    }

    /** A migration's text, as the application's classpath holds it. */
    private static String migration(String name) throws Exception {
        try (var in = ArtifactsIntegrationTest.class.getResourceAsStream("/db/migration/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // --- Story 5.5 part A

    @Test
    void anOverrideIsOneRowAnAnswerReadsItAndItGoesWithTheArtifactsRowAndWithTheRecordsRowOnPostgres()
            throws Exception {
        freeTheIngestionLane();
        Owner a = owner();
        SystemRecord fireball = systemRecord(a, "Bola de Fogo");
        UUID house = as(a.id(), () -> artifacts.create(a.campaign(), new Artifacts.Written("spell",
                "Bola de Fogo (casa)", List.of(), null))).orElseThrow().id();
        UUID second = as(a.id(), () -> artifacts.create(a.campaign(), new Artifacts.Written("note", "Outra",
                List.of(), null))).orElseThrow().id();

        Artifacts.Artifact marked = as(a.id(), () -> artifacts.override(a.campaign(), house, fireball.id()))
                .orElseThrow();

        assertThat(marked.overrides().recordId()).isEqualTo(fireball.id());
        assertThat(marked.overrides().name()).isEqualTo("Bola de Fogo");
        assertThat(marked.overrides().removed()).isFalse();
        Map<String, Object> row = jdbc.sql("select owner_id, to_record_id, kind from record_relation"
                + " where from_record_id = ?").param(house).query().singleRow();
        assertThat(row).containsEntry("owner_id", a.id()).containsEntry("to_record_id", fireball.id())
                .containsEntry("kind", "overrides");
        // The table: one mark per artifact (V51's partial index keeps it), and no kind but 'overrides' and 'related'.
        assertThatThrownBy(() -> jdbc.sql("insert into record_relation (id, owner_id, from_record_id, to_record_id,"
                + " kind, created_at) values (?, ?, ?, ?, 'overrides', now())").params(Ids.newId(), a.id(), house,
                fireball.id()).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("insert into record_relation (id, owner_id, from_record_id, to_record_id,"
                + " kind, created_at) values (?, ?, ?, ?, 'shares', now())").params(Ids.newId(), a.id(), second,
                fireball.id()).update()).isInstanceOf(DataIntegrityViolationException.class);
        // A second artifact of the campaign is refused, naming the first; nothing is written.
        assertThatThrownBy(() -> as(a.id(), () -> artifacts.override(a.campaign(), second, fireball.id())))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.problem().code()).isEqualTo("artifact.override_taken");
                    assertThat(refused.problem().params()).containsEntry("artifactId", house.toString());
                });
        assertThat(count("record_relation", "from_record_id = ?", second)).isZero();

        // An answer of the campaign reads the marks on both sides.
        UUID turn = as(a.id(), () -> conversations.ask(a.campaign(), "Como funciona a Bola de Fogo?")).turnId();
        UUID answerJob = jdbc.sql("select job_id from turn where id = ?").param(turn).query(UUID.class).single();
        JobClaims.Claim answer = claims.claim(answerJob, null, List.of("azure-openai"));
        List<JobRecord> read = recordReads.read(answerJob, answer.claimId(), answer.grant(),
                List.of(house, fireball.id()));
        assertThat(read).extracting(JobRecord::id, JobRecord::overrides, JobRecord::overriddenBy).containsExactly(
                org.assertj.core.groups.Tuple.tuple(house, fireball.id(), null),
                org.assertj.core.groups.Tuple.tuple(fireball.id(), null, house));

        // Pointed to another record, the mark is the same row with its new record (the store's retarget).
        UUID relation = jdbc.sql("select id from record_relation where from_record_id = ?").param(house)
                .query(UUID.class).single();
        SystemRecord lightning = systemRecord(a, "Relâmpago");
        as(a.id(), () -> artifacts.override(a.campaign(), house, lightning.id())).orElseThrow();
        assertThat(count("record_relation", "from_record_id = ?", house)).isOne();
        assertThat(jdbc.sql("select id, to_record_id from record_relation where from_record_id = ?").param(house)
                .query().singleRow()).containsEntry("id", relation).containsEntry("to_record_id", lightning.id());

        // The artifact's deletion pass removes its row, and the mark with it.
        assertThat(as(a.id(), () -> artifacts.delete(a.campaign(), house))).isTrue();
        assertThat(deletion.sweep()).isGreaterThanOrEqualTo(1);
        assertThat(count("record", "id = ?", house)).isZero();
        assertThat(count("record_relation", "from_record_id = ?", house)).isZero();
        // The record's row removed with its version: the second artifact's mark goes with it.
        as(a.id(), () -> artifacts.override(a.campaign(), second, fireball.id())).orElseThrow();
        assertThat(count("record_relation", "from_record_id = ?", second)).isOne();
        as(a.id(), () -> new TransactionTemplate(transactions).execute(status ->
                extracted.removeVersion(fireball.versionId())));
        assertThat(count("record_relation", "from_record_id = ?", second)).isZero();
        assertThat(as(a.id(), () -> artifacts.get(a.campaign(), second)).orElseThrow().overrides()).isNull();
        freeTheIngestionLane();
    }

    // --- Story 5.5 part B

    @Test
    void aRelationIsOneRowReadFromBothSidesAndItGoesWithEitherArtifactsRowAndWithTheRecordsRowOnPostgres()
            throws Exception {
        freeTheIngestionLane();
        Owner a = owner();
        SystemRecord troll = systemRecord(a, "Troll");
        UUID crypt = as(a.id(), () -> artifacts.create(a.campaign(), new Artifacts.Written("encounter", "Cripta",
                List.of(), null))).orElseThrow().id();
        UUID map = as(a.id(), () -> artifacts.create(a.campaign(), new Artifacts.Written("note", "Mapa",
                List.of(), null))).orElseThrow().id();

        Artifacts.Artifact related = as(a.id(), () -> artifacts.relate(a.campaign(), crypt, troll.id()))
                .orElseThrow();
        assertThat(related.related()).singleElement().satisfies(one -> {
            assertThat(one.recordId()).isEqualTo(troll.id());
            assertThat(one.name()).isEqualTo("Troll");
            assertThat(one.tier()).isEqualTo(io.github.gmshield.scope.service.Tier.SYSTEM);
            assertThat(one.artifact()).isFalse();
            assertThat(one.removed()).isFalse();
        });
        as(a.id(), () -> artifacts.relate(a.campaign(), crypt, map)).orElseThrow();
        // The other way round stores nothing, and both sides read the one row.
        as(a.id(), () -> artifacts.relate(a.campaign(), map, crypt)).orElseThrow();
        assertThat(count("record_relation", "kind = 'related' and owner_id = ?", a.id())).isEqualTo(2);
        assertThat(as(a.id(), () -> artifacts.get(a.campaign(), map)).orElseThrow().related())
                .extracting(RecordRelations.Related::recordId, RecordRelations.Related::name,
                        RecordRelations.Related::artifact)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(crypt, "Cripta", true));
        assertThat(as(a.id(), () -> systemRecords.inCampaign(a.campaign(), troll.id())).orElseThrow().related())
                .extracting(RecordRelations.RelatedArtifact::artifactId).containsExactly(crypt);

        // V51's keys: an override beside the relations, one override per artifact still, one link of a kind between
        // two records in one direction.
        as(a.id(), () -> artifacts.override(a.campaign(), crypt, troll.id())).orElseThrow();
        assertThatThrownBy(() -> jdbc.sql("insert into record_relation (id, owner_id, from_record_id, to_record_id,"
                + " kind, created_at) values (?, ?, ?, ?, 'overrides', now())").params(Ids.newId(), a.id(), crypt,
                map).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("insert into record_relation (id, owner_id, from_record_id, to_record_id,"
                + " kind, created_at) values (?, ?, ?, ?, 'related', now())").params(Ids.newId(), a.id(), crypt,
                troll.id()).update()).isInstanceOf(DataIntegrityViolationException.class);

        // Oldest first, read from PostgreSQL.
        assertThat(as(a.id(), () -> artifacts.get(a.campaign(), crypt)).orElseThrow().related())
                .extracting(RecordRelations.Related::recordId).containsExactly(troll.id(), map);

        // Removed from the other side: the row made from the first goes.
        assertThat(as(a.id(), () -> artifacts.unrelate(a.campaign(), map, crypt))).isTrue();
        assertThat(count("record_relation", "kind = 'related' and from_record_id = ?", crypt)).isOne();
        assertThat(as(a.id(), () -> artifacts.get(a.campaign(), map)).orElseThrow().related()).isEmpty();

        // The other artifact's deletion pass takes the relation made to it.
        as(a.id(), () -> artifacts.relate(a.campaign(), crypt, map)).orElseThrow();
        assertThat(as(a.id(), () -> artifacts.delete(a.campaign(), map))).isTrue();
        // While it is deleting, its relation is not shown.
        assertThat(as(a.id(), () -> artifacts.get(a.campaign(), crypt)).orElseThrow().related())
                .extracting(RecordRelations.Related::recordId).containsExactly(troll.id());
        assertThat(deletion.sweep()).isGreaterThanOrEqualTo(1);
        assertThat(count("record_relation", "to_record_id = ?", map)).isZero();
        // The record's row removed with its version: the relation and the mark go with it.
        as(a.id(), () -> new TransactionTemplate(transactions).execute(status ->
                extracted.removeVersion(troll.versionId())));
        assertThat(count("record_relation", "from_record_id = ?", crypt)).isZero();
        assertThat(as(a.id(), () -> artifacts.get(a.campaign(), crypt)).orElseThrow().related()).isEmpty();
        freeTheIngestionLane();
    }

    // --- Story 5.6 part A

    @Test
    void aShareIsOneRowPerPairAndItGoesWithTheArtifactsRowAndWithTheTargetCampaignOnPostgres() throws Exception {
        freeTheIngestionLane();
        Owner a = owner();
        Owner b = owner();
        UUID quinta = as(a.id(), () -> library.createCampaign(a.system(), "Mesa de quinta")).orElseThrow().id();
        UUID domingo = as(a.id(), () -> library.createCampaign(a.system(), "Mesa de domingo")).orElseThrow().id();
        UUID kael = as(a.id(), () -> artifacts.create(a.campaign(), kael())).orElseThrow().id();

        Artifacts.Artifact shared = as(a.id(), () -> artifacts.share(a.campaign(), kael, List.of(quinta, domingo)))
                .orElseThrow();
        assertThat(shared.sharedWith()).extracting(Artifacts.SharedWith::name)
                .containsExactly("Mesa de domingo", "Mesa de quinta");
        // One row per pair, of the owner; nothing is copied and no index job is created.
        assertThat(count("artifact_share", "artifact_id = ?", kael)).isEqualTo(2);
        assertThat(jdbc.sql("select owner_id from artifact_share where artifact_id = ? and campaign_id = ?")
                .params(kael, quinta).query(UUID.class).single()).isEqualTo(a.id());
        assertThat(count("record", "owner_id = ? and campaign_id is not null", a.id())).isOne();
        assertThat(count("artifact_index", "record_id = ?", kael)).isOne();
        // V52's keys: one row per pair, and both sides within the owner.
        assertThatThrownBy(() -> jdbc.sql("insert into artifact_share (artifact_id, campaign_id, owner_id,"
                + " created_at) values (?, ?, ?, now())").params(kael, quinta, a.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("insert into artifact_share (artifact_id, campaign_id, owner_id,"
                + " created_at) values (?, ?, ?, now())").params(kael, b.campaign(), b.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("insert into artifact_share (artifact_id, campaign_id, owner_id,"
                + " created_at) values (?, ?, ?, now())").params(kael, b.campaign(), a.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        // Read on PostgreSQL: a source of the target's system tier, read there read-only, never by another owner.
        assertThat(as(a.id(), () -> campaignArtifacts.shared(quinta))).containsExactly(kael);
        assertThat(as(a.id(), () -> campaignArtifacts.readShared(quinta, List.of(kael))))
                .extracting(CampaignArtifacts.Artifact::name).containsExactly(KAEL);
        assertThat(as(a.id(), () -> campaignArtifacts.ids(quinta))).isEmpty();
        assertThat(as(a.id(), () -> scopes.resolve(quinta, ScopeSettings.STARTING)).shared()).containsExactly(kael);
        Artifacts.Artifact there = as(a.id(), () -> artifacts.get(quinta, kael)).orElseThrow();
        assertThat(there.sharedFrom().campaignId()).isEqualTo(a.campaign());
        assertThat(there.sharedWith()).isEmpty();
        assertThat(as(b.id(), () -> campaignArtifacts.shared(quinta))).isEmpty();
        assertThat(as(b.id(), () -> artifacts.get(quinta, kael))).isEmpty();

        // A new set removes the rows it no longer names, on PostgreSQL; an empty set removes them all.
        as(a.id(), () -> artifacts.share(a.campaign(), kael, List.of(domingo))).orElseThrow();
        assertThat(count("artifact_share", "artifact_id = ?", kael)).isOne();
        assertThat(as(a.id(), () -> campaignArtifacts.shared(quinta))).isEmpty();
        as(a.id(), () -> artifacts.share(a.campaign(), kael, List.of())).orElseThrow();
        assertThat(count("artifact_share", "artifact_id = ?", kael)).isZero();
        assertThat(as(a.id(), () -> campaignArtifacts.shared(quinta))).isEmpty();
        as(a.id(), () -> artifacts.share(a.campaign(), kael, List.of(quinta, domingo))).orElseThrow();
        assertThat(count("artifact_share", "artifact_id = ?", kael)).isEqualTo(2);
        assertThat(as(a.id(), () -> campaignArtifacts.shared(quinta))).containsExactly(kael);

        // The target campaign's key has no cascade: its row cannot go while a share names it; its deletion's pass
        // removes the shares into it first.
        assertThatThrownBy(() -> jdbc.sql("delete from campaign where id = ?").param(quinta).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(as(a.id(), () -> deletion.deleteCampaign(quinta))).isTrue();
        assertThat(as(a.id(), () -> artifacts.get(a.campaign(), kael)).orElseThrow().sharedWith())
                .extracting(Artifacts.SharedWith::campaignId).containsExactly(domingo);
        assertThat(deletion.sweep()).isGreaterThanOrEqualTo(1);
        assertThat(count("campaign", "id = ?", quinta)).isZero();
        assertThat(count("artifact_share", "campaign_id = ?", quinta)).isZero();
        assertThat(count("artifact_share", "artifact_id = ?", kael)).isOne();

        // The artifact's key cascades: its row removed takes its shares.
        jdbc.sql("delete from artifact_index where record_id = ?").param(kael).update();
        jdbc.sql("delete from record_version where record_id = ?").param(kael).update();
        jdbc.sql("delete from record where id = ?").param(kael).update();
        assertThat(count("artifact_share", "artifact_id = ?", kael)).isZero();
        assertThat(as(a.id(), () -> campaignArtifacts.shared(domingo))).isEmpty();
        freeTheIngestionLane();
    }

    // --- Story 5.6 part B

    @Test
    void theListOfACampaignHoldsTheArtifactsSharedIntoItInIdOrderWithItsPagingAndCountsOnPostgres() throws Exception {
        freeTheIngestionLane();
        Owner a = owner();
        Owner b = owner();
        UUID quinta = as(a.id(), () -> library.createCampaign(a.system(), "Mesa de quinta")).orElseThrow().id();
        UUID older = as(a.id(), () -> artifacts.create(quinta, new Artifacts.Written("note", "Lembrete", List.of(),
                null))).orElseThrow().id();
        UUID espada = as(a.id(), () -> artifacts.create(a.campaign(), kael())).orElseThrow().id();
        UUID kept = as(a.id(), () -> artifacts.create(a.campaign(), new Artifacts.Written("note", "Diário", List.of(),
                null))).orElseThrow().id();
        UUID newer = as(a.id(), () -> artifacts.create(quinta, new Artifacts.Written("item", "Escudo", List.of(),
                null))).orElseThrow().id();
        as(a.id(), () -> artifacts.share(a.campaign(), espada, List.of(quinta))).orElseThrow();

        // One list, newest first by id: the own artifacts and the shared one, counted in total and types.
        Artifacts.Page page = as(a.id(), () -> artifacts.list(quinta, null, null, 0, 20)).orElseThrow();
        assertThat(page.items()).extracting(Artifacts.Summary::id).containsExactly(newer, espada, older);
        assertThat(page.items()).extracting(summary -> summary.sharedFrom() == null ? null
                : summary.sharedFrom().name()).containsExactly(null, "Primeira campanha", null);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.types()).containsExactly(new Artifacts.TypeCount("item", 2), new Artifacts.TypeCount("note",
                1));
        // The type filter, the cursor and the offset page the same list in SQL.
        assertThat(as(a.id(), () -> artifacts.list(quinta, "item", null, 0, 20)).orElseThrow().items())
                .extracting(Artifacts.Summary::id).containsExactly(newer, espada);
        assertThat(as(a.id(), () -> artifacts.list(quinta, "item", null, 0, 20)).orElseThrow().total()).isEqualTo(2);
        Artifacts.Page first = as(a.id(), () -> artifacts.list(quinta, null, null, 0, 1)).orElseThrow();
        assertThat(first.nextCursor()).isEqualTo(newer);
        assertThat(as(a.id(), () -> artifacts.list(quinta, null, newer, 0, 1)).orElseThrow().items())
                .extracting(Artifacts.Summary::id).containsExactly(espada);
        assertThat(as(a.id(), () -> artifacts.list(quinta, "item", newer, 0, 20)).orElseThrow().items())
                .extracting(Artifacts.Summary::id).containsExactly(espada);
        assertThat(as(a.id(), () -> artifacts.list(quinta, null, null, 2, 20)).orElseThrow().items())
                .extracting(Artifacts.Summary::id).containsExactly(older);
        // The own list is unchanged, and the other owner's sees nothing of A's.
        Artifacts.Page own = as(a.id(), () -> artifacts.list(a.campaign(), null, null, 0, 20)).orElseThrow();
        assertThat(own.items()).extracting(Artifacts.Summary::id).containsExactly(kept, espada);
        assertThat(own.items()).allMatch(summary -> summary.sharedFrom() == null);
        assertThat(as(b.id(), () -> artifacts.list(b.campaign(), null, null, 0, 20)).orElseThrow().total()).isZero();
        as(b.id(), () -> new TransactionTemplate(transactions).execute(status -> {
            assertThat(records.artifacts(b.campaign(), List.of(espada), null, null, 0, 20)).isEmpty();
            assertThat(records.artifactCount(b.campaign(), List.of(espada), null)).isZero();
            assertThat(records.artifactTypes(b.campaign(), List.of(espada))).isEmpty();
            assertThat(shares.ofAll(List.of(espada))).isEmpty();
            return null;
        }));
        as(a.id(), () -> new TransactionTemplate(transactions).execute(status -> {
            assertThat(shares.ofAll(List.of(espada, kept))).singleElement().satisfies(row -> {
                assertThat(row.artifactId()).isEqualTo(espada);
                assertThat(row.campaignId()).isEqualTo(quinta);
            });
            assertThat(shares.ofAll(List.of())).isEmpty();
            return null;
        }));

        // Deleting the shared artifact takes it out of the list and its counts.
        assertThat(as(a.id(), () -> artifacts.delete(a.campaign(), espada))).isTrue();
        Artifacts.Page after = as(a.id(), () -> artifacts.list(quinta, null, null, 0, 20)).orElseThrow();
        assertThat(after.items()).extracting(Artifacts.Summary::id).containsExactly(newer, older);
        assertThat(after.total()).isEqualTo(2);
        assertThat(after.types()).containsExactly(new Artifacts.TypeCount("item", 1), new Artifacts.TypeCount("note",
                1));
        freeTheIngestionLane();
    }

    /** A record of the owner's system, of its own ready version: its id and that version. */
    private record SystemRecord(UUID id, UUID versionId) {
    }

    /** A ready material of the owner's system with one spell record named {@code name}, stored directly. */
    private SystemRecord systemRecord(Owner owner, String name) throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID material = Ids.newId();
        UUID version = Ids.newId();
        UUID passage = Ids.newId();
        as(owner.id(), () -> new TransactionTemplate(transactions).execute(status -> {
            materials.insertMaterial(new MaterialRow(material, owner.id(), vault.encryptText(new Binding("material",
                    "name", material, owner.id()), "Livro"), now, "rulebook", null, null));
            materials.insertMaterialSystem(new MaterialSystemRow(Ids.newId(), owner.id(), material, owner.system(),
                    now));
            materials.insertVersion(new VersionRow(version, owner.id(), material, "ready", "pdf", 1000L, now, now));
            byte[] place = "{\"sectionPath\":[\"Magias\"],\"range\":[0,4]}".getBytes(StandardCharsets.UTF_8);
            materials.insertPassages(List.of(new PassageRow(passage, owner.id(), version, 0, 1,
                    vault.encryptText(new Binding("passage", "text", passage, owner.id()), name + "."),
                    vault.encrypt(new Binding("passage", "place", passage, owner.id()), place), now)));
            return extracted.replaceRange(version, 1, 1, List.of(new ExtractedRecords.Extracted("spell", name,
                    List.of(), List.of(passage), 1)));
        }));
        return new SystemRecord(jdbc.sql("select id from record where version_id = ?").param(version)
                .query(UUID.class).single(), version);
    }

    /** One ingestion job at a time: whatever another test left open would hold this test's jobs back. */
    private void freeTheIngestionLane() {
        jdbc.sql("update job set state = 'cancelled', grant_hash = null, claim_renew_at = null, claim_lapses_at = null"
                + " where lane = 'ingestion' and state in ('waiting', 'offered', 'running')").update();
    }

    /** A record type the GM approved in the owner's system with this key (Story 4.4), stored directly: its id. */
    private UUID approvedType(Owner owner, String key, String name) throws Exception {
        UUID proposal = Ids.newId();
        jdbc.sql("insert into proposal (id, owner_id, system_id, kind, name, name_fingerprint, examples, state,"
                + " decided_at, created_at) values (?, ?, ?, 'record_type', ?, ?, ?, 'approved', now(), now())")
                .params(proposal, owner.id(), owner.system(),
                        vault.encryptText(new Binding("proposal", "name", proposal, owner.id()), name),
                        vault.fingerprint("record_type", "name", Library.uniqueForm(name)),
                        vault.encrypt(new Binding("proposal", "examples", proposal, owner.id()),
                                "[]".getBytes(StandardCharsets.UTF_8))).update();
        UUID id = Ids.newId();
        as(owner.id(), () -> new TransactionTemplate(transactions).execute(status -> {
            types.insertRecordTypes(List.of(new RecordTypeRow(id, owner.id(), owner.system(), null, key, proposal,
                    vault.encryptText(new Binding("record_type", "name", id, owner.id()), name),
                    vault.fingerprint("record_type", "name", Library.uniqueForm(name)), 0,
                    Instant.now().truncatedTo(ChronoUnit.MICROS))));
            return null;
        }));
        return id;
    }

    private static Artifacts.Written kael() {
        return new Artifacts.Written("item", KAEL, List.of(new Artifacts.Field("Dano", "1d8 cortante"),
                new Artifacts.Field("Peso", "1,5 kg")), "Forjada nas montanhas do norte.");
    }

    private long count(String table, String where, Object param) {
        return jdbc.sql("select count(*) from " + table + " where " + where).param(param).query(Long.class).single();
    }

    private Owner owner() throws Exception {
        UUID id = Ids.newId();
        return as(id, () -> {
            new TransactionTemplate(transactions).executeWithoutResult(status -> library.createStarter(Language.PT_BR));
            Library.Campaign campaign = library.currentCampaign().orElseThrow();
            return new Owner(id, campaign.system().id(), campaign.id());
        });
    }

    private static <T> T as(UUID owner, Callable<T> work) throws Exception {
        return OwnerContext.callAs(owner, work::call);
    }
}

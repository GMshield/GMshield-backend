package io.github.gmshield.artifacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.GmshieldApplication;
import io.github.gmshield.artifacts.service.Artifacts;
import io.github.gmshield.knowledge.data.KnowledgeStore;
import io.github.gmshield.knowledge.data.RecordStore;
import io.github.gmshield.knowledge.data.RecordTypeRow;
import io.github.gmshield.knowledge.service.Ontology;
import io.github.gmshield.knowledge.service.Records;
import io.github.gmshield.library.service.Language;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.materials.service.ExtractedRecords;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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
        assertThatThrownBy(() -> jdbc.sql("insert into record_version (record_id, owner_id, number, name, fields,"
                + " created_at) values (?, ?, 2, ?, ?, now())").params(artifact, b.id(), new byte[] {1}, new byte[] {1})
                .update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("insert into record_version (record_id, owner_id, number, name, fields,"
                + " created_at) values (?, ?, 1, ?, ?, now())").params(artifact, a.id(), new byte[] {1}, new byte[] {1})
                .update()).isInstanceOf(DataIntegrityViolationException.class);
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

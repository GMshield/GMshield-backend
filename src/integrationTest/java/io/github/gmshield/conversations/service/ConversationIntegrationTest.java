package io.github.gmshield.conversations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.conversations.data.ConversationConflict;
import io.github.gmshield.conversations.data.ConversationStore;
import io.github.gmshield.conversations.data.TurnRow;
import io.github.gmshield.jobs.service.CallUsage;
import io.github.gmshield.jobs.service.JobClaims;
import io.github.gmshield.jobs.service.JobEvent;
import io.github.gmshield.jobs.service.JobEventBatch;
import io.github.gmshield.jobs.service.JobRecord;
import io.github.gmshield.jobs.service.JobRecordReads;
import io.github.gmshield.knowledge.service.Proposals;
import io.github.gmshield.library.service.Language;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.materials.service.ExtractedRecords;
import io.github.gmshield.materials.service.OntologyProposals;
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
import java.util.function.Supplier;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Story 2.4 against a real PostgreSQL and Redis (test mode): a question recorded with its job in one transaction, the
 * conversation made once per campaign, the claim of its {@code question.answer} job, numbered event batches stored once
 * per claim and number with their sequences, a gap refused with nothing stored, the usage recorded once per call id
 * ({@code ON CONFLICT DO NOTHING}), the final {@code done}, the stored events read back after a sequence, the
 * encrypted columns, and another owner. The same rows without a database are in {@code ConversationFlowTest}.
 * Story 2.5: the references of a completed turn stored by value in {@code turn_reference} (passage ids as
 * {@code uuid[]}, the name and the section path encrypted), read back, and marked removed after a discard.
 * Story 2.6: the GM's stop on PostgreSQL: the job row locked, {@code turn.stopped_at} set, the references so far and
 * {@code done} marked stopped stored at once, the next batch stored no more but its usage recorded as an estimate
 * ({@code model_call_usage.estimated}), and the worker's {@code done} adding nothing.
 * Story 3.7: the reference's tier in {@code turn_reference.tier} (V28), null on an older row, and a tier marker stored
 * only when its tier has a source in the turn's scope.
 * Story 3.8: a {@code model} tier marker stored only when the turn's frozen {@code model_knowledge} is on, and the
 * turn's ending read from its row and its stored events.
 * Story 3.9: a search on PostgreSQL ({@code search}, {@code search_result}, V29): recorded with its job and the query
 * encrypted, its claim's input, its results stored in rank order and read back with their places, a result dropped
 * once its version is no longer ready, a forged result refused whole, the checks of the tables, another owner, and
 * only the newest 20 searches kept.
 * Story 3.10 (V30): many conversations per campaign (the unique key gone, the list's index there), each in an area
 * (checked), listed most recently asked first, renamed on its version with the name encrypted (a stale rename refused,
 * asking never raises the version), another owner's never seen, and a done turn's question and answer in the next
 * question's history. On Postgres too: the list most recently asked first (also when that differs from the order they
 * were made), an offset, an area-less question continuing the most recently asked conversation, the newest 200 turns
 * oldest first, and a rename whose row moved after it was read refused at the flush.
 * Story 3.11 (V32): a dismissed turn left out of the conversation's turns, its question counts and the next claim's
 * history, a second dismissal keeping the first time, and the undo putting it back.
 * Story 3.12 (V33): the saved answers across two campaigns, newest saved first, paged with their total, each with its
 * campaign's name, area, conversation name and excerpt; a running turn refused and another owner's never listed nor
 * saved; a dismissed answer and an archived campaign's left out until they come back with the same time; an unsave; and
 * a conversation being deleted leaving the list at once.
 * Story 4.3 part C (V38): an answer job reads a record with its fields, cites it, and the record becomes a reference of
 * its own in {@code turn_reference}, its name and fields encrypted ({@code record_name}, {@code record_fields}) beside
 * its plain id and type, read back as written after the record is replaced, the four columns set together.
 * Story 5.3 part A (V47): a draft's turn of kind {@code draft} with its {@code content.draft} job, the default
 * {@code question} for a row written without the column, the column's check, the kind read back, and a draft outside
 * the questions area recording nothing.
 */
@SpringBootTest(properties = {"GMSHIELD_MODE=test", "gmshield.jobs.sweep.enabled=false",
    "gmshield.materials.upload-sweep.enabled=false",
    "gmshield.materials.version-removal-sweep.enabled=false",
    "gmshield.library.deletion-sweep.enabled=false",
    "gmshield.materials.index-retry-sweep.enabled=false",
    "gmshield.files.dir=${java.io.tmpdir}/gmshield-conversation-it"})
@ActiveProfiles("test-mode")
class ConversationIntegrationTest {

    private static final String QUESTION = "Quanto dano causa Fire Bolt?";

    @Autowired
    private Library library;
    @Autowired
    private MaterialStore materials;
    @Autowired
    private Conversations conversations;
    @Autowired
    private ConversationStore store;
    @Autowired
    private Searches searches;
    @Autowired
    private JobClaims claims;
    @Autowired
    private JobRecordReads recordReads;
    @Autowired
    private ExtractedRecords extracted;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private Vault vault;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private OntologyProposals proposals;
    @Autowired
    private Proposals decisions;

    @Test
    void aQuestionItsBatchesItsUsageAndItsEndAreStoredOnPostgres() {
        UUID owner = Ids.newId();
        UUID campaign = as(owner, () -> {
            library.createStarter(Language.PT_BR);
            return library.currentCampaign().orElseThrow().id();
        });
        assertThat(as(owner, () -> conversations.current()).hasSources()).isFalse();
        readyVersion(owner, campaign);
        assertThat(as(owner, () -> conversations.current()).hasSources()).isTrue();

        Conversations.Asked asked = OwnerContext.callAs(owner, () -> conversations.ask(campaign, QUESTION));
        Conversations.Asked again = OwnerContext.callAs(owner, () -> conversations.ask(campaign, "What is it?"));

        assertThat(asked.language()).isEqualTo("pt-BR");
        assertThat(again.language()).isEqualTo("en");
        assertThat(again.conversationId()).isEqualTo(asked.conversationId());
        Map<String, Object> turn = jdbc.sql("select job_id, question, language, campaign_tier, system_tier,"
                + " model_knowledge from turn where id = :id").param("id", asked.turnId()).query().singleRow();
        assertThat(new String((byte[]) turn.get("question"), StandardCharsets.ISO_8859_1)).doesNotContain("Fire Bolt");
        assertThat(turn.get("campaign_tier")).isEqualTo(true);
        assertThat(turn.get("model_knowledge")).isEqualTo(false);
        UUID jobId = (UUID) turn.get("job_id");
        assertThat(jdbc.sql("select kind from job where id = :id").param("id", jobId).query(String.class).single())
                .isEqualTo(AnswerJobs.KIND);

        JobClaims.Claim claim = claims.claim(jobId, null, List.of("azure-openai"));
        assertThat(claim.input().path("question").asString()).isEqualTo(QUESTION);
        UUID call = Ids.newId();
        UUID passage = Ids.newId();
        JobEventBatch first = new JobEventBatch(claim.claimId(), 1, List.of(JobEvent.delta("Fire Bolt causa "),
                JobEvent.delta("1d10.")));
        JobEventBatch second = new JobEventBatch(claim.claimId(), 2, List.of(JobEvent.citation(passage, 16)));
        List<CallUsage> usage = List.of(new CallUsage(call, "chatcmpl-it", 100, 10, 0, 7, 2, false));
        claims.events(jobId, claim.claimId(), claim.grant(), first, List.of());
        claims.events(jobId, claim.claimId(), claim.grant(), second, usage);
        claims.events(jobId, claim.claimId(), claim.grant(), second, usage);
        JobEventBatch gap = new JobEventBatch(claim.claimId(), 4, List.of(JobEvent.delta("x")));
        assertThatThrownBy(() -> claims.events(jobId, claim.claimId(), claim.grant(), gap, List.of()))
                .isInstanceOfSatisfying(ApiException.class, refused ->
                        assertThat(refused.problem().code()).isEqualTo("job.batch_out_of_order"));
        claims.report(jobId, claim.claimId(), claim.grant(), "done", null, null);

        List<TurnEvent> events = as(owner, () -> conversations.eventsAfter(asked.turnId(), 0));
        assertThat(events).extracting(TurnEvent::sequence).containsExactly(1L, 2L, 3L, 4L);
        assertThat(events).extracting(TurnEvent::type).containsExactly("delta", "delta", "citation", "done");
        assertThat(events.get(2).passageId()).isEqualTo(passage);
        assertThat(as(owner, () -> conversations.eventsAfter(asked.turnId(), 2))).extracting(TurnEvent::sequence)
                .containsExactly(3L, 4L);
        List<byte[]> payloads = jdbc.sql("select payload from turn_event_batch where turn_id = :turn")
                .param("turn", asked.turnId()).query((row, number) -> row.getBytes(1)).list();
        assertThat(payloads).hasSize(3).allSatisfy(payload ->
                assertThat(new String(payload, StandardCharsets.ISO_8859_1)).doesNotContain("Fire Bolt")
                        .doesNotContain("delta"));
        Map<String, Object> recorded = jdbc.sql("select job_id, owner_id, provider, tier, response_id, input_tokens,"
                + " cache_read, output_tokens, reasoning from model_call_usage where call_id = :call")
                .param("call", call).query().singleRow();
        assertThat(recorded.get("job_id")).isEqualTo(jobId);
        assertThat(recorded.get("owner_id")).isEqualTo(owner);
        assertThat(recorded.get("provider")).isEqualTo("azure-openai");
        assertThat(recorded.get("tier")).isEqualTo("full");
        assertThat(recorded.get("response_id")).isEqualTo("chatcmpl-it");
        assertThat(((Number) recorded.get("input_tokens")).longValue()).isEqualTo(100);
        assertThat(((Number) recorded.get("output_tokens")).longValue()).isEqualTo(7);

        // Another owner sees nothing and records nothing.
        UUID other = Ids.newId();
        as(other, () -> {
            library.createStarter(Language.EN);
            return null;
        });
        assertThat(as(other, () -> conversations.turn(asked.turnId()))).isEmpty();
        assertThat(as(other, () -> conversations.eventsAfter(asked.turnId(), 0))).isEmpty();
        assertThatThrownBy(() -> OwnerContext.callAs(other, () -> conversations.ask(campaign, QUESTION)))
                .isInstanceOfSatisfying(ApiException.class, refused ->
                        assertThat(refused.problem().code()).isEqualTo("request.not_found"));

        // Leave no job behind: the second question's job ends.
        UUID secondJob = jdbc.sql("select job_id from turn where id = :id").param("id", again.turnId())
                .query(UUID.class).single();
        JobClaims.Claim other2 = claims.claim(secondJob, null, List.of("azure-openai"));
        claims.report(secondJob, other2.claimId(), other2.grant(), "failed", "model.refused", false, null);
        assertThat(as(owner, () -> conversations.eventsAfter(again.turnId(), 0))).extracting(TurnEvent::type,
                TurnEvent::code).containsExactly(org.assertj.core.groups.Tuple.tuple("error", "model.refused"));
    }

    @Test
    void aCompletedTurnsReferencesAreStoredByValueOnPostgresAndShowRemovedLater() {
        UUID owner = Ids.newId();
        UUID campaign = as(owner, () -> {
            library.createStarter(Language.PT_BR);
            return library.currentCampaign().orElseThrow().id();
        });
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID material = Ids.newId();
        UUID version = Ids.newId();
        List<UUID> passages = List.of(Ids.newId(), Ids.newId(), Ids.newId());
        as(owner, () -> {
            materials.insertMaterial(new MaterialRow(material, owner, vault.encryptText(new Binding("material", "name",
                    material, owner), "SRD 5.2.1"), now, "rulebook", null, campaign));
            materials.insertVersion(new VersionRow(version, owner, material, "ready", "pdf", 1000L, now, now));
            materials.insertPassages(List.of(passage(owner, version, passages.get(0), 0, 4),
                    passage(owner, version, passages.get(1), 1, 4), passage(owner, version, passages.get(2), 2, 9)));
            return null;
        });
        Conversations.Asked asked = OwnerContext.callAs(owner, () -> conversations.ask(campaign, QUESTION));
        UUID jobId = jdbc.sql("select job_id from turn where id = :id").param("id", asked.turnId())
                .query(UUID.class).single();
        JobClaims.Claim claim = claims.claim(jobId, null, List.of("azure-openai"));
        claims.events(jobId, claim.claimId(), claim.grant(), new JobEventBatch(claim.claimId(), 1, List.of(
                JobEvent.tier("campaign", 0), JobEvent.delta("Fire Bolt causa 1d10."),
                JobEvent.citation(passages.get(2), 9), JobEvent.tier("system", 20),
                JobEvent.citation(passages.get(1), 20), JobEvent.citation(Ids.newId(), 20),
                JobEvent.citation(passages.get(0), 20), JobEvent.tier("model", 20))), List.of());
        claims.report(jobId, claim.claimId(), claim.grant(), "done", null, null);

        List<TurnReference> references = as(owner, () -> conversations.references(asked.turnId()));

        assertThat(references).extracting(TurnReference::number, TurnReference::page, TurnReference::passageIds,
                TurnReference::removed).containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, 9, List.of(passages.get(2)), false),
                        org.assertj.core.groups.Tuple.tuple(2, 4, List.of(passages.get(1), passages.get(0)), false));
        assertThat(references.getFirst().materialName()).isEqualTo("SRD 5.2.1");
        assertThat(references.getFirst().sectionPath()).containsExactly("Magias", "Fire Bolt");
        // Story 3.7: the tier is stored with the reference (V28), from the turn's frozen scope; the campaign marker is
        // stored with the events, and the system one, whose tier has no source in this scope, is dropped. Story 3.8: so
        // is the model one, since the starter campaign's model knowledge is off.
        assertThat(references).extracting(TurnReference::tier).containsExactly("campaign", "campaign");
        assertThat(as(owner, () -> conversations.eventsAfter(asked.turnId(), 0))).filteredOn(event ->
                TurnEvent.TIER.equals(event.type())).extracting(TurnEvent::tier, TurnEvent::offset)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("campaign", 0));
        assertThat(as(owner, () -> conversations.ending(asked.turnId()))).satisfies(ending -> {
            assertThat(ending.modelKnowledge()).isFalse();
            assertThat(ending.modelTier()).isFalse();
            assertThat(ending.references()).hasSize(2);
        });
        List<Map<String, Object>> rows = jdbc.sql("select owner_id, number, version_id, material_id, page,"
                + " material_name, section_path, tier from turn_reference where turn_id = :turn order by number")
                .param("turn", asked.turnId()).query().listOfRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().get("owner_id")).isEqualTo(owner);
        assertThat(rows.getFirst().get("version_id")).isEqualTo(version);
        assertThat(rows.getFirst().get("material_id")).isEqualTo(material);
        assertThat(rows).extracting(row -> row.get("tier")).containsExactly("campaign", "campaign");
        assertThat(rows).allSatisfy(row -> {
            assertThat(new String((byte[]) row.get("material_name"), StandardCharsets.ISO_8859_1)).doesNotContain("SRD");
            assertThat(new String((byte[]) row.get("section_path"), StandardCharsets.ISO_8859_1))
                    .doesNotContain("Magias").doesNotContain("Fire Bolt");
        });
        assertThat(jdbc.sql("select cardinality(passage_ids) from turn_reference where turn_id = :turn and number = 2")
                .param("turn", asked.turnId()).query(Integer.class).single()).isEqualTo(2);
        // Another owner reads no reference.
        UUID other = Ids.newId();
        assertThat(as(other, () -> conversations.references(asked.turnId()))).isEmpty();

        as(owner, () -> materials.discardVersion(version, Instant.now()));

        assertThat(as(owner, () -> conversations.references(asked.turnId()))).extracting(TurnReference::removed,
                TurnReference::tier).containsExactly(org.assertj.core.groups.Tuple.tuple(true, "campaign"),
                org.assertj.core.groups.Tuple.tuple(true, "campaign"));
        // A row written before V28 has no tier, and still reads; the column takes only the three words.
        jdbc.sql("update turn_reference set tier = null where turn_id = :turn and number = 2")
                .param("turn", asked.turnId()).update();
        assertThat(as(owner, () -> conversations.references(asked.turnId()))).extracting(TurnReference::tier)
                .containsExactly("campaign", null);
        assertThatThrownBy(() -> jdbc.sql("update turn_reference set tier = 'model' where turn_id = :turn")
                .param("turn", asked.turnId()).update()).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("turn_reference_tier_check");
    }

    @Test
    void aCitedRecordIsStoredByValueAsAReferenceOfItsOwnOnPostgres() {
        UUID owner = Ids.newId();
        UUID campaign = as(owner, () -> {
            library.createStarter(Language.PT_BR);
            return library.currentCampaign().orElseThrow().id();
        });
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID material = Ids.newId();
        UUID version = Ids.newId();
        UUID first = Ids.newId();
        UUID second = Ids.newId();
        as(owner, () -> {
            materials.insertMaterial(new MaterialRow(material, owner, vault.encryptText(new Binding("material", "name",
                    material, owner), "SRD 5.2.1"), now, "rulebook", null, campaign));
            materials.insertVersion(new VersionRow(version, owner, material, "ready", "pdf", 1000L, now, now));
            materials.insertPassages(List.of(passage(owner, version, first, 0, 4), passage(owner, version, second, 1,
                    4)));
            return extracted.replaceRange(version, 1, 9, List.of(new ExtractedRecords.Extracted("spell",
                    "Bola de Fogo", List.of(new ExtractedRecords.Field("Alcance", "45 m"),
                    new ExtractedRecords.Field("Nível", "3")), List.of(first, second), 4)));
        });
        UUID record = jdbc.sql("select id from record where version_id = :version").param("version", version)
                .query(UUID.class).single();
        Conversations.Asked asked = OwnerContext.callAs(owner, () -> conversations.ask(campaign, QUESTION));
        UUID jobId = jdbc.sql("select job_id from turn where id = :id").param("id", asked.turnId())
                .query(UUID.class).single();
        JobClaims.Claim claim = claims.claim(jobId, null, List.of("azure-openai"));

        List<JobRecord> read = recordReads.read(jobId, claim.claimId(), claim.grant(), List.of(record));
        assertThat(read).singleElement().satisfies(one -> {
            assertThat(one.name()).isEqualTo("Bola de Fogo");
            assertThat(one.type()).isEqualTo("spell");
            assertThat(one.tier()).isEqualTo("campaign");
            assertThat(one.page()).isEqualTo(4);
            assertThat(one.sectionPath()).containsExactly("Magias", "Fire Bolt");
            assertThat(one.fields()).extracting(JobRecord.Field::name, JobRecord.Field::value).containsExactly(
                    org.assertj.core.groups.Tuple.tuple("Alcance", "45 m"),
                    org.assertj.core.groups.Tuple.tuple("Nível", "3"));
        });
        assertThatThrownBy(() -> recordReads.read(jobId, claim.claimId(), claim.grant(), List.of(record, first)))
                .isInstanceOfSatisfying(ApiException.class, refused ->
                        assertThat(refused.problem().code()).isEqualTo("scope.passage_refused"));
        claims.events(jobId, claim.claimId(), claim.grant(), new JobEventBatch(claim.claimId(), 1, List.of(
                JobEvent.delta("O alcance é 45 m."), JobEvent.citation(first, 5), JobEvent.recordCitation(record, 16))),
                List.of());
        claims.report(jobId, claim.claimId(), claim.grant(), "done", null, null);

        // The record's material is reprocessed: its records go; the reference keeps what the answer was written from.
        as(owner, () -> extracted.replaceRange(version, 1, 9, List.of()));
        List<TurnReference> references = as(owner, () -> conversations.references(asked.turnId()));

        assertThat(references).extracting(TurnReference::number, TurnReference::kind, TurnReference::page)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "passage", 4),
                        org.assertj.core.groups.Tuple.tuple(2, "record", 4));
        TurnReference cited = references.get(1);
        assertThat(cited.record().id()).isEqualTo(record);
        assertThat(cited.record().type()).isEqualTo("spell");
        assertThat(cited.record().name()).isEqualTo("Bola de Fogo");
        assertThat(cited.record().fields()).extracting(TurnReference.Field::name, TurnReference.Field::value)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Alcance", "45 m"),
                        org.assertj.core.groups.Tuple.tuple("Nível", "3"));
        assertThat(cited.passageIds()).containsExactlyInAnyOrder(first, second);
        assertThat(cited.materialName()).isEqualTo("SRD 5.2.1");
        assertThat(cited.tier()).isEqualTo("campaign");
        assertThat(references.getFirst().record()).isNull();
        Map<String, Object> row = jdbc.sql("select record_id, record_type, record_name, record_fields from"
                + " turn_reference where turn_id = :turn and number = 2").param("turn", asked.turnId()).query()
                .singleRow();
        assertThat(row.get("record_id")).isEqualTo(record);
        assertThat(row.get("record_type")).isEqualTo("spell");
        assertThat(new String((byte[]) row.get("record_name"), StandardCharsets.ISO_8859_1)).doesNotContain("Bola");
        assertThat(new String((byte[]) row.get("record_fields"), StandardCharsets.ISO_8859_1))
                .doesNotContain("Alcance").doesNotContain("45 m");
        assertThat(jdbc.sql("select count(*) from turn_reference where turn_id = :turn and number = 1 and record_id is"
                + " null and record_type is null and record_name is null and record_fields is null")
                .param("turn", asked.turnId()).query(Long.class).single()).isEqualTo(1L);
        // The four are set together, and the type is a key's text (any key from V40, never upper case).
        assertThatThrownBy(() -> jdbc.sql("update turn_reference set record_name = null where turn_id = :turn and"
                + " number = 2").param("turn", asked.turnId()).update()).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("turn_reference_record_check");
        assertThatThrownBy(() -> jdbc.sql("update turn_reference set record_type = 'Dragon' where turn_id = :turn and"
                + " number = 2").param("turn", asked.turnId()).update()).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("turn_reference_record_type_check");
        // Another owner reads no reference.
        assertThat(as(Ids.newId(), () -> conversations.references(asked.turnId()))).isEmpty();
    }

    @Test
    void aStopEndsTheTurnAtOnceOnPostgresAndTheNextBatchIsToldToStop() {
        UUID owner = Ids.newId();
        UUID campaign = as(owner, () -> {
            library.createStarter(Language.PT_BR);
            return library.currentCampaign().orElseThrow().id();
        });
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID material = Ids.newId();
        UUID version = Ids.newId();
        UUID cited = Ids.newId();
        as(owner, () -> {
            materials.insertMaterial(new MaterialRow(material, owner, vault.encryptText(new Binding("material", "name",
                    material, owner), "SRD 5.2.1"), now, "rulebook", null, campaign));
            materials.insertVersion(new VersionRow(version, owner, material, "ready", "pdf", 1000L, now, now));
            materials.insertPassages(List.of(passage(owner, version, cited, 0, 4)));
            return null;
        });
        Conversations.Asked asked = OwnerContext.callAs(owner, () -> conversations.ask(campaign, QUESTION));
        UUID jobId = jdbc.sql("select job_id from turn where id = :id").param("id", asked.turnId())
                .query(UUID.class).single();
        JobClaims.Claim claim = claims.claim(jobId, null, List.of("azure-openai"));
        assertThat(claims.events(jobId, claim.claimId(), claim.grant(), new JobEventBatch(claim.claimId(), 1,
                List.of(JobEvent.delta("Fire Bolt causa"), JobEvent.citation(cited, 9))), List.of())).isFalse();

        OwnerContext.callAs(owner, () -> {
            conversations.stop(asked.turnId());
            return null;
        });

        assertThat(jdbc.sql("select stopped_at from turn where id = :id").param("id", asked.turnId()).query()
                .singleValue()).isNotNull();
        assertThat(jdbc.sql("select state from job where id = :id").param("id", jobId).query(String.class).single())
                .isEqualTo("running");
        UUID call = Ids.newId();
        assertThat(claims.events(jobId, claim.claimId(), claim.grant(), new JobEventBatch(claim.claimId(), 2,
                List.of(JobEvent.delta(" 1d10."))), List.of(new CallUsage(call, null, 40, 0, 0, 3, 0, true))))
                .isTrue();
        claims.report(jobId, claim.claimId(), claim.grant(), "done", null, null);
        // Stopping again changes nothing.
        OwnerContext.callAs(owner, () -> {
            conversations.stop(asked.turnId());
            return null;
        });

        List<TurnEvent> events = as(owner, () -> conversations.eventsAfter(asked.turnId(), 0));
        assertThat(events).extracting(TurnEvent::type).containsExactly("delta", "citation", "done");
        assertThat(events.getLast().stopped()).isTrue();
        assertThat(as(owner, () -> conversations.references(asked.turnId()))).extracting(TurnReference::passageIds)
                .containsExactly(List.of(cited));
        assertThat(jdbc.sql("select estimated from model_call_usage where call_id = :call").param("call", call)
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("select state from job where id = :id").param("id", jobId).query(String.class).single())
                .isEqualTo("done");
        // Another owner's stop finds nothing.
        UUID other = Ids.newId();
        assertThatThrownBy(() -> OwnerContext.callAs(other, () -> {
            conversations.stop(asked.turnId());
            return null;
        })).isInstanceOfSatisfying(ApiException.class, refused ->
                assertThat(refused.problem().code()).isEqualTo("request.not_found"));
    }

    @Test
    void aModelMarkerIsStoredOnlyForATurnWhoseModelKnowledgeIsOnAndItsEndingSaysSo() {
        UUID owner = Ids.newId();
        UUID campaign = as(owner, () -> {
            library.createStarter(Language.PT_BR);
            return library.currentCampaign().orElseThrow().id();
        });
        Conversations.Asked asked = OwnerContext.callAs(owner, () -> conversations.ask(campaign, QUESTION));
        // As if the switch had been on when the question was asked: the turn's frozen value decides.
        jdbc.sql("update turn set model_knowledge = true where id = :id").param("id", asked.turnId()).update();
        UUID jobId = jdbc.sql("select job_id from turn where id = :id").param("id", asked.turnId())
                .query(UUID.class).single();
        JobClaims.Claim claim = claims.claim(jobId, null, List.of("azure-openai"));
        claims.events(jobId, claim.claimId(), claim.grant(), new JobEventBatch(claim.claimId(), 1, List.of(
                JobEvent.tier("model", 0), JobEvent.delta("Em geral, 1d10."), JobEvent.tier("system", 15))), List.of());
        claims.report(jobId, claim.claimId(), claim.grant(), "done", null, null);

        assertThat(as(owner, () -> conversations.eventsAfter(asked.turnId(), 0))).extracting(TurnEvent::type,
                TurnEvent::tier).containsExactly(org.assertj.core.groups.Tuple.tuple("tier", "model"),
                org.assertj.core.groups.Tuple.tuple("delta", null), org.assertj.core.groups.Tuple.tuple("done", null));
        Conversations.Ending ending = as(owner, () -> conversations.ending(asked.turnId()));
        assertThat(ending.modelKnowledge()).isTrue();
        assertThat(ending.modelTier()).isTrue();
        assertThat(ending.references()).isEmpty();
        // Another owner's turn reads as off, with nothing.
        Conversations.Ending theirs = as(Ids.newId(), () -> conversations.ending(asked.turnId()));
        assertThat(theirs.modelKnowledge()).isFalse();
        assertThat(theirs.modelTier()).isFalse();
        assertThat(theirs.references()).isEmpty();
    }

    @Test
    void aSearchItsResultsAndItsPruningAreStoredOnPostgres() {
        UUID owner = Ids.newId();
        UUID campaign = as(owner, () -> {
            library.createStarter(Language.PT_BR);
            return library.currentCampaign().orElseThrow().id();
        });
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID material = Ids.newId();
        UUID version = Ids.newId();
        List<UUID> passages = List.of(Ids.newId(), Ids.newId());
        as(owner, () -> {
            materials.insertMaterial(new MaterialRow(material, owner, vault.encryptText(new Binding("material", "name",
                    material, owner), "SRD 5.2.1"), now, "rulebook", null, campaign));
            materials.insertVersion(new VersionRow(version, owner, material, "ready", "pdf", 1000L, now, now));
            materials.insertPassages(List.of(passage(owner, version, passages.get(0), 0, 4),
                    passage(owner, version, passages.get(1), 1, 9)));
            return null;
        });

        UUID searchId = OwnerContext.callAs(owner, () -> searches.create(campaign, "Fire Bolt", null, null));

        Map<String, Object> row = jdbc.sql("select owner_id, campaign_id, job_id, query, tier, material_id, state, code"
                + " from search where id = :id").param("id", searchId).query().singleRow();
        assertThat(row.get("owner_id")).isEqualTo(owner);
        assertThat(row.get("campaign_id")).isEqualTo(campaign);
        assertThat(row.get("state")).isEqualTo("pending");
        assertThat(row.get("tier")).isNull();
        assertThat(new String((byte[]) row.get("query"), StandardCharsets.ISO_8859_1)).doesNotContain("Fire Bolt");
        UUID jobId = (UUID) row.get("job_id");
        assertThat(jdbc.sql("select kind, lane from job where id = :id").param("id", jobId).query().singleRow())
                .containsEntry("kind", SearchJobs.KIND).containsEntry("lane", "interactive");
        JobClaims.Claim claim = claims.claim(jobId, null, List.of());
        assertThat(claim.input().path("query").asString()).isEqualTo("Fire Bolt");
        assertThat(claim.input().path("tiers").get(0).path("sources").get(0).asString()).isEqualTo(version.toString());
        claims.report(jobId, claim.claimId(), claim.grant(), "done", null, tools.jackson.databind.node.JsonNodeFactory
                .instance.objectNode().set("ids", tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode()
                        .add(passages.get(1).toString()).add(passages.get(0).toString())));

        Searches.Found found = as(owner, () -> searches.find(searchId)).orElseThrow();

        assertThat(found.state()).isEqualTo("done");
        assertThat(found.query()).isEqualTo("Fire Bolt");
        assertThat(found.results()).extracting(Searches.Result::passageId, Searches.Result::page,
                Searches.Result::materialName, Searches.Result::firstWords).containsExactly(
                        org.assertj.core.groups.Tuple.tuple(passages.get(1), 9, "SRD 5.2.1",
                                "Fire Bolt deals 1d10 Fire damage 1"),
                        org.assertj.core.groups.Tuple.tuple(passages.get(0), 4, "SRD 5.2.1",
                                "Fire Bolt deals 1d10 Fire damage 0"));
        assertThat(jdbc.sql("select rank, passage_id, version_id, tier from search_result where search_id = :id"
                + " order by rank").param("id", searchId).query().listOfRows()).extracting(result -> result.get("rank"),
                result -> result.get("passage_id"), result -> result.get("tier")).containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, passages.get(1), "campaign"),
                        org.assertj.core.groups.Tuple.tuple(2, passages.get(0), "campaign"));
        assertThat(jdbc.sql("select state, code from search where id = :id").param("id", searchId).query().singleRow())
                .containsEntry("state", "done").containsEntry("code", null);
        // Another owner finds nothing.
        UUID other = Ids.newId();
        assertThat(as(other, () -> searches.find(searchId))).isEmpty();
        // A forged result is refused whole and the search fails with scope.passage_refused.
        UUID forged = OwnerContext.callAs(owner, () -> searches.create(campaign, "Vorpal",
                io.github.gmshield.scope.service.Tier.CAMPAIGN, material));
        UUID forgedJob = jdbc.sql("select job_id from search where id = :id").param("id", forged).query(UUID.class)
                .single();
        JobClaims.Claim forgedClaim = claims.claim(forgedJob, null, List.of());
        claims.report(forgedJob, forgedClaim.claimId(), forgedClaim.grant(), "done", null,
                tools.jackson.databind.node.JsonNodeFactory.instance.objectNode().set("ids",
                        tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode().add(Ids.newId().toString())));
        assertThat(jdbc.sql("select state, code, tier, material_id from search where id = :id").param("id", forged)
                .query().singleRow()).containsEntry("state", "failed").containsEntry("code", "scope.passage_refused")
                .containsEntry("tier", "campaign").containsEntry("material_id", material);
        // The tables' checks.
        assertThatThrownBy(() -> jdbc.sql("update search set state = 'failed', code = null where id = :id")
                .param("id", searchId).update()).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("search_code_check");
        assertThatThrownBy(() -> jdbc.sql("update search_result set rank = 21 where search_id = :id and rank = 1")
                .param("id", searchId).update()).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("search_result_rank_check");
        // A version no longer ready drops its results on the next read.
        as(owner, () -> materials.discardVersion(version, Instant.now()));
        assertThat(as(owner, () -> searches.find(searchId)).orElseThrow().results()).isEmpty();
        // Only the newest 20 of the owner's searches are kept.
        for (int i = 0; i < 20; i++) {
            OwnerContext.callAs(owner, () -> searches.create(campaign, "Fire Bolt", null, null));
        }
        assertThat(jdbc.sql("select count(*) from search where owner_id = :owner").param("owner", owner)
                .query(Long.class).single()).isEqualTo(20);
        assertThat(jdbc.sql("select count(*) from search where id = :id").param("id", searchId).query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("select count(*) from search_result where search_id = :id").param("id", searchId)
                .query(Long.class).single()).isZero();
    }

    @Test
    void aRecordOfAnApprovedTypeIsStoredWithItsKeyAndFoundByASearchFilteredByItOnPostgres() {
        UUID owner = Ids.newId();
        UUID[] campaignAndSystem = as(owner, () -> {
            library.createStarter(Language.PT_BR);
            Library.Campaign current = library.currentCampaign().orElseThrow();
            return new UUID[] {current.id(), current.system().id()};
        });
        UUID campaign = campaignAndSystem[0];
        UUID system = campaignAndSystem[1];
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID material = Ids.newId();
        UUID version = Ids.newId();
        UUID passage = Ids.newId();
        as(owner, () -> {
            materials.insertMaterial(new MaterialRow(material, owner, vault.encryptText(new Binding("material", "name",
                    material, owner), "Livro"), now, "rulebook", null, campaign));
            materials.insertVersion(new VersionRow(version, owner, material, "ready", "pdf", 1000L, now, now));
            materials.insertPassages(List.of(passage(owner, version, passage, 0, 4)));
            return proposals.store(material, version, List.of(system), List.of(new OntologyProposals.Proposed(
                    "record_type", "Talento", null, List.of(new OntologyProposals.Example(4, "Fire Bolt")))));
        });
        UUID proposal = jdbc.sql("select id from proposal where owner_id = :owner").param("owner", owner)
                .query(UUID.class).single();
        OwnerContext.callAs(owner, () -> decisions.approve(proposal, 0, null)).orElseThrow();
        assertThat(jdbc.sql("select key from record_type where owner_id = :owner and core is null")
                .param("owner", owner).query(String.class).single()).isEqualTo("talento");
        as(owner, () -> extracted.replaceRange(version, 1, 9, List.of(new ExtractedRecords.Extracted("talento",
                "Ataque Poderoso", List.of(new ExtractedRecords.Field("Custo", "1 PM")), List.of(passage), 4))));
        UUID record = jdbc.sql("select id from record where version_id = :version and type = 'talento'")
                .param("version", version).query(UUID.class).single();

        UUID searchId = OwnerContext.callAs(owner, () -> searches.create(campaign, "Golpe", null, null, "talento"));
        assertThat(jdbc.sql("select record_type from search where id = :id").param("id", searchId).query(String.class)
                .single()).isEqualTo("talento");
        UUID jobId = jdbc.sql("select job_id from search where id = :id").param("id", searchId).query(UUID.class)
                .single();
        JobClaims.Claim claim = claims.claim(jobId, null, List.of());
        assertThat(claim.input().path("recordType").asString()).isEqualTo("talento");
        assertThat(claim.input().path("recordTypes").toString()).contains("\"talento\"").contains("\"spell\"");
        claims.report(jobId, claim.claimId(), claim.grant(), "done", null, JsonNodeFactory.instance.objectNode()
                .set("ids", JsonNodeFactory.instance.arrayNode().add(record.toString())));

        Searches.Found found = as(owner, () -> searches.find(searchId)).orElseThrow();
        assertThat(found.state()).isEqualTo("done");
        assertThat(found.recordType()).isEqualTo("talento");
        assertThat(found.results()).extracting(Searches.Result::recordId, Searches.Result::recordType,
                Searches.Result::name).containsExactly(Tuple.tuple(record, "talento", "Ataque Poderoso"));
        assertThat(jdbc.sql("select record_id from search_result where search_id = :id").param("id", searchId)
                .query(UUID.class).single()).isEqualTo(record);
        // The record's type is a key's text; anything else is refused by V40's check.
        assertThatThrownBy(() -> jdbc.sql("update record set type = 'Talento' where id = :id").param("id", record)
                .update()).isInstanceOf(RuntimeException.class).hasStackTraceContaining("record_type_check");
    }

    @Test
    void conversationsByAreaAreListedRenamedAndCarryTheirHistoryOnPostgres() {
        UUID owner = Ids.newId();
        UUID campaign = as(owner, () -> {
            library.createStarter(Language.PT_BR);
            return library.currentCampaign().orElseThrow().id();
        });
        // V30: the unique key on (owner, campaign) is gone, and the list's index is there.
        assertThat(jdbc.sql("select count(*) from pg_constraint where conname = 'conversation_owner_campaign_key'")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from pg_indexes where indexname = 'conversation_owner_campaign_area'")
                .query(Long.class).single()).isOne();

        Conversations.Asked first = OwnerContext.callAs(owner, () -> conversations.ask(campaign,
                "Que regras já existem sobre fabricar itens?", null, Area.RULES));
        Conversations.Asked other = OwnerContext.callAs(owner, () -> conversations.ask(campaign, QUESTION, null,
                Area.QUESTIONS));
        Conversations.Asked latest = OwnerContext.callAs(owner, () -> conversations.ask(campaign, "E agora?"));
        Conversations.Asked again = OwnerContext.callAs(owner, () -> conversations.ask(campaign, "Mais uma?",
                first.conversationId(), null));

        assertThat(latest.conversationId()).isEqualTo(other.conversationId());
        assertThat(again.conversationId()).isEqualTo(first.conversationId());
        Map<String, Object> row = jdbc.sql("select area, name, version, created_at, last_asked_at from conversation"
                + " where id = :id").param("id", first.conversationId()).query().singleRow();
        assertThat(row).containsEntry("area", "rules").containsEntry("name", null).containsEntry("version", 0L);
        assertThat(((java.sql.Timestamp) row.get("last_asked_at")).toInstant())
                .isAfter(((java.sql.Timestamp) row.get("created_at")).toInstant());
        Conversations.Page rules = as(owner, () -> conversations.list(campaign, Area.RULES, 0, 20)).orElseThrow();
        assertThat(rules.total()).isOne();
        assertThat(rules.items()).extracting(Conversations.Summary::id, Conversations.Summary::name,
                Conversations.Summary::questions).containsExactly(org.assertj.core.groups.Tuple.tuple(
                        first.conversationId(), "Que regras já existem sobre fabricar itens?", 2L));
        Conversations.Page questions = as(owner, () -> conversations.list(campaign, Area.QUESTIONS, 0, 20))
                .orElseThrow();
        assertThat(questions.items()).extracting(Conversations.Summary::id).containsExactly(other.conversationId());
        assertThat(as(Ids.newId(), () -> conversations.list(campaign, Area.RULES, 0, 20))).isEmpty();

        // A rename on its version, stored encrypted; a stale one changes nothing.
        Conversations.Opened renamed = OwnerContext.callAs(owner, () -> conversations.rename(first.conversationId(), 0,
                " Fabricar itens ")).orElseThrow();
        assertThat(renamed.name()).isEqualTo("Fabricar itens");
        assertThat(renamed.version()).isOne();
        byte[] name = jdbc.sql("select name from conversation where id = :id").param("id", first.conversationId())
                .query((result, number) -> result.getBytes(1)).single();
        assertThat(new String(name, StandardCharsets.ISO_8859_1)).doesNotContain("Fabricar");
        assertThatThrownBy(() -> OwnerContext.callAs(owner, () -> conversations.rename(first.conversationId(), 0,
                "Outro"))).isInstanceOfSatisfying(ApiException.class, refused ->
                        assertThat(refused.problem().code()).isEqualTo("request.stale_version"));
        // Asking never raises the version.
        OwnerContext.callAs(owner, () -> conversations.ask(campaign, "Ainda?", first.conversationId(), null));
        assertThat(jdbc.sql("select version from conversation where id = :id").param("id", first.conversationId())
                .query(Long.class).single()).isOne();
        assertThat(as(Ids.newId(), () -> conversations.conversation(first.conversationId()))).isEmpty();
        assertThatThrownBy(() -> jdbc.sql("update conversation set area = 'search' where id = :id")
                .param("id", first.conversationId()).update()).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("conversation_area_check");

        // The history: the done turn's question and answer, through the claim of the next question.
        UUID firstJob = jdbc.sql("select job_id from turn where id = :id").param("id", first.turnId())
                .query(UUID.class).single();
        JobClaims.Claim opening = claims.claim(firstJob, null, List.of("azure-openai"));
        assertThat(opening.input().path("history")).isEmpty();
        claims.events(firstJob, opening.claimId(), opening.grant(), new JobEventBatch(opening.claimId(), 1,
                List.of(JobEvent.delta("Duas regras: "), JobEvent.delta("forja e alquimia."))), List.of());
        claims.report(firstJob, opening.claimId(), opening.grant(), "done", null, null);
        UUID secondJob = jdbc.sql("select job_id from turn where id = :id").param("id", again.turnId())
                .query(UUID.class).single();
        JobClaims.Claim next = claims.claim(secondJob, null, List.of("azure-openai"));
        assertThat(next.input().path("history")).hasSize(1);
        assertThat(next.input().path("history").get(0).path("question").asString())
                .isEqualTo("Que regras já existem sobre fabricar itens?");
        assertThat(next.input().path("history").get(0).path("answer").asString())
                .isEqualTo("Duas regras: forja e alquimia.");
        List<Conversations.StoredTurn> turns = as(owner, () -> conversations.turns(first.conversationId()))
                .orElseThrow();
        assertThat(turns).extracting(Conversations.StoredTurn::id).startsWith(first.turnId(), again.turnId());
        assertThat(turns.getFirst().events()).extracting(Conversations.StoredEvent::type)
                .containsExactly("delta", "delta", "done");
    }

    @Test
    void aDismissedTurnLeavesTheTurnsTheCountsAndTheHistoryOnPostgresUntilItsUndo() {
        UUID owner = Ids.newId();
        UUID campaign = as(owner, () -> {
            library.createStarter(Language.PT_BR);
            return library.currentCampaign().orElseThrow().id();
        });
        Conversations.Asked first = OwnerContext.callAs(owner, () -> conversations.ask(campaign, "Pergunta 1?", null,
                Area.QUESTIONS));
        UUID conversation = first.conversationId();
        answer(first.turnId(), "Resposta 1.");
        UUID second = OwnerContext.callAs(owner, () -> conversations.ask(campaign, "Pergunta 2?", conversation, null))
                .turnId();
        answer(second, "Resposta 2.");
        UUID third = OwnerContext.callAs(owner, () -> conversations.ask(campaign, "Pergunta 3?", conversation, null))
                .turnId();
        answer(third, "Resposta 3.");

        OwnerContext.runAs(owner, () -> conversations.dismiss(second));

        // Left out of the turns, every count and the next question's history.
        assertThat(as(owner, () -> conversations.turns(conversation)).orElseThrow())
                .extracting(Conversations.StoredTurn::id).containsExactly(first.turnId(), third);
        assertQuestions(owner, campaign, conversation, 2);
        UUID fourth = OwnerContext.callAs(owner, () -> conversations.ask(campaign, "Pergunta 4?", conversation, null))
                .turnId();
        assertThat(historyOf(fourth)).containsExactly("Pergunta 1?", "Pergunta 3?");

        // Twice: the first dismissal's time is kept.
        Instant dismissedAt = dismissedAt(second);
        assertThat(dismissedAt).isNotNull();
        OwnerContext.runAs(owner, () -> conversations.dismiss(second));
        assertThat(dismissedAt(second)).isEqualTo(dismissedAt);

        // The undo: listed, counted and in the history again.
        OwnerContext.runAs(owner, () -> conversations.restore(second));
        assertThat(dismissedAt(second)).isNull();
        assertThat(as(owner, () -> conversations.turns(conversation)).orElseThrow())
                .extracting(Conversations.StoredTurn::id).containsExactly(first.turnId(), second, third, fourth);
        assertQuestions(owner, campaign, conversation, 4);
        UUID fifth = OwnerContext.callAs(owner, () -> conversations.ask(campaign, "Pergunta 5?", conversation, null))
                .turnId();
        assertThat(historyOf(fifth)).containsExactly("Pergunta 1?", "Pergunta 2?", "Pergunta 3?");
    }

    @Test
    void theSavedAnswersAreListedAcrossCampaignsNewestFirstLeavingOutArchivedAndDismissedOnPostgres() {
        UUID owner = Ids.newId();
        Library.Campaign starter = as(owner, () -> {
            library.createStarter(Language.PT_BR);
            return library.currentCampaign().orElseThrow();
        });
        UUID campaign = starter.id();
        UUID other = as(owner, () -> library.createCampaign(starter.system().id(), "A Coroa Partida")).orElseThrow()
                .id();
        UUID here = citedPassage(owner, campaign);
        UUID thereCited = citedPassage(owner, other);
        Conversations.Asked first = OwnerContext.callAs(owner, () -> conversations.ask(campaign, "Pergunta 1?", null,
                Area.QUESTIONS));
        answer(first.turnId(), "**Resposta** 1.", here);
        UUID second = OwnerContext.callAs(owner, () -> conversations.ask(campaign, "Pergunta 2?",
                first.conversationId(), null)).turnId();
        answer(second, "Resposta 2.", here);
        // A silent answer (no reference: the sources said nothing) is not saved.
        UUID silent = OwnerContext.callAs(owner, () -> conversations.ask(campaign, "Pergunta muda?",
                first.conversationId(), null)).turnId();
        answer(silent, "Nada.");
        Conversations.Asked elsewhere = OwnerContext.callAs(owner, () -> conversations.ask(other, "Pergunta lá?", null,
                Area.RULES));
        answer(elsewhere.turnId(), "Resposta lá.", thereCited);
        Conversations.Asked running = OwnerContext.callAs(owner, () -> conversations.ask(campaign, "Ainda?",
                first.conversationId(), null));
        // Another owner's saved answer is never listed.
        UUID stranger = Ids.newId();
        UUID theirCampaign = as(stranger, () -> {
            library.createStarter(Language.EN);
            return library.currentCampaign().orElseThrow().id();
        });
        UUID theirPassage = citedPassage(stranger, theirCampaign);
        UUID theirs = OwnerContext.callAs(stranger, () -> conversations.ask(theirCampaign, "Theirs?")).turnId();
        answer(theirs, "Their answer.", theirPassage);
        OwnerContext.runAs(stranger, () -> conversations.bookmark(theirs));

        for (UUID turn : List.of(first.turnId(), elsewhere.turnId(), second)) {
            OwnerContext.runAs(owner, () -> conversations.bookmark(turn));
            sleep();
        }
        assertThatThrownBy(() -> OwnerContext.runAs(owner, () -> conversations.bookmark(running.turnId())))
                .isInstanceOfSatisfying(ApiException.class, refused ->
                        assertThat(refused.problem().code()).isEqualTo("conversation.turn_not_done"));
        assertThat(bookmarkedAt(running.turnId())).isNull();
        assertThatThrownBy(() -> OwnerContext.runAs(owner, () -> conversations.bookmark(silent)))
                .isInstanceOfSatisfying(ApiException.class, refused ->
                        assertThat(refused.problem().code()).isEqualTo("conversation.turn_not_done"));
        assertThat(bookmarkedAt(silent)).isNull();
        assertThatThrownBy(() -> OwnerContext.runAs(owner, () -> conversations.bookmark(theirs)))
                .isInstanceOfSatisfying(ApiException.class, refused ->
                        assertThat(refused.problem().code()).isEqualTo("request.not_found"));

        Conversations.SavedPage page = as(owner, () -> conversations.saved(0, 20));
        assertThat(page.items()).extracting(Conversations.Saved::turnId)
                .containsExactly(second, elsewhere.turnId(), first.turnId());
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.nextOffset()).isNull();
        Conversations.Saved there = page.items().get(1);
        assertThat(there.campaignId()).isEqualTo(other);
        assertThat(there.campaignName()).isEqualTo("A Coroa Partida");
        assertThat(there.area()).isEqualTo(Area.RULES);
        assertThat(there.conversationName()).isEqualTo("Pergunta lá?");
        assertThat(there.savedAt()).isEqualTo(bookmarkedAt(elsewhere.turnId()));
        assertThat(page.items().get(2).excerpt()).isEqualTo("Resposta 1.");
        assertThat(page.items().get(2).conversationName()).isEqualTo("Pergunta 1?");
        // Paged by offset, newest saved first.
        Conversations.SavedPage firstPage = as(owner, () -> conversations.saved(0, 2));
        assertThat(firstPage.items()).extracting(Conversations.Saved::turnId).containsExactly(second,
                elsewhere.turnId());
        assertThat(firstPage.nextOffset()).isEqualTo(2);
        assertThat(firstPage.total()).isEqualTo(3);
        assertThat(as(owner, () -> conversations.saved(2, 2)).items()).extracting(Conversations.Saved::turnId)
                .containsExactly(first.turnId());
        // The bookmark is in the turns read, and gives no standing: the next history is the same.
        assertThat(as(owner, () -> conversations.turns(first.conversationId())).orElseThrow())
                .extracting(Conversations.StoredTurn::bookmarked).containsExactly(true, true, false, false);

        // A dismissed answer and an archived campaign's are left out of the list and the total.
        Instant savedAt = bookmarkedAt(second);
        OwnerContext.runAs(owner, () -> conversations.dismiss(second));
        long version = as(owner, () -> library.campaign(other)).orElseThrow().version();
        as(owner, () -> library.changeCampaign(other, version, null, true));
        Conversations.SavedPage narrowed = as(owner, () -> conversations.saved(0, 20));
        assertThat(narrowed.items()).extracting(Conversations.Saved::turnId).containsExactly(first.turnId());
        assertThat(narrowed.total()).isOne();
        // Both come back: the dismissal undone keeps the time it was saved.
        OwnerContext.runAs(owner, () -> conversations.restore(second));
        as(owner, () -> library.changeCampaign(other, version + 1, null, false));
        assertThat(as(owner, () -> conversations.saved(0, 20)).items()).extracting(Conversations.Saved::turnId)
                .containsExactly(second, elsewhere.turnId(), first.turnId());
        assertThat(bookmarkedAt(second)).isEqualTo(savedAt);

        // Unsaved: it leaves the list; a second unsave and one of a running turn change nothing.
        OwnerContext.runAs(owner, () -> conversations.unbookmark(second));
        OwnerContext.runAs(owner, () -> conversations.unbookmark(second));
        OwnerContext.runAs(owner, () -> conversations.unbookmark(running.turnId()));
        assertThat(bookmarkedAt(second)).isNull();
        assertThat(as(owner, () -> conversations.saved(0, 20)).total()).isEqualTo(2);
        // The conversation being deleted: its saved answer leaves the list at once.
        assertThat(as(owner, () -> conversations.delete(first.conversationId()))).isTrue();
        assertThat(as(owner, () -> conversations.saved(0, 20)).items()).extracting(Conversations.Saved::turnId)
                .containsExactly(elsewhere.turnId());
        assertThat(as(stranger, () -> conversations.saved(0, 20)).items()).extracting(Conversations.Saved::turnId)
                .containsExactly(theirs);
    }

    @Test
    void aDraftIsATurnOfKindDraftWithAContentDraftJobAndAQuestionDefaultsToQuestionOnPostgres() {
        UUID owner = Ids.newId();
        UUID campaign = as(owner, () -> {
            library.createStarter(Language.PT_BR);
            return library.currentCampaign().orElseThrow().id();
        });
        readyVersion(owner, campaign);

        Conversations.Asked question = OwnerContext.callAs(owner, () -> conversations.ask(campaign, QUESTION));
        Conversations.Asked draft = OwnerContext.callAs(owner, () -> conversations.ask(campaign,
                "Um encontro para a cripta", question.conversationId(), null, TurnKind.DRAFT));

        // V47: the kind is stored with the turn, and its job is the draft's.
        Map<String, Object> drafted = jdbc.sql("select t.kind as turn_kind, j.kind as job_kind from turn t"
                + " join job j on j.id = t.job_id where t.id = :id").param("id", draft.turnId()).query().singleRow();
        assertThat(drafted).containsEntry("turn_kind", "draft").containsEntry("job_kind", DraftJobs.KIND);
        assertThat(jdbc.sql("select kind from turn where id = :id").param("id", question.turnId())
                .query(String.class).single()).isEqualTo("question");
        // A row written without the column (the previous release's insert) gets the default, read back as a question.
        UUID oldTurn = Ids.newId();
        jdbc.sql("insert into turn (id, owner_id, conversation_id, job_id, question, language, campaign_tier,"
                + " system_tier, other_tier, model_knowledge, created_at) select :id, owner_id, conversation_id, :job,"
                + " question, language, campaign_tier, system_tier, other_tier, model_knowledge, created_at + interval"
                + " '1 second' from turn where id = :from").param("id", oldTurn).param("job", Ids.newId())
                .param("from", question.turnId())
                .update();
        assertThat(jdbc.sql("select kind from turn where id = :id").param("id", oldTurn).query(String.class).single())
                .isEqualTo("question");
        assertThat(as(owner, () -> store.findTurn(oldTurn)).orElseThrow().kind()).isEqualTo(TurnRow.QUESTION);
        assertThatThrownBy(() -> jdbc.sql("update turn set kind = 'proposal' where id = :id")
                .param("id", draft.turnId()).update()).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("turn_kind_check");
        jdbc.sql("delete from turn where id = :id").param("id", oldTurn).update();

        // The draft's claim reads the same input as a question's; it is read back with its kind.
        answer(draft.turnId(), "## Encontro na cripta");
        List<Conversations.StoredTurn> turns = as(owner, () -> conversations.turns(question.conversationId()))
                .orElseThrow();
        assertThat(turns).extracting(Conversations.StoredTurn::id, Conversations.StoredTurn::kind)
                .containsExactly(Tuple.tuple(question.turnId(), TurnKind.QUESTION),
                        Tuple.tuple(draft.turnId(), TurnKind.DRAFT));
        // A draft outside the questions area records nothing.
        Conversations.Asked rules = OwnerContext.callAs(owner, () -> conversations.ask(campaign, "Regras?", null,
                Area.RULES));
        long before = jdbc.sql("select count(*) from turn where owner_id = :owner").param("owner", owner)
                .query(Long.class).single();
        assertThatThrownBy(() -> OwnerContext.callAs(owner, () -> conversations.ask(campaign, "Um item",
                rules.conversationId(), null, TurnKind.DRAFT))).isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.problem().code()).isEqualTo("request.invalid");
                    assertThat(refused.problem().params()).containsEntry("field", "kind");
                });
        assertThat(jdbc.sql("select count(*) from turn where owner_id = :owner").param("owner", owner)
                .query(Long.class).single()).isEqualTo(before);
        // Leave no job behind.
        answer(question.turnId(), "Resposta.");
        answer(rules.turnId(), "Resposta.");
    }

    private static void sleep() {
        try {
            Thread.sleep(2);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Instant bookmarkedAt(UUID turnId) {
        java.time.OffsetDateTime at = jdbc.sql("select bookmarked_at from turn where id = :id").param("id", turnId)
                .query(java.time.OffsetDateTime.class).list().getFirst();
        return at == null ? null : at.toInstant();
    }

    /** An accepted material of the owner's campaign with one passage, which an answer can cite (Story 3.12). */
    private UUID citedPassage(UUID owner, UUID campaign) {
        UUID passage = Ids.newId();
        as(owner, () -> {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            UUID material = Ids.newId();
            UUID version = Ids.newId();
            materials.insertMaterial(new MaterialRow(material, owner, vault.encryptText(new Binding("material", "name",
                    material, owner), "Livro"), now, "rulebook", null, campaign));
            materials.insertVersion(new VersionRow(version, owner, material, "ready", "pdf", 1000L, now, now));
            materials.insertPassages(List.of(passage(owner, version, passage, 0, 4)));
            return null;
        });
        return passage;
    }

    /** As {@link #answer(UUID, String)}, citing {@code passage} at the text's start: an answer with a reference. */
    private void answer(UUID turnId, String text, UUID passage) {
        UUID job = jdbc.sql("select job_id from turn where id = :id").param("id", turnId).query(UUID.class).single();
        JobClaims.Claim claim = claims.claim(job, null, List.of("azure-openai"));
        claims.events(job, claim.claimId(), claim.grant(), new JobEventBatch(claim.claimId(), 1,
                List.of(JobEvent.delta(text), JobEvent.citation(passage, 0))), List.of());
        claims.report(job, claim.claimId(), claim.grant(), "done", null, null);
    }

    /** Claims the turn's job, posts {@code text} as its answer and reports it done. */
    private void answer(UUID turnId, String text) {
        UUID job = jdbc.sql("select job_id from turn where id = :id").param("id", turnId).query(UUID.class).single();
        JobClaims.Claim claim = claims.claim(job, null, List.of("azure-openai"));
        claims.events(job, claim.claimId(), claim.grant(), new JobEventBatch(claim.claimId(), 1,
                List.of(JobEvent.delta(text))), List.of());
        claims.report(job, claim.claimId(), claim.grant(), "done", null, null);
    }

    /** The questions of the history the claim of the turn's job is given. */
    private List<String> historyOf(UUID turnId) {
        UUID job = jdbc.sql("select job_id from turn where id = :id").param("id", turnId).query(UUID.class).single();
        List<String> questions = new java.util.ArrayList<>();
        claims.claim(job, null, List.of("azure-openai")).input().path("history")
                .forEach(item -> questions.add(item.path("question").asString()));
        return questions;
    }

    private Instant dismissedAt(UUID turnId) {
        java.time.OffsetDateTime at = jdbc.sql("select dismissed_at from turn where id = :id").param("id", turnId)
                .query(java.time.OffsetDateTime.class).list().getFirst();
        return at == null ? null : at.toInstant();
    }

    /** The conversation's question count in its list, as the campaign's conversation, and in its deletion preview. */
    private void assertQuestions(UUID owner, UUID campaign, UUID conversation, long questions) {
        assertThat(as(owner, () -> conversations.list(campaign, Area.QUESTIONS, 0, 20)).orElseThrow().items())
                .filteredOn(item -> item.id().equals(conversation)).extracting(Conversations.Summary::questions)
                .containsExactly(questions);
        Conversations.OfCampaign ofCampaign = as(owner, () -> conversations.ofCampaign(campaign)).orElseThrow();
        assertThat(ofCampaign.id()).isEqualTo(conversation);
        assertThat(ofCampaign.questions()).isEqualTo(questions);
        assertThat(as(owner, () -> conversations.deletion(conversation)).orElseThrow()).isEqualTo(questions);
    }

    @Test
    void theListIsMostRecentlyAskedFirstOnPostgresAndAnAreaLessQuestionContinuesTheMostRecentlyAskedOne() {
        UUID owner = Ids.newId();
        UUID campaign = as(owner, () -> {
            library.createStarter(Language.PT_BR);
            return library.currentCampaign().orElseThrow().id();
        });
        Conversations.Asked older = OwnerContext.callAs(owner, () -> conversations.ask(campaign, "Primeira?", null,
                Area.QUESTIONS));
        Conversations.Asked newer = OwnerContext.callAs(owner, () -> conversations.ask(campaign, "Segunda?", null,
                Area.QUESTIONS));
        // The one made first is asked again: it is now the most recently asked.
        OwnerContext.callAs(owner, () -> conversations.ask(campaign, "De novo?", older.conversationId(), null));

        assertThat(as(owner, () -> conversations.list(campaign, Area.QUESTIONS, 0, 20)).orElseThrow().items())
                .extracting(Conversations.Summary::id).containsExactly(older.conversationId(), newer.conversationId());
        Conversations.Page second = as(owner, () -> conversations.list(campaign, Area.QUESTIONS, 1, 20)).orElseThrow();
        assertThat(second.items()).extracting(Conversations.Summary::id).containsExactly(newer.conversationId());
        assertThat(second.total()).isEqualTo(2);
        Conversations.Page first = as(owner, () -> conversations.list(campaign, Area.QUESTIONS, 0, 1)).orElseThrow();
        assertThat(first.nextOffset()).isOne();
        Conversations.Asked areaLess = OwnerContext.callAs(owner, () -> conversations.ask(campaign, "Sem área?"));
        assertThat(areaLess.conversationId()).isEqualTo(older.conversationId());
    }

    @Test
    void aConversationsTurnsAreItsNewest200OldestFirstOnPostgres() {
        UUID owner = Ids.newId();
        UUID campaign = as(owner, () -> {
            library.createStarter(Language.PT_BR);
            return library.currentCampaign().orElseThrow().id();
        });
        UUID conversation = OwnerContext.callAs(owner, () -> conversations.ask(campaign, "Pergunta 0", null,
                Area.RULES)).conversationId();
        UUID firstTurn = jdbc.sql("select id from turn where conversation_id = :id").param("id", conversation)
                .query(UUID.class).single();
        Instant start = Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(10);
        List<UUID> added = as(owner, () -> {
            List<UUID> ids = new java.util.ArrayList<>();
            for (int number = 1; number <= 200; number++) {
                UUID turnId = Ids.newId();
                store.insertTurn(new TurnRow(turnId, owner, conversation, Ids.newId(), vault.encryptText(
                        Conversations.question(turnId, owner), "Pergunta " + number), "pt-BR", true, true, false, false,
                        java.util.Set.of(), start.plusSeconds(number), null, null, null, null));
                ids.add(turnId);
            }
            return ids;
        });

        List<Conversations.StoredTurn> turns = as(owner, () -> conversations.turns(conversation)).orElseThrow();

        assertThat(turns).hasSize(200);
        assertThat(turns).extracting(Conversations.StoredTurn::id).doesNotContain(firstTurn).containsExactlyElementsOf(added);
        assertThat(turns.getFirst().question()).isEqualTo("Pergunta 1");
        assertThat(turns.getLast().question()).isEqualTo("Pergunta 200");
    }

    @Test
    void aRenameWhoseRowMovedAfterItWasReadIsAConflictAtTheFlushAndChangesNothing() {
        UUID owner = Ids.newId();
        UUID campaign = as(owner, () -> {
            library.createStarter(Language.PT_BR);
            return library.currentCampaign().orElseThrow().id();
        });
        UUID conversation = OwnerContext.callAs(owner, () -> conversations.ask(campaign, QUESTION, null,
                Area.QUESTIONS)).conversationId();
        TransactionTemplate other = new TransactionTemplate(transactions);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Throwable refused = OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status -> {
            // Read at version 0 in this transaction; then another transaction raises the version and commits.
            assertThat(store.findConversation(conversation).orElseThrow().version()).isZero();
            other.executeWithoutResult(moved -> jdbc.sql("update conversation set version = version + 1 where id = :id")
                    .param("id", conversation).update());
            status.setRollbackOnly();
            try {
                store.renameConversation(conversation, 0, new byte[] {1, 2, 3});
                return null;
            } catch (RuntimeException conflict) {
                return conflict;
            }
        }));

        assertThat(refused).isInstanceOf(ConversationConflict.class);
        Map<String, Object> row = jdbc.sql("select name, version from conversation where id = :id")
                .param("id", conversation).query().singleRow();
        assertThat(row).containsEntry("name", null).containsEntry("version", 1L);
    }

    private PassageRow passage(UUID owner, UUID version, UUID id, int ordinal, int page) {
        String text = "Fire Bolt deals 1d10 Fire damage " + ordinal;
        byte[] place = ("{\"sectionPath\":[\"Magias\",\"Fire Bolt\"],\"range\":[0," + text.length() + "]}")
                .getBytes(StandardCharsets.UTF_8);
        return new PassageRow(id, owner, version, ordinal, page,
                vault.encryptText(new Binding("passage", "text", id, owner), text),
                vault.encrypt(new Binding("passage", "place", id, owner), place), Instant.now()
                        .truncatedTo(ChronoUnit.MICROS));
    }

    private void readyVersion(UUID owner, UUID campaign) {
        as(owner, () -> {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            UUID material = Ids.newId();
            materials.insertMaterial(new MaterialRow(material, owner, vault.encryptText(new Binding("material", "name",
                    material, owner), "Livro"), now, "rulebook", null, campaign));
            materials.insertVersion(new VersionRow(Ids.newId(), owner, material, "ready", "pdf", 1000L, now, now));
            return null;
        });
    }

    private <T> T as(UUID owner, Supplier<T> work) {
        return OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status -> work.get()));
    }
}

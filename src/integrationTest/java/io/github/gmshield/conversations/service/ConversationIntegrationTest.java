package io.github.gmshield.conversations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.jobs.service.CallUsage;
import io.github.gmshield.jobs.service.JobClaims;
import io.github.gmshield.jobs.service.JobEvent;
import io.github.gmshield.jobs.service.JobEventBatch;
import io.github.gmshield.library.service.Language;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.data.VersionRow;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 2.4 against a real PostgreSQL and Redis (test mode): a question recorded with its job in one transaction, the
 * conversation made once per campaign, the claim of its {@code question.answer} job, numbered event batches stored once
 * per claim and number with their sequences, a gap refused with nothing stored, the usage recorded once per call id
 * ({@code ON CONFLICT DO NOTHING}), the final {@code done}, the stored events read back after a sequence, the
 * encrypted columns, and another owner. The same rows without a database are in {@code ConversationFlowTest}.
 * Story 2.5: the references of a completed turn stored by value in {@code turn_reference} (passage ids as
 * {@code uuid[]}, the name and the section path encrypted), read back, and marked removed after a discard.
 */
@SpringBootTest(properties = {"GMSHIELD_MODE=test", "gmshield.jobs.sweep.enabled=false",
    "gmshield.materials.upload-sweep.enabled=false",
    "gmshield.materials.version-removal-sweep.enabled=false",
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
    private JobClaims claims;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private Vault vault;
    @Autowired
    private PlatformTransactionManager transactions;

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
        List<CallUsage> usage = List.of(new CallUsage(call, "chatcmpl-it", 100, 10, 0, 7, 2));
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
                JobEvent.delta("Fire Bolt causa 1d10."), JobEvent.citation(passages.get(2), 9),
                JobEvent.citation(passages.get(1), 20), JobEvent.citation(Ids.newId(), 20),
                JobEvent.citation(passages.get(0), 20))), List.of());
        claims.report(jobId, claim.claimId(), claim.grant(), "done", null, null);

        List<TurnReference> references = as(owner, () -> conversations.references(asked.turnId()));

        assertThat(references).extracting(TurnReference::number, TurnReference::page, TurnReference::passageIds,
                TurnReference::removed).containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, 9, List.of(passages.get(2)), false),
                        org.assertj.core.groups.Tuple.tuple(2, 4, List.of(passages.get(1), passages.get(0)), false));
        assertThat(references.getFirst().materialName()).isEqualTo("SRD 5.2.1");
        assertThat(references.getFirst().sectionPath()).containsExactly("Magias", "Fire Bolt");
        List<Map<String, Object>> rows = jdbc.sql("select owner_id, number, version_id, material_id, page,"
                + " material_name, section_path from turn_reference where turn_id = :turn order by number")
                .param("turn", asked.turnId()).query().listOfRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().get("owner_id")).isEqualTo(owner);
        assertThat(rows.getFirst().get("version_id")).isEqualTo(version);
        assertThat(rows.getFirst().get("material_id")).isEqualTo(material);
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

        assertThat(as(owner, () -> conversations.references(asked.turnId()))).extracting(TurnReference::removed)
                .containsExactly(true, true);
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

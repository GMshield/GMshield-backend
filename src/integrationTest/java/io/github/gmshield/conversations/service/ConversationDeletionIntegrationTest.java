package io.github.gmshield.conversations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.jobs.service.JobClaims;
import io.github.gmshield.library.service.Language;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.shared.service.ApiException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
 * Story 3.10 part B against a real PostgreSQL and Redis (test mode): V31, a conversation's deletion from the GM's delete
 * to the end of its removal. From the commit its row is {@code deleting} and every read through the service finds
 * nothing (the conversation, its turns, its campaign's list, total and latest questions conversation, a question naming
 * it, its turns and their events), while the other conversation answers. The removal leaves no row of it, its turns,
 * their event batches, materials-off rows or references, and touches nothing else (the other conversation, its
 * campaign, the searches, the usage); it waits for a running answer's claim, it is finished by the sweep after a pass
 * stopped mid-way, and a campaign deleted meanwhile takes the conversation, after which both deletions end done.
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
class ConversationDeletionIntegrationTest {

    @Autowired
    private Library library;
    @Autowired
    private Conversations conversations;
    @Autowired
    private Searches searches;
    @Autowired
    private SystemDeletion deletion;
    @Autowired
    private JobClaims claims;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactions;

    /** An owner with the starter system and campaign, as a first sign-in makes them. */
    private record Owner(UUID id, UUID campaign, UUID system) {
    }

    @Test
    void aDeletedConversationLeavesNoRowOfItAndTouchesNothingElseOnPostgres() throws Exception {
        Owner a = owner();
        Conversations.Asked kept = as(a.id(), () -> conversations.ask(a.campaign(), "Quem age primeiro?", null,
                Area.QUESTIONS));
        UUID other = kept.conversationId();
        Thread.sleep(2);
        Conversations.Asked first = as(a.id(), () -> conversations.ask(a.campaign(), "Quanto dano?", null,
                Area.QUESTIONS));
        UUID target = first.conversationId();
        UUID second = as(a.id(), () -> conversations.ask(a.campaign(), "E o alcance?", target, null)).turnId();
        UUID third = as(a.id(), () -> conversations.ask(a.campaign(), "E a duração?", target, null)).turnId();
        List<UUID> going = List.of(first.turnId(), second, third);
        for (UUID turn : going) {
            stored(a.id(), turn);
        }
        stored(a.id(), kept.turnId());
        UUID search = as(a.id(), () -> searches.create(a.campaign(), "Quanto dano", null, null));
        assertThat(as(a.id(), () -> conversations.deletion(target))).contains(3L);
        assertThat(as(Ids.newId(), () -> conversations.deletion(target))).isEmpty();
        assertThat(as(Ids.newId(), () -> conversations.delete(target))).isFalse();
        assertThat(as(a.id(), () -> conversations.ofCampaign(a.campaign())).orElseThrow().id()).isEqualTo(target);
        assertThat(as(a.id(), () -> deletion.previewCampaign(a.campaign())).orElseThrow().conversations())
                .isEqualTo(2);

        assertThat(as(a.id(), () -> conversations.delete(target))).isTrue();

        // From the commit: deleting, not found, and gone from the list, its total and the counts.
        assertThat(jdbc.sql("select state from conversation where id = ?").param(target).query(String.class).single())
                .isEqualTo("deleting");
        assertThat(jdbc.sql("select state from conversation where id = ?").param(other).query(String.class).single())
                .isEqualTo("ready");
        assertThat(as(a.id(), () -> conversations.conversation(target))).isEmpty();
        assertThat(as(a.id(), () -> conversations.turns(target))).isEmpty();
        assertThat(as(a.id(), () -> conversations.deletion(target))).isEmpty();
        assertThat(as(a.id(), () -> conversations.rename(target, 0, "Outra"))).isEmpty();
        assertThat(as(a.id(), () -> conversations.delete(target))).isFalse();
        assertThat(as(a.id(), () -> conversations.turn(first.turnId()))).isEmpty();
        assertThat(as(a.id(), () -> conversations.eventsAfter(first.turnId(), 0))).isEmpty();
        assertThatThrownBy(() -> as(a.id(), () -> conversations.ask(a.campaign(), "Ainda?", target, null)))
                .isInstanceOfSatisfying(ApiException.class, refused ->
                        assertThat(refused.problem().code()).isEqualTo("request.not_found"));
        assertThatThrownBy(() -> as(a.id(), () -> {
            conversations.stop(first.turnId());
            return null;
        })).isInstanceOfSatisfying(ApiException.class, refused ->
                assertThat(refused.problem().code()).isEqualTo("request.not_found"));
        Conversations.Page page = as(a.id(), () -> conversations.list(a.campaign(), Area.QUESTIONS, 0, 20))
                .orElseThrow();
        assertThat(page.items()).extracting(Conversations.Summary::id).containsExactly(other);
        assertThat(page.total()).isOne();
        assertThat(as(a.id(), () -> conversations.ofCampaign(a.campaign())).orElseThrow().id()).isEqualTo(other);
        assertThat(as(a.id(), () -> deletion.previewCampaign(a.campaign())).orElseThrow().conversations()).isOne();
        // The area-less question continues the next latest questions conversation.
        assertThat(as(a.id(), () -> conversations.ask(a.campaign(), "E agora?")).conversationId()).isEqualTo(other);
        Map<String, Object> row = jdbc.sql("select id, owner_id, kind, done_at from deletion where target_id = ?")
                .param(target).query().singleRow();
        assertThat(row).containsEntry("owner_id", a.id()).containsEntry("kind", "conversation")
                .containsEntry("done_at", null);
        UUID deletionId = (UUID) row.get("id");
        assertThat(count("turn", "conversation_id = ?", target)).isEqualTo(3);
        List<UUID> jobs = going.stream().map(turn -> jdbc.sql("select job_id from turn where id = ?").param(turn)
                .query(UUID.class).single()).toList();

        assertThat(deletion.run(a.id(), deletionId)).isTrue();

        assertThat(count("conversation", "id = ?", target)).isZero();
        for (UUID turn : going) {
            for (String table : List.of("turn_event_batch", "turn_reference", "turn_material_off")) {
                assertThat(count(table, "turn_id = ?", turn)).as(table).isZero();
            }
            assertThat(count("turn", "id = ?", turn)).isZero();
        }
        for (UUID job : jobs) {
            // The usage of each model call stays: it names a job, never a turn.
            assertThat(count("model_call_usage", "job_id = ?", job)).isOne();
        }
        assertThat(count("conversation", "id = ?", other)).isOne();
        assertThat(count("turn", "conversation_id = ?", other)).isEqualTo(2);
        for (String table : List.of("turn_event_batch", "turn_reference", "turn_material_off")) {
            assertThat(count(table, "turn_id = ?", kept.turnId())).as(table).isOne();
        }
        assertThat(count("search", "id = ?", search)).isOne();
        assertThat(count("campaign", "id = ?", a.campaign())).isOne();
        assertThat(jdbc.sql("select done_at from deletion where id = ?").param(deletionId).query().singleRow()
                .get("done_at")).isNotNull();
        assertThat(deletion.run(a.id(), deletionId)).isFalse();
    }

    @Test
    void theRemovalWaitsForARunningClaimOnPostgres() throws Exception {
        Owner b = owner();
        Conversations.Asked asked = as(b.id(), () -> conversations.ask(b.campaign(), "Quanto dano?", null, Area.RULES));
        UUID job = jdbc.sql("select job_id from turn where id = ?").param(asked.turnId()).query(UUID.class).single();
        JobClaims.Claim claim = claims.claim(job, null, List.of("azure-openai"));

        assertThat(as(b.id(), () -> conversations.delete(asked.conversationId()))).isTrue();

        assertThat(jdbc.sql("select state from job where id = ?").param(job).query(String.class).single())
                .isEqualTo("cancelled");
        UUID deletionId = jdbc.sql("select id from deletion where target_id = ?").param(asked.conversationId())
                .query(UUID.class).single();
        assertThat(notBefore(deletionId)).isEqualTo(claim.lapsesAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(deletion.run(b.id(), deletionId)).isFalse();
        assertThat(count("turn", "id = ?", asked.turnId())).isOne();

        jdbc.sql("update deletion set not_before = ? where id = ?").params(OffsetDateTime.now().minusSeconds(1),
                deletionId).update();
        assertThat(deletion.run(b.id(), deletionId)).isTrue();

        assertThat(count("turn", "id = ?", asked.turnId())).isZero();
        assertThat(count("conversation", "id = ?", asked.conversationId())).isZero();
        assertThat(count("campaign", "id = ?", b.campaign())).isOne();
    }

    @Test
    void aRemovalStoppedMidWayIsFinishedByTheSweepOnPostgres() throws Exception {
        Owner c = owner();
        Conversations.Asked first = as(c.id(), () -> conversations.ask(c.campaign(), "Quanto dano?", null,
                Area.QUESTIONS));
        UUID second = as(c.id(), () -> conversations.ask(c.campaign(), "E o alcance?", first.conversationId(), null))
                .turnId();
        stored(c.id(), first.turnId());
        stored(c.id(), second);

        assertThat(as(c.id(), () -> conversations.delete(first.conversationId()))).isTrue();
        // The backend stopped after the first turn's references and batches went, before the rest.
        jdbc.sql("delete from turn_reference where turn_id = ?").param(first.turnId()).update();
        jdbc.sql("delete from turn_event_batch where turn_id = ?").param(first.turnId()).update();

        assertThat(deletion.sweep()).isGreaterThanOrEqualTo(1);

        assertThat(count("conversation", "id = ?", first.conversationId())).isZero();
        assertThat(count("turn", "id in (?, ?)", first.turnId(), second)).isZero();
        assertThat(count("turn_reference", "turn_id = ?", second)).isZero();
        assertThat(count("turn_material_off", "turn_id in (?, ?)", first.turnId(), second)).isZero();
        assertThat(jdbc.sql("select done_at from deletion where target_id = ?").param(first.conversationId()).query()
                .singleRow().get("done_at")).isNotNull();
    }

    @Test
    void aCampaignDeletedMeanwhileTakesTheConversationAndBothDeletionsEndDoneOnPostgres() throws Exception {
        Owner d = owner();
        UUID campaign = as(d.id(), () -> library.createCampaign(d.system(), "Mesa de domingo")).orElseThrow().id();
        Conversations.Asked asked = as(d.id(), () -> conversations.ask(campaign, "Quanto dano?", null, Area.QUESTIONS));
        stored(d.id(), asked.turnId());

        assertThat(as(d.id(), () -> conversations.delete(asked.conversationId()))).isTrue();
        assertThat(as(d.id(), () -> deletion.previewCampaign(campaign)).orElseThrow().conversations()).isZero();
        assertThat(as(d.id(), () -> deletion.deleteCampaign(campaign))).isTrue();
        UUID ofConversation = jdbc.sql("select id from deletion where target_id = ?").param(asked.conversationId())
                .query(UUID.class).single();
        UUID ofCampaign = jdbc.sql("select id from deletion where target_id = ?").param(campaign).query(UUID.class)
                .single();

        assertThat(deletion.run(d.id(), ofCampaign)).isTrue();
        assertThat(count("conversation", "id = ?", asked.conversationId())).isZero();
        assertThat(count("campaign", "id = ?", campaign)).isZero();

        assertThat(deletion.run(d.id(), ofConversation)).isTrue();
        for (UUID id : List.of(ofConversation, ofCampaign)) {
            assertThat(jdbc.sql("select done_at from deletion where id = ?").param(id).query().singleRow()
                    .get("done_at")).isNotNull();
        }
    }

    // --- helpers

    private Owner owner() throws Exception {
        UUID id = Ids.newId();
        return as(id, () -> {
            new TransactionTemplate(transactions).executeWithoutResult(status -> library.createStarter(Language.PT_BR));
            Library.Campaign campaign = library.currentCampaign().orElseThrow();
            return new Owner(id, campaign.id(), campaign.system().id());
        });
    }

    /**
     * What an answer leaves with the turn, as the answer job and the ask store it: a batch of events, a source
     * reference, a material switched off, and the usage of a model call of its job.
     */
    private void stored(UUID owner, UUID turn) {
        Timestamp now = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        jdbc.sql("insert into turn_event_batch (id, owner_id, turn_id, first_sequence, last_sequence, payload,"
                + " created_at) values (?, ?, ?, 1, 1, ?, ?)").params(Ids.newId(), owner, turn,
                "x".getBytes(StandardCharsets.UTF_8), now).update();
        jdbc.sql("insert into turn_reference (id, owner_id, turn_id, number, version_id, material_id, page, passage_ids,"
                + " material_name, section_path, created_at) values (?, ?, ?, 1, ?, ?, 1, array[?]::uuid[], ?, ?, ?)")
                .params(Ids.newId(), owner, turn, Ids.newId(), Ids.newId(), Ids.newId(),
                        "n".getBytes(StandardCharsets.UTF_8), "s".getBytes(StandardCharsets.UTF_8), now).update();
        jdbc.sql("insert into turn_material_off (owner_id, turn_id, material_id) values (?, ?, ?)")
                .params(owner, turn, Ids.newId()).update();
        jdbc.sql("insert into model_call_usage (call_id, owner_id, job_id, provider, model, tier, input_tokens,"
                + " cache_read, cache_write, output_tokens, reasoning, created_at) select ?, ?, job_id, 'azure-openai',"
                + " 'm', 'full', 1, 0, 0, 1, 0, ? from turn where id = ?").params(Ids.newId(), owner, now, turn)
                .update();
    }

    private Instant notBefore(UUID deletionId) {
        return jdbc.sql("select not_before from deletion where id = ?").param(deletionId).query(OffsetDateTime.class)
                .single().toInstant();
    }

    private long count(String table, String where, Object... params) {
        return jdbc.sql("select count(*) from " + table + " where " + where).params(params).query(Long.class).single();
    }

    private static <T> T as(UUID owner, Callable<T> work) throws Exception {
        return OwnerContext.callAs(owner, work::call);
    }
}

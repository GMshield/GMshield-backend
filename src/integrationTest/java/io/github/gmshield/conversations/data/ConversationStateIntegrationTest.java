package io.github.gmshield.conversations.data;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.conversations.service.Area;
import io.github.gmshield.conversations.service.Conversations;
import io.github.gmshield.library.service.Language;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 3.10 part B on PostgreSQL: only the store's bulk updates write {@code conversation.state} and
 * {@code last_asked_at}. A rename whose entity was loaded before a delete (and a question) committed on another
 * connection flushes its name and version only: the row stays {@code deleting}, with the newer {@code last_asked_at}.
 *
 * <p>Story 3.11 on PostgreSQL: only the store's bulk updates write {@code turn.dismissed_at}. A stop whose turn was
 * loaded before a dismissal committed on another connection flushes its {@code stopped_at} only: the turn keeps both.
 */
@SpringBootTest(properties = {"GMSHIELD_MODE=test", "gmshield.jobs.sweep.enabled=false",
    "gmshield.materials.upload-sweep.enabled=false",
    "gmshield.materials.version-removal-sweep.enabled=false",
    "gmshield.library.deletion-sweep.enabled=false",
    "gmshield.materials.index-retry-sweep.enabled=false",
    "gmshield.files.dir=${java.io.tmpdir}/gmshield-deletion-it"})
@ActiveProfiles("test-mode")
class ConversationStateIntegrationTest {

    private static final String STOPPED_AT = "select stopped_at from turn where id = ?";
    private static final String DISMISSED_AT = "select dismissed_at from turn where id = ?";

    @Autowired
    private Library library;
    @Autowired
    private Conversations conversations;
    @Autowired
    private ConversationStore store;
    @Autowired
    private EntityManagerFactory entities;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void aRenameLoadedBeforeADeleteCommittedKeepsTheRowDeletingAndItsLastQuestionTime() {
        UUID owner = Ids.newId();
        UUID campaign = OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status -> {
            library.createStarter(Language.PT_BR);
            return library.currentCampaign().orElseThrow().id();
        }));
        UUID conversation = OwnerContext.callAs(owner, () -> conversations.ask(campaign, "Quanto dano?", null,
                Area.QUESTIONS)).conversationId();
        OffsetDateTime later = OffsetDateTime.now().plusMinutes(5).truncatedTo(ChronoUnit.MICROS);
        TransactionTemplate other = new TransactionTemplate(transactions);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status -> {
            EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(entities);
            // The rename's transaction holds the row as it was: ready, at version 0.
            ConversationEntity loaded = em.createQuery("select c from Conversation c where c.id = :id",
                    ConversationEntity.class).setParameter("id", conversation).getSingleResult();
            // Another connection commits the delete's mark and a question's time.
            other.executeWithoutResult(moved -> {
                assertThat(store.markDeleting(conversation)).isTrue();
                jdbc.sql("update conversation set last_asked_at = ? where id = ?").params(later, conversation)
                        .update();
            });
            loaded.rename(new byte[] {1, 2, 3});
            em.flush();
            return null;
        }));

        Map<String, Object> row = jdbc.sql("select state, version, last_asked_at, name from conversation where id = ?")
                .param(conversation).query().singleRow();
        assertThat(row).containsEntry("state", "deleting").containsEntry("version", 1L);
        assertThat(row.get("name")).isNotNull();
        assertThat(jdbc.sql("select last_asked_at from conversation where id = ?").param(conversation)
                .query(OffsetDateTime.class).single().toInstant()).isEqualTo(later.toInstant());
    }

    @Test
    void aStopLoadedBeforeADismissalCommittedKeepsTheDismissalAndTheStop() {
        UUID owner = Ids.newId();
        UUID campaign = OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status -> {
            library.createStarter(Language.PT_BR);
            return library.currentCampaign().orElseThrow().id();
        }));
        UUID turn = OwnerContext.callAs(owner, () -> conversations.ask(campaign, "Quanto dano?", null,
                Area.QUESTIONS)).turnId();
        Instant dismissedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant stoppedAt = dismissedAt.plusSeconds(1);
        TransactionTemplate other = new TransactionTemplate(transactions);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status -> {
            EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(entities);
            // The stop's transaction holds the turn as it was: shown, not stopped.
            TurnEntity loaded = em.createQuery("select t from Turn t where t.id = :id", TurnEntity.class)
                    .setParameter("id", turn).getSingleResult();
            assertThat(loaded.row(java.util.Set.of()).dismissedAt()).isNull();
            // Another connection commits the GM's dismissal.
            other.executeWithoutResult(moved -> assertThat(store.markDismissed(turn, dismissedAt)).isTrue());
            // The stop flushes the entity it holds, whose dismissed_at is still null in memory.
            assertThat(store.markStopped(turn, stoppedAt)).isTrue();
            return null;
        }));

        assertThat(turnTime(turn, STOPPED_AT)).isEqualTo(stoppedAt);
        assertThat(turnTime(turn, DISMISSED_AT)).isEqualTo(dismissedAt);

        // And the other way round: an undo's bulk update leaves the stop as it is.
        OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status ->
                store.clearDismissed(turn)));
        assertThat(turnTime(turn, DISMISSED_AT)).isNull();
        assertThat(turnTime(turn, STOPPED_AT)).isEqualTo(stoppedAt);
    }

    /** The turn's time read by {@code query} (one of the two below), or null. */
    private Instant turnTime(UUID turn, String query) {
        OffsetDateTime time = jdbc.sql(query).param(turn).query(OffsetDateTime.class).list().getFirst();
        return time == null ? null : time.toInstant();
    }
}

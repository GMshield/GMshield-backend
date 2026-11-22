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
 */
@SpringBootTest(properties = {"GMSHIELD_MODE=test", "gmshield.jobs.sweep.enabled=false",
    "gmshield.materials.upload-sweep.enabled=false",
    "gmshield.materials.version-removal-sweep.enabled=false",
    "gmshield.library.deletion-sweep.enabled=false",
    "gmshield.materials.index-retry-sweep.enabled=false",
    "gmshield.files.dir=${java.io.tmpdir}/gmshield-deletion-it"})
@ActiveProfiles("test-mode")
class ConversationStateIntegrationTest {

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
}

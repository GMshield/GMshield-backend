package io.github.gmshield.conversations.service;

import io.github.gmshield.conversations.data.ConversationRow;
import io.github.gmshield.conversations.data.ConversationStore;
import io.github.gmshield.conversations.data.TurnReferenceStore;
import io.github.gmshield.conversations.data.TurnRow;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.library.service.SystemDeletionHandler;
import io.github.gmshield.shared.service.OwnerContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The conversations' part of a system's deletion (Story 3.1 part B, AD-22), as the owner of the work: the
 * conversations of the system's campaigns, with their turns, event batches and source references. The usage of each
 * model call ({@code model_call_usage}) stays: it names a job, never a turn.
 *
 * <ul>
 *   <li>{@link #count}: how many conversations the campaigns have.</li>
 *   <li>{@link #prepare}: the {@code question.answer} job of every turn is locked ({@link Jobs#claimLapse}) and, unless
 *       it has ended, cancelled; it answers the latest claim lapse of those jobs. The turns are already "not found":
 *       their campaign is.</li>
 *   <li>{@link #remove}: one transaction per conversation deletes the references, event batches and turn of each of
 *       its turns, then the conversation. Safe to repeat.</li>
 * </ul>
 *
 * <p>Story 3.2 part B: a campaign's deletion ({@code Going(null, [campaign])}) is the same, for that campaign alone.
 */
@Service
class SystemConversationsDeletion implements SystemDeletionHandler {

    private static final Logger log = LoggerFactory.getLogger(SystemConversationsDeletion.class);

    private final ConversationStore store;
    private final TurnReferenceStore references;
    private final Jobs jobs;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    SystemConversationsDeletion(ConversationStore store, TurnReferenceStore references, Jobs jobs,
                                ObjectProvider<PlatformTransactionManager> transactions) {
        this.store = store;
        this.references = references;
        this.jobs = jobs;
        this.transactions = transactions;
    }

    @Override
    public String part() {
        return SystemDeletion.CONVERSATIONS;
    }

    @Override
    public long count(Going going) {
        return store.conversationsOfCampaigns(going.campaignIds()).size();
    }

    @Override
    public Optional<Instant> prepare(Going going) {
        List<UUID> jobIds = store.conversationsOfCampaigns(going.campaignIds()).stream()
                .flatMap(conversation -> store.turnsOf(conversation.id()).stream()).map(TurnRow::jobId).toList();
        Optional<Instant> lapse = jobs.claimLapse(jobIds);
        int cancelled = 0;
        for (UUID jobId : jobs.unended(jobIds)) {
            cancelled += jobs.cancel(jobId) ? 1 : 0;
        }
        going.about(log.atInfo()).addKeyValue("cancelled", cancelled).log("conversation.system_deletion_prepared");
        return lapse;
    }

    @Override
    public void remove(Going going) {
        UUID owner = OwnerContext.require();
        List<ConversationRow> conversations = reading(() -> store.conversationsOfCampaigns(going.campaignIds()));
        int turns = 0;
        for (ConversationRow conversation : conversations) {
            turns += writing(() -> {
                List<TurnRow> found = store.turnsOf(conversation.id());
                for (TurnRow turn : found) {
                    references.deleteReferences(turn.id());
                    store.deleteTurn(turn.id());
                }
                store.deleteConversation(conversation.id());
                return found.size();
            });
        }
        going.about(log.atInfo().addKeyValue("ownerId", owner)).addKeyValue("conversations", conversations.size())
                .addKeyValue("turns", turns).log("conversation.system_deletion_removed");
    }


    private <T> T reading(Supplier<T> read) {
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }

    private <T> T writing(Supplier<T> write) {
        return new TransactionTemplate(transactions.getObject()).execute(status -> write.get());
    }
}

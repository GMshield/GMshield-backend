package io.github.gmshield.conversations.service;

import io.github.gmshield.conversations.data.ConversationRow;
import io.github.gmshield.conversations.data.ConversationStore;
import io.github.gmshield.conversations.data.SearchRow;
import io.github.gmshield.conversations.data.SearchStore;
import io.github.gmshield.conversations.data.TurnReferenceStore;
import io.github.gmshield.conversations.data.TurnRow;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.library.service.SystemDeletionHandler;
import io.github.gmshield.shared.service.OwnerContext;
import java.time.Instant;
import java.util.ArrayList;
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
 *
 * <p>Story 3.4 part B: a material's deletion or a version's removal ({@link Going#ofMaterials}) takes nothing here:
 * a turn's references are stored by value, and one whose version is gone reads as a removed source.
 *
 * <p>Story 3.9: the searches of the campaigns go too: {@link #prepare} locks and cancels their {@code library.search}
 * jobs with the answer jobs, and {@link #remove} deletes each search with its results, in one transaction. A
 * material's deletion takes none: a search's results are re-checked against the scope each time it is read, so a
 * result whose material is gone is no longer shown. {@link #count} counts conversations only.
 *
 * <p>Story 3.10 part B: a conversation's deletion ({@link Going#ofOneConversation}) takes that conversation alone, the
 * same way: {@link #prepare} marks it {@code deleting} (from the commit it is "not found" everywhere) and locks and
 * cancels its answer jobs; {@link #remove} deletes it with its turns in one transaction. Nothing of its campaign, of the
 * other conversations or of the searches goes, and a conversation already gone (its campaign's or system's deletion
 * took it) is nothing to do. {@link #count} counts {@code ready} conversations only, so a deleting one leaves its
 * campaign's counts at once.
 *
 * <p>Story 5.2 part B: an artifact's deletion ({@link Going#ofOneArtifact}) takes nothing here: a turn's reference to
 * it is stored by value, and reads as a removed source once the artifact is deleting or gone.
 */
@Service
class SystemConversationsDeletion implements SystemDeletionHandler {

    private static final Logger log = LoggerFactory.getLogger(SystemConversationsDeletion.class);

    private final ConversationStore store;
    private final TurnReferenceStore references;
    private final SearchStore searches;
    private final Jobs jobs;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    SystemConversationsDeletion(ConversationStore store, TurnReferenceStore references, SearchStore searches, Jobs jobs,
                                ObjectProvider<PlatformTransactionManager> transactions) {
        this.store = store;
        this.references = references;
        this.searches = searches;
        this.jobs = jobs;
        this.transactions = transactions;
    }

    @Override
    public String part() {
        return SystemDeletion.CONVERSATIONS;
    }

    @Override
    public long count(Going going) {
        if (going.ofOneItem()) {
            return 0;
        }
        return store.countConversationsOfCampaigns(going.campaignIds());
    }

    @Override
    public Optional<Instant> prepare(Going going) {
        if (going.ofMaterials() || going.ofOneArtifact()) {
            return Optional.empty();
        }
        if (going.ofOneConversation()) {
            return prepareOne(going);
        }
        List<UUID> jobIds = new ArrayList<>(store.conversationsOfCampaigns(going.campaignIds()).stream()
                .flatMap(conversation -> store.turnsOf(conversation.id()).stream()).map(TurnRow::jobId).toList());
        jobIds.addAll(searches.searchesOfCampaigns(going.campaignIds()).stream().map(SearchRow::jobId).toList());
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
        if (going.ofMaterials() || going.ofOneArtifact()) {
            return;
        }
        UUID owner = OwnerContext.require();
        if (going.ofOneConversation()) {
            Integer turns = writing(() -> removeConversation(going.conversationId()));
            going.about(log.atInfo().addKeyValue("ownerId", owner)).addKeyValue("turns", turns)
                    .log("conversation.deletion_removed");
            return;
        }
        List<ConversationRow> conversations = reading(() -> store.conversationsOfCampaigns(going.campaignIds()));
        int turns = 0;
        for (ConversationRow conversation : conversations) {
            turns += writing(() -> removeConversation(conversation.id()));
        }
        Integer removedSearches = writing(() -> {
            List<SearchRow> found = searches.searchesOfCampaigns(going.campaignIds());
            found.forEach(search -> searches.deleteSearch(search.id()));
            return found.size();
        });
        going.about(log.atInfo().addKeyValue("ownerId", owner)).addKeyValue("conversations", conversations.size())
                .addKeyValue("turns", turns).addKeyValue("searches", removedSearches)
                .log("conversation.system_deletion_removed");
    }

    /**
     * One conversation's prepare (Story 3.10 part B): it becomes {@code deleting}, and its answer jobs are locked and,
     * unless ended, cancelled. Nothing when it is gone already.
     */
    private Optional<Instant> prepareOne(Going going) {
        UUID owner = OwnerContext.require();
        UUID conversationId = going.conversationId();
        if (!store.markDeleting(conversationId)) {
            going.about(log.atInfo().addKeyValue("ownerId", owner)).addKeyValue("found", false)
                    .addKeyValue("cancelled", 0).log("conversation.deletion_prepared");
            return Optional.empty();
        }
        List<UUID> jobIds = store.turnsOf(conversationId).stream().map(TurnRow::jobId).toList();
        Optional<Instant> lapse = jobs.claimLapse(jobIds);
        int cancelled = 0;
        for (UUID jobId : jobs.unended(jobIds)) {
            cancelled += jobs.cancel(jobId) ? 1 : 0;
        }
        going.about(log.atInfo().addKeyValue("ownerId", owner)).addKeyValue("found", true)
                .addKeyValue("cancelled", cancelled).log("conversation.deletion_prepared");
        return lapse;
    }

    /**
     * In the caller's transaction: the references, event batches, materials-off rows and turn of each of the
     * conversation's turns, then the conversation. Answers how many turns went; 0 (nothing) when it is gone already.
     */
    private int removeConversation(UUID conversationId) {
        List<TurnRow> found = store.turnsOf(conversationId);
        for (TurnRow turn : found) {
            references.deleteReferences(turn.id());
            store.deleteTurn(turn.id());
        }
        store.deleteConversation(conversationId);
        return found.size();
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

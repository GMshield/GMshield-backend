package io.github.gmshield.library.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.gmshield.library.data.CampaignRow;
import io.github.gmshield.library.data.DeletionRow;
import io.github.gmshield.library.data.InMemoryDeletionStore;
import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.library.data.SystemRow;
import io.github.gmshield.shared.data.InMemoryTransactionManager;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A delete hands its one-shot pass to the schedule at the deletion's not_before, and that pass finishes it (Story 3.1
 * part B); likewise a campaign's (Story 3.2 part B), and a campaign whose system was deleted meanwhile ends as done.
 * Story 3.4 part B: a material's deletion and a version's removal are recorded once, prepared in the same transaction
 * (waiting for the latest claim lapse), and done once the handlers' removal ends, with nothing of library's touched.
 * Story 3.10 part B: a conversation's deletion is the same, its id kept opaque, waiting for the claims its prepare
 * answers; a second delete records nothing. Story 5.2 part B: an artifact's deletion, recorded inside the caller's
 * transaction, schedules its one-shot pass only once that transaction commits, and never when it rolls back.
 */
class SystemDeletionTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private final InMemoryLibraryStore store = new InMemoryLibraryStore();
    private final InMemoryDeletionStore deletions = new InMemoryDeletionStore();
    private final InMemoryTransactionManager transactions = new InMemoryTransactionManager();
    private final SystemDeletionSweepSchedule schedule = mock(SystemDeletionSweepSchedule.class);

    private SystemDeletion deletion() {
        return deletion(null);
    }

    private SystemDeletion deletion(SystemDeletionHandler handler) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (handler != null) {
            beans.addBean("handler", handler);
        }
        beans.addBean("transactions", transactions);
        beans.addBean("schedule", schedule);
        beans.addBean("clock", Clock.fixed(NOW, ZoneOffset.UTC));
        return new SystemDeletion(store, deletions, beans.getBeanProvider(SystemDeletionHandler.class),
                beans.getBeanProvider(PlatformTransactionManager.class),
                beans.getBeanProvider(SystemDeletionSweepSchedule.class), beans.getBeanProvider(Clock.class));
    }

    private void insert(UUID owner, UUID system, UUID... campaigns) {
        OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status -> {
            store.insertSystem(new SystemRow(system, owner, new byte[] {1}, new byte[] {2}, NOW));
            for (UUID campaign : campaigns) {
                store.insertCampaign(new CampaignRow(campaign, owner, system, new byte[] {3}, NOW));
            }
            return null;
        }));
    }

    @Test
    void aCampaignsDeleteSchedulesItsPassAndThatPassRemovesTheCampaignAloneThenEndsTheDeletion() {
        SystemDeletion deletion = deletion();
        UUID owner = Ids.newId();
        UUID system = Ids.newId();
        UUID campaign = Ids.newId();
        UUID sibling = Ids.newId();
        insert(owner, system, campaign, sibling);

        assertThat(OwnerContext.callAs(owner, () -> deletion.previewCampaign(campaign)))
                .contains(new SystemDeletion.CampaignCounts(0, 0));
        assertThat(OwnerContext.callAs(Ids.newId(), () -> deletion.deleteCampaign(campaign))).isFalse();
        assertThat(OwnerContext.callAs(owner, () -> deletion.deleteCampaign(campaign))).isTrue();

        DeletionRow row = deletions.rows.values().stream().findFirst().orElseThrow();
        assertThat(row.kind()).isEqualTo(DeletionRow.CAMPAIGN);
        assertThat(row.targetId()).isEqualTo(campaign);
        assertThat(store.deletingCampaigns).containsExactly(campaign);
        assertThat(OwnerContext.callAs(owner, () -> deletion.previewCampaign(campaign))).isEmpty();
        assertThat(OwnerContext.callAs(owner, () -> deletion.deleteCampaign(campaign))).isFalse();
        ArgumentCaptor<Runnable> pass = ArgumentCaptor.forClass(Runnable.class);
        verify(schedule).once(pass.capture(), eq(NOW));

        pass.getValue().run();

        assertThat(store.campaigns).doesNotContainKey(campaign).containsKey(sibling);
        assertThat(store.systems).containsKey(system);
        assertThat(store.deletingCampaigns).isEmpty();
        assertThat(deletions.rows.get(row.id()).doneAt()).isEqualTo(NOW);
        assertThat(deletion.run(owner, row.id())).isFalse();
    }

    @Test
    void aSystemDeletedWhileItsCampaignIsDeletingTakesItAndTheCampaignsDeletionEndsAsDone() {
        SystemDeletion deletion = deletion();
        UUID owner = Ids.newId();
        UUID system = Ids.newId();
        UUID campaign = Ids.newId();
        insert(owner, system, campaign);
        assertThat(OwnerContext.callAs(owner, () -> deletion.deleteCampaign(campaign))).isTrue();
        // The system's preview no longer counts the deleting campaign; its deletion still takes it.
        assertThat(OwnerContext.callAs(owner, () -> deletion.preview(system)).orElseThrow().campaigns()).isZero();
        assertThat(OwnerContext.callAs(owner, () -> deletion.delete(system))).isTrue();
        DeletionRow ofCampaign = deletions.rows.values().stream()
                .filter(row -> row.kind().equals(DeletionRow.CAMPAIGN)).findFirst().orElseThrow();
        DeletionRow ofSystem = deletions.rows.values().stream()
                .filter(row -> row.kind().equals(DeletionRow.SYSTEM)).findFirst().orElseThrow();

        assertThat(deletion.run(owner, ofSystem.id())).isTrue();
        assertThat(store.campaigns).doesNotContainKey(campaign);
        assertThat(store.systems).doesNotContainKey(system);

        assertThat(deletion.run(owner, ofCampaign.id())).isTrue();
        assertThat(deletions.rows.get(ofCampaign.id()).doneAt()).isEqualTo(NOW);
        assertThat(deletions.rows.get(ofSystem.id()).doneAt()).isEqualTo(NOW);
    }

    @Test
    void aDeleteSchedulesItsPassAtNotBeforeAndThatPassFinishesTheDeletion() {
        SystemDeletion deletion = deletion();
        UUID owner = Ids.newId();
        UUID system = Ids.newId();
        UUID campaign = Ids.newId();
        OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status -> {
            store.insertSystem(new SystemRow(system, owner, new byte[] {1}, new byte[] {2}, NOW));
            store.insertCampaign(new CampaignRow(campaign, owner, system, new byte[] {3}, NOW));
            return null;
        }));

        assertThat(OwnerContext.callAs(owner, () -> deletion.delete(system))).isTrue();

        DeletionRow row = deletions.rows.values().stream().findFirst().orElseThrow();
        ArgumentCaptor<Runnable> pass = ArgumentCaptor.forClass(Runnable.class);
        verify(schedule).once(pass.capture(), eq(row.notBefore()));
        assertThat(row.notBefore()).isEqualTo(NOW);
        assertThat(store.systems).containsKey(system);

        pass.getValue().run();

        assertThat(store.systems).doesNotContainKey(system);
        assertThat(store.campaigns).doesNotContainKey(campaign);
        assertThat(deletions.rows.get(row.id()).doneAt()).isEqualTo(NOW);
        verify(schedule).once(any(), any());
    }

    /** A handler that records what it is given, and answers {@code lapse} from its prepare. */
    private static final class Recording implements SystemDeletionHandler {
        final java.util.List<Going> prepared = new java.util.ArrayList<>();
        final java.util.List<Going> removed = new java.util.ArrayList<>();
        Instant lapse;
        boolean failing;

        @Override
        public String part() {
            return SystemDeletion.MATERIALS;
        }

        @Override
        public long count(Going going) {
            return 0;
        }

        @Override
        public java.util.Optional<Instant> prepare(Going going) {
            prepared.add(going);
            return java.util.Optional.ofNullable(lapse);
        }

        @Override
        public void remove(Going going) {
            removed.add(going);
            if (failing) {
                throw new IllegalStateException("material.version_not_discarded");
            }
        }
    }

    @Test
    void aMaterialsDeletionIsRecordedOncePreparedAtOnceAndDoneOnceTheHandlersRemovalEnds() {
        Recording handler = new Recording();
        SystemDeletion deletion = deletion(handler);
        UUID owner = Ids.newId();
        UUID system = Ids.newId();
        insert(owner, system);
        UUID material = Ids.newId();

        assertThat(OwnerContext.callAs(owner, () -> deletion.deleteMaterial(material))).isTrue();
        assertThat(handler.prepared).containsExactly(SystemDeletionHandler.Going.ofMaterial(material));
        DeletionRow row = deletions.rows.values().stream().findFirst().orElseThrow();
        assertThat(row.kind()).isEqualTo(DeletionRow.MATERIAL);
        assertThat(row.targetId()).isEqualTo(material);
        assertThat(row.ownerId()).isEqualTo(owner);
        assertThat(row.notBefore()).isEqualTo(NOW);
        // Again: nothing is recorded or prepared a second time.
        assertThat(OwnerContext.callAs(owner, () -> deletion.deleteMaterial(material))).isFalse();
        assertThat(handler.prepared).hasSize(1);
        assertThat(deletions.rows).hasSize(1);
        ArgumentCaptor<Runnable> pass = ArgumentCaptor.forClass(Runnable.class);
        verify(schedule).once(pass.capture(), eq(NOW));

        // A removal that fails leaves the deletion for the next pass.
        handler.failing = true;
        pass.getValue().run();
        assertThat(deletions.rows.get(row.id()).doneAt()).isNull();
        handler.failing = false;

        assertThat(deletion.run(owner, row.id())).isTrue();
        assertThat(handler.prepared).hasSize(3).allMatch(SystemDeletionHandler.Going.ofMaterial(material)::equals);
        assertThat(handler.removed).hasSize(2).allMatch(SystemDeletionHandler.Going.ofMaterial(material)::equals);
        assertThat(deletions.rows.get(row.id()).doneAt()).isEqualTo(NOW);
        assertThat(store.systems).containsKey(system);
        assertThat(deletion.run(owner, row.id())).isFalse();
    }

    @Test
    void aVersionsRemovalWaitsForTheLatestClaimLapseItsPrepareAnswers() {
        Recording handler = new Recording();
        handler.lapse = NOW.plusSeconds(90);
        SystemDeletion deletion = deletion(handler);
        UUID owner = Ids.newId();
        UUID version = Ids.newId();

        assertThat(OwnerContext.callAs(owner, () -> deletion.versionRemovalRecorded(version))).isFalse();
        assertThat(OwnerContext.callAs(owner, () -> deletion.removeVersion(version))).isTrue();

        DeletionRow row = deletions.rows.values().stream().findFirst().orElseThrow();
        assertThat(row.kind()).isEqualTo(DeletionRow.VERSION);
        assertThat(row.notBefore()).isEqualTo(NOW.plusSeconds(90));
        assertThat(handler.prepared).containsExactly(SystemDeletionHandler.Going.ofVersion(version));
        assertThat(OwnerContext.callAs(owner, () -> deletion.versionRemovalRecorded(version))).isTrue();
        assertThat(OwnerContext.callAs(Ids.newId(), () -> deletion.versionRemovalRecorded(version))).isFalse();
        verify(schedule).once(any(), eq(NOW.plusSeconds(90)));
        // Not due yet: the pass does nothing.
        assertThat(deletion.run(owner, row.id())).isFalse();
        assertThat(handler.removed).isEmpty();
        assertThat(deletion.sweep()).isZero();
    }

    @Test
    void aConversationsDeletionIsRecordedOnceWaitsForItsClaimsAndIsDoneOnceTheHandlersRemovalEnds() {
        Recording handler = new Recording();
        handler.lapse = NOW.plusSeconds(45);
        SystemDeletion deletion = deletion(handler);
        UUID owner = Ids.newId();
        UUID system = Ids.newId();
        UUID campaign = Ids.newId();
        insert(owner, system, campaign);
        UUID conversation = Ids.newId();
        SystemDeletionHandler.Going going = SystemDeletionHandler.Going.ofConversation(conversation);
        assertThat(going.ofOneConversation()).isTrue();
        assertThat(going.ofOneItem()).isTrue();
        assertThat(going.ofMaterials()).isFalse();
        assertThat(going.campaignIds()).isEmpty();

        assertThat(OwnerContext.callAs(owner, () -> deletion.deleteConversation(conversation))).isTrue();
        assertThat(OwnerContext.callAs(owner, () -> deletion.deleteConversation(conversation))).isFalse();

        DeletionRow row = deletions.rows.values().stream().findFirst().orElseThrow();
        assertThat(deletions.rows).hasSize(1);
        assertThat(row.kind()).isEqualTo(DeletionRow.CONVERSATION);
        assertThat(row.targetId()).isEqualTo(conversation);
        assertThat(row.notBefore()).isEqualTo(NOW.plusSeconds(45));
        assertThat(handler.prepared).containsExactly(going);
        verify(schedule).once(any(), eq(NOW.plusSeconds(45)));
        // Not due yet: the pass does nothing.
        assertThat(deletion.run(owner, row.id())).isFalse();
        assertThat(handler.removed).isEmpty();

        // Due, and the claims have lapsed: the pass removes it and ends the record; nothing of library's goes.
        handler.lapse = null;
        deletions.rows.put(row.id(), new DeletionRow(row.id(), owner, row.kind(), conversation, NOW, NOW, null));
        assertThat(deletion.run(owner, row.id())).isTrue();
        assertThat(handler.removed).containsExactly(going);
        assertThat(deletions.rows.get(row.id()).doneAt()).isEqualTo(NOW);
        assertThat(store.campaigns).containsKey(campaign);
        assertThat(store.systems).containsKey(system);
        assertThat(deletion.run(owner, row.id())).isFalse();
    }

    @Test
    void anArtifactsDeletionRecordedInACallersTransactionSchedulesItsPassOnlyAfterTheCommit() {
        SystemDeletion deletion = deletion();
        UUID owner = Ids.newId();
        UUID artifact = Ids.newId();

        OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status -> {
            assertThat(deletion.deleteArtifact(artifact)).isTrue();
            // Not before the commit: the pass would not find the record yet.
            verify(schedule, never()).once(any(), any());
            return null;
        }));
        verify(schedule).once(any(), eq(NOW));
        assertThat(deletions.rows.values()).anyMatch(row -> row.targetId().equals(artifact)
                && row.kind().equals(DeletionRow.ARTIFACT));

        // Rolled back: no pass is ever scheduled. (Whether the record rolls back with it is PostgreSQL's: this test's
        // transaction manager starts the deletion's own transaction apart instead of joining the caller's.)
        clearInvocations(schedule);
        UUID other = Ids.newId();
        OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status -> {
            assertThat(deletion.deleteArtifact(other)).isTrue();
            status.setRollbackOnly();
            return null;
        }));
        verify(schedule, never()).once(any(), any());
    }
}

package io.github.gmshield.library.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
 */
class SystemDeletionTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private final InMemoryLibraryStore store = new InMemoryLibraryStore();
    private final InMemoryDeletionStore deletions = new InMemoryDeletionStore();
    private final InMemoryTransactionManager transactions = new InMemoryTransactionManager();
    private final SystemDeletionSweepSchedule schedule = mock(SystemDeletionSweepSchedule.class);

    private SystemDeletion deletion() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
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
}

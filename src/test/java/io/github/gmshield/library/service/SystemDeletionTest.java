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

/** A delete hands its one-shot pass to the schedule at the deletion's not_before, and that pass finishes it (Story 3.1 part B). */
class SystemDeletionTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    @Test
    void aDeleteSchedulesItsPassAtNotBeforeAndThatPassFinishesTheDeletion() {
        InMemoryLibraryStore store = new InMemoryLibraryStore();
        InMemoryDeletionStore deletions = new InMemoryDeletionStore();
        InMemoryTransactionManager transactions = new InMemoryTransactionManager();
        SystemDeletionSweepSchedule schedule = mock(SystemDeletionSweepSchedule.class);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("transactions", transactions);
        beans.addBean("schedule", schedule);
        beans.addBean("clock", Clock.fixed(NOW, ZoneOffset.UTC));
        SystemDeletion deletion = new SystemDeletion(store, deletions, beans.getBeanProvider(SystemDeletionHandler.class),
                beans.getBeanProvider(PlatformTransactionManager.class),
                beans.getBeanProvider(SystemDeletionSweepSchedule.class), beans.getBeanProvider(Clock.class));
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

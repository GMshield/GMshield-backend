package io.github.gmshield.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.knowledge.data.InMemoryKnowledgeStore;
import io.github.gmshield.knowledge.data.InMemoryMissingCoreTypes;
import io.github.gmshield.knowledge.data.RecordTypeRow;
import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.library.data.SystemRow;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.library.service.TestLibraries;
import io.github.gmshield.shared.data.InMemoryTransactionManager;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.TestVaults;
import io.github.gmshield.vault.service.Vault;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 4.1: the systems from before V34 have no record types. The fill at start gives each ready system the core types
 * it lacks, as its owner, encrypted and in the fixed order; it adds nothing on a second run, only the missing keys of a
 * system that has some, nothing to a system being deleted, and a system whose fill failed is filled at the next run.
 */
class CoreTypeBackfillTest {

    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");

    private final InMemoryLibraryStore library = new InMemoryLibraryStore();
    private final InMemoryKnowledgeStore knowledge = new InMemoryKnowledgeStore();
    private final InMemoryTransactionManager transactions = new InMemoryTransactionManager();
    private final Vault vault = TestVaults.vault();
    private final Ontology ontology;
    private final CoreTypeBackfill backfill;

    CoreTypeBackfillTest() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("transactions", transactions);
        beans.addBean("clock", Clock.fixed(NOW, ZoneOffset.UTC));
        ontology = new Ontology(knowledge, vault, TestLibraries.library(library, vault,
                beans.getBeanProvider(PlatformTransactionManager.class), beans.getBeanProvider(Clock.class)),
                beans.getBeanProvider(PlatformTransactionManager.class), beans.getBeanProvider(Clock.class));
        backfill = new CoreTypeBackfill(new InMemoryMissingCoreTypes(library, knowledge), ontology);
    }

    @Test
    void everyOldSystemGetsTheEightCoreTypesOnceAsItsOwner() {
        UUID a = Ids.newId();
        UUID b = Ids.newId();
        UUID first = system(a, "Um");
        UUID second = system(a, "Dois");
        UUID theirs = system(b, "Um");

        assertThat(backfill.run()).isEqualTo(3);

        for (UUID system : List.of(first, second, theirs)) {
            UUID owner = system.equals(theirs) ? b : a;
            List<RecordTypeRow> rows = OwnerContext.callAs(owner, () -> knowledge.recordTypes(system, 0, 50));
            assertThat(rows).extracting(RecordTypeRow::core).containsExactlyElementsOf(CoreRecordTypes.KEYS);
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.ownerId()).isEqualTo(owner);
                assertThat(row.version()).isZero();
                assertThat(new String(row.name(), StandardCharsets.ISO_8859_1)).doesNotContain(row.core().replace('_', ' '));
                assertThat(vault.decryptText(new Binding("record_type", "name", row.id(), owner), row.name()))
                        .isEqualTo(CoreRecordTypes.name(row.core()));
                assertThat(row.nameFingerprint()).isEqualTo(vault.fingerprint("record_type", "name",
                        Library.uniqueForm(CoreRecordTypes.name(row.core()))));
            });
        }
        int rows = knowledge.recordTypes.size();

        assertThat(backfill.run()).isZero();
        assertThat(knowledge.recordTypes).hasSize(rows);
    }

    @Test
    void aSystemWithSomeCoreTypesGetsOnlyTheMissingOnes() {
        UUID owner = Ids.newId();
        UUID system = system(owner, "Parcial");
        OwnerContext.runAs(owner, () -> ontology.fillCore(system));
        List<RecordTypeRow> kept = OwnerContext.callAs(owner, () -> knowledge.recordTypes(system, 0, 3));
        knowledge.recordTypes.values().removeIf(row -> row.systemId().equals(system) && !kept.contains(row));

        assertThat(backfill.run()).isEqualTo(1);

        List<RecordTypeRow> rows = OwnerContext.callAs(owner, () -> knowledge.recordTypes(system, 0, 50));
        assertThat(rows).extracting(RecordTypeRow::core).containsExactlyElementsOf(CoreRecordTypes.KEYS);
        assertThat(rows).containsAll(kept);
    }

    @Test
    void aSystemBeingDeletedIsLeftAlone() {
        UUID owner = Ids.newId();
        UUID system = system(owner, "Indo");
        library.deleting.add(system);

        assertThat(backfill.run()).isZero();
        assertThat(OwnerContext.callAs(owner, () -> ontology.fillCore(system))).isZero();
        assertThat(knowledge.recordTypes.values()).noneMatch(row -> row.systemId().equals(system));
    }

    @Test
    void aFailedFillLeavesTheSystemForTheNextRunAndTheOthersGoOn() {
        UUID owner = Ids.newId();
        List<UUID> systems = List.of(system(owner, "Falha"), system(owner, "Outro"));
        knowledge.failNextInsert.set(true);

        assertThat(backfill.run()).isEqualTo(1);
        // The first one the fill reached failed and kept nothing; the other one was filled all the same.
        assertThat(systems.stream().map(this::rowsOf)).containsExactlyInAnyOrder(0L, 8L);
        assertThat(transactions.rollbacks.get()).isEqualTo(1);

        assertThat(backfill.run()).isEqualTo(1);
        assertThat(systems.stream().map(this::rowsOf)).containsExactly(8L, 8L);
    }

    private long rowsOf(UUID system) {
        return knowledge.recordTypes.values().stream().filter(row -> row.systemId().equals(system)).count();
    }

    /** A system as V34 found it: no record types. */
    private UUID system(UUID owner, String name) {
        UUID id = Ids.newId();
        OwnerContext.runAs(owner, () -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                library.insertSystem(new SystemRow(id, owner, vault.encryptText(new Binding("system", "name", id, owner),
                        name), vault.fingerprint("system", "name", Library.uniqueForm(name)), NOW))));
        return id;
    }
}

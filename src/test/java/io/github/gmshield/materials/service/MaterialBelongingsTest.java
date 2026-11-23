package io.github.gmshield.materials.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.gmshield.jobs.data.MutableClock;
import io.github.gmshield.library.data.CampaignRow;
import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.library.data.SystemRow;
import io.github.gmshield.library.service.Language;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.library.service.TestLibraries;
import io.github.gmshield.materials.data.InMemoryMaterialStore;
import io.github.gmshield.materials.data.InMemoryUploadStorage;
import io.github.gmshield.materials.data.MaterialSystemRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.data.InMemoryTransactionManager;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.TestVaults;
import io.github.gmshield.vault.service.Vault;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 3.3 without a database: where a material belongs as the materials read it (its version, size and places, the
 * ready ones only, read once for a whole page), several systems at upload, and every row of the I/O matrix for a change
 * (systems, several systems, a campaign, stale, each bad body, another owner). The scope's side is in
 * {@code ScopesTest}, the HTTP edge in {@code UploadFlowTest}, and the same against PostgreSQL in
 * {@code MaterialIntegrationTest}.
 */
class MaterialBelongingsTest {

    @TempDir
    Path root;

    private final InMemoryMaterialStore materials = new InMemoryMaterialStore();
    private final InMemoryUploadStorage uploads = new InMemoryUploadStorage();
    /** How many reads of the library's campaigns and systems were made, one by one or in a batch. */
    private final AtomicInteger singleReads = new AtomicInteger();
    private final AtomicInteger batchReads = new AtomicInteger();
    private final InMemoryLibraryStore libraryStore = new InMemoryLibraryStore() {
        @Override
        public Optional<SystemRow> findSystem(UUID id) {
            singleReads.incrementAndGet();
            return super.findSystem(id);
        }

        @Override
        public Optional<CampaignRow> findCampaign(UUID id) {
            singleReads.incrementAndGet();
            return super.findCampaign(id);
        }

        @Override
        public List<SystemRow> findSystems(Collection<UUID> ids) {
            batchReads.incrementAndGet();
            return ids.stream().distinct().map(super::findSystem).flatMap(Optional::stream).toList();
        }

        @Override
        public List<CampaignRow> findCampaigns(Collection<UUID> ids) {
            batchReads.incrementAndGet();
            return ids.stream().distinct().map(super::findCampaign).flatMap(Optional::stream).toList();
        }
    };
    private final InMemoryTransactionManager transactions = new InMemoryTransactionManager();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-21T12:00:00Z"));
    private final Vault vault = TestVaults.vault();
    private final UUID owner = Ids.newId();
    private final UUID other = Ids.newId();
    private Library library;
    private Uploads service;
    private Materials reads;
    private MaterialBelongings belongings;
    private Library.Campaign starter;

    @BeforeEach
    void wire() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("transactions", transactions);
        beans.addBean("clock", clock);
        library = TestLibraries.library(libraryStore, vault, beans.getBeanProvider(PlatformTransactionManager.class),
                beans.getBeanProvider(Clock.class));
        service = new Uploads(materials, uploads, vault, TestVaults.files(vault, root), library,
                beans.getBeanProvider(PlatformTransactionManager.class), beans.getBeanProvider(Clock.class),
                (version, upload) -> { }, TestMaterials.NO_RECORDS, TestMaterials.NO_PROPOSALS);
        reads = new Materials(materials, uploads, vault, TestVaults.files(vault, root), ids -> java.util.Set.of(),
                library, new IngestionSettings(4), TestMaterials.NO_RECORDS,
                beans.getBeanProvider(PlatformTransactionManager.class));
        belongings = new MaterialBelongings(materials, service, reads,
                beans.getBeanProvider(PlatformTransactionManager.class), beans.getBeanProvider(Clock.class));
        for (UUID gm : List.of(owner, other)) {
            as(gm, () -> new TransactionTemplate(transactions).execute(status -> {
                library.createStarter(Language.EN);
                return null;
            }));
        }
        starter = as(owner, () -> library.currentCampaign().orElseThrow());
    }

    // --- what a material shows

    @Test
    void aMaterialShowsItsVersionItsSizeAndItsCampaignWithItsSystemByName() {
        UUID material = create(owner, "notas.md", campaign(starter.id()));
        setSize(material, 1234L);

        Materials.Material found = as(owner, () -> reads.find(material)).orElseThrow();

        assertThat(found.version()).isZero();
        assertThat(found.size()).isEqualTo(1234L);
        assertThat(found.belonging().campaign()).isEqualTo(new Materials.CampaignPlace(starter.id(), starter.name(),
                new Materials.Place(starter.system().id(), starter.system().name())));
        assertThat(found.belonging().systems()).isEmpty();
        assertThat(found.toString()).doesNotContain(starter.name());
    }

    @Test
    void aMaterialWithNoPlaceShowsNeitherAndItsSizeIsNullUntilKnown() {
        UUID material = create(owner, "solto.pdf", Uploads.Details.NONE);

        Materials.Material found = as(owner, () -> reads.find(material)).orElseThrow();

        assertThat(found.belonging()).isEqualTo(Materials.Belonging.NOWHERE);
        assertThat(found.size()).isNull();
    }

    @Test
    void anUploadToSeveralSystemsIsOneRowPerSystemEachOnceAndShowsThemByName() {
        UUID second = system(owner, "Pathfinder");
        UUID first = starter.system().id();

        UUID material = create(owner, "srd.pdf", systems(second, first, second));

        assertThat(as(owner, () -> materials.systemsOf(material))).extracting(MaterialSystemRow::systemId)
                .containsExactlyInAnyOrder(second, first);
        assertThat(as(owner, () -> reads.find(material)).orElseThrow().belonging().systems())
                .extracting(Materials.Place::name).containsExactlyInAnyOrder("Pathfinder", starter.system().name());
    }

    @Test
    void anUploadWithACampaignAndSystemsTooManySystemsOrAnEmptyOrForeignSystemIsInvalidMetadataAndStoresNothing() {
        UUID theirs = as(other, () -> library.currentCampaign().orElseThrow().system().id());
        UUID mine = starter.system().id();
        List<String> tooMany = IntStream.rangeClosed(0, Uploads.MAX_SYSTEMS).mapToObj(i -> mine.toString()).toList();
        List<Uploads.Details> refused = List.of(
                new Uploads.Details(null, null, starter.id().toString(), List.of(mine.toString())),
                new Uploads.Details(null, null, null, tooMany),
                new Uploads.Details(null, null, null, List.of(mine.toString(), "")),
                new Uploads.Details(null, null, null, List.of(mine.toString(), theirs.toString())));

        for (Uploads.Details details : refused) {
            assertThat(catchThrowable(() -> as(owner, () -> service.create(10, "a.pdf", details))))
                    .satisfies(problem("upload.metadata_invalid", 400));
        }
        assertThat(materials.materials).isEmpty();
        assertThat(materials.systems).isEmpty();
        // An empty list is no system, as an older frontend sends none.
        assertThat(create(owner, "b.pdf", new Uploads.Details(null, null, null, List.of()))).isNotNull();
    }

    @Test
    void aPageReadsThePlacesOfAllItsMaterialsInOneBatchAndNeverOneByOne() {
        UUID second = system(owner, "Pathfinder");
        for (int i = 0; i < 4; i++) {
            create(owner, "c" + i + ".md", campaign(starter.id()));
            create(owner, "s" + i + ".pdf", systems(second, starter.system().id()));
        }
        singleReads.set(0);
        batchReads.set(0);

        Materials.Page page = as(owner, () -> reads.list(null, null, 20));

        assertThat(page.items()).hasSize(8).allSatisfy(item -> assertThat(item.belonging())
                .isNotEqualTo(Materials.Belonging.NOWHERE));
        assertThat(singleReads).hasValue(0);
        // One read of the campaigns and one of the systems (theirs included), for the whole page.
        assertThat(batchReads).hasValue(2);
    }

    @Test
    void withoutAStateTheListHasEveryMaterialAndOneWhoseEveryPlaceIsDeletingIsLeftOutOfLists() {
        UUID going = system(owner, "Some");
        UUID kept = create(owner, "kept.pdf", systems(going, starter.system().id()));
        UUID gone = create(owner, "gone.pdf", systems(going));
        UUID loose = create(owner, "loose.pdf", Uploads.Details.NONE);
        UUID discarded = create(owner, "discarded.pdf", campaign(starter.id()));
        setState(discarded, "discarded");
        libraryStore.deleting.add(going);

        Materials.Page page = as(owner, () -> reads.list(null, null, 20));

        assertThat(page.items()).extracting(Materials.Material::id).containsExactly(discarded, loose, kept);
        assertThat(page.items().get(2).belonging().systems()).extracting(Materials.Place::id)
                .containsExactly(starter.system().id());
        assertThat(as(owner, () -> reads.find(gone)).orElseThrow().belonging()).isEqualTo(Materials.Belonging.NOWHERE);
        // A change of it is not found: its place is being deleted.
        assertThat(as(owner, () -> belongings.change(gone, 0, null, List.of(starter.system().id().toString()))))
                .isEmpty();
    }

    // --- a change (the I/O matrix)

    @Test
    void aCampaignsMaterialMovedToASystemBelongsToItAloneItsVersionRisesAndNothingButItsPlaceIsWritten() {
        UUID material = create(owner, "notas.md", campaign(starter.id()));
        List<VersionRow> versions = List.copyOf(materials.versions.values());

        Materials.Material moved = as(owner, () -> belongings.change(material, 0, null,
                List.of(starter.system().id().toString()))).orElseThrow();

        assertThat(moved.version()).isEqualTo(1);
        assertThat(moved.belonging().campaign()).isNull();
        assertThat(moved.belonging().systems()).extracting(Materials.Place::id).containsExactly(starter.system().id());
        assertThat(materials.materials.get(material).campaignId()).isNull();
        assertThat(materials.versions.values()).containsExactlyInAnyOrderElementsOf(versions);
        assertThat(materials.passages).isEmpty();
    }

    @Test
    void severalSystemsReplaceTheOldOnesKeepingThoseThatStayAndACampaignThenReplacesThemAll() {
        UUID a = system(owner, "A");
        UUID b = system(owner, "B");
        UUID c = system(owner, "C");
        UUID material = create(owner, "srd.pdf", systems(a, b));
        MaterialSystemRow keptRow = as(owner, () -> materials.systemsOf(material)).stream()
                .filter(row -> row.systemId().equals(b)).findFirst().orElseThrow();

        Materials.Material changed = as(owner, () -> belongings.change(material, 0, null,
                List.of(b.toString(), c.toString(), c.toString()))).orElseThrow();

        assertThat(changed.belonging().systems()).extracting(Materials.Place::id).containsExactlyInAnyOrder(b, c);
        assertThat(as(owner, () -> materials.systemsOf(material))).contains(keptRow).hasSize(2);

        Materials.Material toCampaign = as(owner, () -> belongings.change(material, 1, starter.id().toString(), null))
                .orElseThrow();

        assertThat(toCampaign.version()).isEqualTo(2);
        assertThat(toCampaign.belonging().campaign().id()).isEqualTo(starter.id());
        assertThat(toCampaign.belonging().systems()).isEmpty();
        assertThat(materials.systems).isEmpty();
    }

    @Test
    void aMaterialWithNoPlaceCanBeGivenOneAndAnArchivedCampaignIsAValidTarget() {
        UUID material = create(owner, "solto.pdf", Uploads.Details.NONE);
        Library.Campaign archived = as(owner, () -> library.createCampaign(starter.system().id(), "Antiga"))
                .orElseThrow();
        as(owner, () -> library.changeCampaign(archived.id(), archived.version(), null, true));

        Materials.Material placed = as(owner, () -> belongings.change(material, 0, archived.id().toString(), null))
                .orElseThrow();

        assertThat(placed.belonging().campaign().id()).isEqualTo(archived.id());
    }

    @Test
    void aStaleVersionIsAConflictAndNothingChanges() {
        UUID material = create(owner, "notas.md", campaign(starter.id()));
        as(owner, () -> belongings.change(material, 0, null, List.of(starter.system().id().toString())));
        int writes = materials.belongingChanges.get();

        assertThat(catchThrowable(() -> as(owner, () -> belongings.change(material, 0, starter.id().toString(), null))))
                .satisfies(problem("request.stale_version", 409));
        assertThat(materials.belongingChanges).hasValue(writes);
        assertThat(materials.materials.get(material).campaignId()).isNull();
        assertThat(materials.materials.get(material).version()).isEqualTo(1);
    }

    @Test
    void eachBadBodyIsInvalidBelongingWhichNeverSaysWhyAndNothingChanges() {
        UUID material = create(owner, "notas.md", campaign(starter.id()));
        UUID mine = starter.system().id();
        UUID deleting = system(owner, "Indo");
        libraryStore.deleting.add(deleting);
        Library.Campaign theirs = as(other, () -> library.currentCampaign().orElseThrow());
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= Uploads.MAX_SYSTEMS; i++) {
            tooMany.add(system(owner, "S" + i).toString());
        }
        List<Object[]> refused = List.of(
                new Object[] {starter.id().toString(), List.of(mine.toString())},
                new Object[] {null, null},
                new Object[] {null, List.of()},
                new Object[] {null, tooMany},
                new Object[] {null, List.of(UUID.randomUUID().toString())},
                new Object[] {null, List.of(mine.toString(), theirs.system().id().toString())},
                new Object[] {theirs.id().toString(), null},
                new Object[] {UUID.randomUUID().toString(), null},
                new Object[] {"not-a-uuid", null},
                new Object[] {null, List.of(deleting.toString())});
        List<ApiException> problems = new ArrayList<>();

        for (Object[] body : refused) {
            @SuppressWarnings("unchecked")
            List<String> systems = (List<String>) body[1];
            Throwable thrown = catchThrowable(() -> as(owner, () -> belongings.change(material, 0, (String) body[0],
                    systems)));
            assertThat(thrown).satisfies(problem("material.belonging_invalid", 400));
            problems.add((ApiException) thrown);
        }
        assertThat(problems.stream().map(ApiException::problem).distinct()).hasSize(1);
        assertThat(materials.belongingChanges).hasValue(0);
        assertThat(materials.materials.get(material).campaignId()).isEqualTo(starter.id());
        assertThat(materials.systems).isEmpty();
        // A campaign being deleted is not a target either.
        libraryStore.deletingCampaigns.add(starter.id());
        UUID loose = create(owner, "solto.pdf", Uploads.Details.NONE);
        assertThat(catchThrowable(() -> as(owner, () -> belongings.change(loose, 0, starter.id().toString(), null))))
                .satisfies(problem("material.belonging_invalid", 400));
    }

    @Test
    void anotherOwnersMaterialOrAnUnknownOneIsNotFoundAndNothingChanges() {
        UUID material = create(owner, "notas.md", campaign(starter.id()));
        UUID theirSystem = as(other, () -> library.currentCampaign().orElseThrow().system().id());

        assertThat(as(other, () -> belongings.change(material, 0, null, List.of(theirSystem.toString())))).isEmpty();
        assertThat(as(owner, () -> belongings.change(UUID.randomUUID(), 0, null,
                List.of(starter.system().id().toString())))).isEmpty();
        assertThat(materials.belongingChanges).hasValue(0);
        assertThat(materials.materials.get(material).campaignId()).isEqualTo(starter.id());
    }

    // --- helpers

    private static Uploads.Details campaign(UUID campaign) {
        return new Uploads.Details(null, null, campaign.toString(), (String) null);
    }

    private static Uploads.Details systems(UUID... systems) {
        return new Uploads.Details(null, null, null, java.util.Arrays.stream(systems).map(UUID::toString).toList());
    }

    private UUID system(UUID gm, String name) {
        return as(gm, () -> library.createSystem(new Library.SystemFields(name, null, null))).id();
    }

    /** A material created through the upload, its version uploading; each a millisecond after the one before. */
    private UUID create(UUID gm, String name, Uploads.Details details) {
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        UUID upload = as(gm, () -> service.create(10, name, details)).uploadId();
        return materials.versions.get(uploads.row(upload).versionId()).materialId();
    }

    private void setSize(UUID material, Long size) {
        VersionRow version = versionOf(material);
        write(() -> materials.changeVersion(version.id(), "waiting", "pdf", size, clock.instant()));
    }

    private void setState(UUID material, String state) {
        VersionRow version = versionOf(material);
        write(() -> materials.changeVersionState(version.id(), state, clock.instant()));
    }

    private VersionRow versionOf(UUID material) {
        return materials.versions.values().stream().filter(row -> row.materialId().equals(material)).findFirst()
                .orElseThrow();
    }

    private void write(Callable<Boolean> work) {
        as(owner, () -> new TransactionTemplate(transactions).execute(status -> {
            try {
                return work.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }));
    }

    private static <T> T as(UUID gm, Callable<T> work) {
        try {
            return OwnerContext.callAs(gm, work::call);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Consumer<Throwable> problem(String code, int status) {
        return thrown -> {
            assertThat(thrown).isInstanceOf(ApiException.class);
            assertThat(((ApiException) thrown).problem().code()).isEqualTo(code);
            assertThat(((ApiException) thrown).problem().status()).isEqualTo(status);
        };
    }
}

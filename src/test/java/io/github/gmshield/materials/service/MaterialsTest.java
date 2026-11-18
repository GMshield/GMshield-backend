package io.github.gmshield.materials.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.gmshield.jobs.data.MutableClock;
import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.library.service.Language;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.library.service.TestLibraries;
import io.github.gmshield.materials.data.InMemoryMaterialStore;
import io.github.gmshield.materials.data.InMemoryUploadStorage;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialSystemRow;
import io.github.gmshield.materials.data.PageRangeRow;
import io.github.gmshield.materials.data.StepRow;
import io.github.gmshield.materials.data.UploadRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.data.InMemoryTransactionManager;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.FileStore;
import io.github.gmshield.vault.service.TestVaults;
import io.github.gmshield.vault.service.Vault;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 1.14 without a database: what a GM says about a file at creation (every metadata row of the I/O matrix), and
 * the materials as the tray and the material page read them (states, order, cursor, limit, the upload's progress only
 * while uploading). The same against PostgreSQL is in the integration tests, and the HTTP edge in
 * {@code UploadFlowTest}.
 */
class MaterialsTest {

    @TempDir
    Path root;

    private final InMemoryMaterialStore materials = new InMemoryMaterialStore();
    private final InMemoryUploadStorage uploads = new InMemoryUploadStorage();
    private final InMemoryLibraryStore libraryStore = new InMemoryLibraryStore();
    private final InMemoryTransactionManager transactions = new InMemoryTransactionManager();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T12:00:00Z"));
    private final Vault vault = TestVaults.vault();
    private final UUID owner = Ids.newId();
    private final UUID other = Ids.newId();
    private Library library;
    private Uploads service;
    private Materials reads;
    private final List<VersionRow> started = new java.util.ArrayList<>();
    /** The jobs offered or running, as the jobs module would answer (Story 1.17). */
    private final java.util.Set<UUID> running = new java.util.HashSet<>();

    private FileStore files;

    @BeforeEach
    void wire() {
        files = TestVaults.files(vault, root);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("transactions", transactions);
        beans.addBean("clock", clock);
        library = TestLibraries.library(libraryStore, vault, beans.getBeanProvider(PlatformTransactionManager.class),
                beans.getBeanProvider(Clock.class));
        service = new Uploads(materials, uploads, vault, files, library,
                beans.getBeanProvider(PlatformTransactionManager.class), beans.getBeanProvider(Clock.class),
                (version, upload) -> started.add(version));
        reads = new Materials(materials, uploads, vault, files, ids -> ids.stream().filter(running::contains)
                .collect(java.util.stream.Collectors.toSet()), new IngestionSettings(4),
                beans.getBeanProvider(PlatformTransactionManager.class));
        for (UUID gm : List.of(owner, other)) {
            as(gm, () -> new TransactionTemplate(transactions).execute(status -> {
                library.createStarter(Language.EN);
                return null;
            }));
        }
    }

    // --- details at creation ---

    @Test
    void aKindACampaignOfTheOwnerAndDetectStoreTheKindTheCampaignAndNoLanguage() {
        UUID campaign = as(owner, () -> library.currentCampaign().orElseThrow().id());

        UUID material = create(owner, "Livro do Jogador.pdf", new Uploads.Details("rulebook", "detect", campaign.toString(), null));

        MaterialRow row = materials.materials.get(material);
        assertThat(row.kind()).isEqualTo("rulebook");
        assertThat(row.campaignId()).isEqualTo(campaign);
        assertThat(row.language()).isNull();
        assertThat(materials.systems).isEmpty();
        assertThat(row.toString()).doesNotContain("rulebook").doesNotContain(campaign.toString());
    }

    @Test
    void aSystemOfTheOwnerIsOneMaterialSystemRowAndANamedLanguageIsKept() {
        UUID system = as(owner, () -> library.currentCampaign().orElseThrow().system().id());

        UUID material = create(owner, "Bestiário.pdf", new Uploads.Details("supplement", "pt-BR", null, system.toString()));

        MaterialRow row = materials.materials.get(material);
        assertThat(row.campaignId()).isNull();
        assertThat(row.language()).isEqualTo("pt-BR");
        List<MaterialSystemRow> links = as(owner, () -> materials.systemsOf(material));
        assertThat(links).singleElement().satisfies(link -> {
            assertThat(link.ownerId()).isEqualTo(owner);
            assertThat(link.systemId()).isEqualTo(system);
        });
        assertThat(create(owner, "notes.md", new Uploads.Details("campaign_notes", "en", null, null))).isNotNull();
        for (String kind : Uploads.KINDS) {
            assertThat(create(owner, kind + ".txt", new Uploads.Details(kind, null, null, null))).isNotNull();
        }
    }

    @Test
    void anotherOwnersCampaignOrSystemBothKeysOrABadValueIsInvalidMetadataAndNothingIsStored() {
        UUID mine = as(owner, () -> library.currentCampaign().orElseThrow().id());
        UUID mySystem = as(owner, () -> library.currentCampaign().orElseThrow().system().id());
        UUID theirs = as(other, () -> library.currentCampaign().orElseThrow().id());
        UUID theirSystem = as(other, () -> library.currentCampaign().orElseThrow().system().id());
        List<Uploads.Details> refused = List.of(
                new Uploads.Details(null, null, theirs.toString(), null),
                new Uploads.Details(null, null, null, theirSystem.toString()),
                new Uploads.Details(null, null, UUID.randomUUID().toString(), null),
                new Uploads.Details(null, null, mine.toString(), mySystem.toString()),
                new Uploads.Details(null, null, "not-a-uuid", null),
                new Uploads.Details(null, null, "", null),
                new Uploads.Details(null, null, null, mySystem.toString().toUpperCase() + "0"),
                new Uploads.Details("novel", null, null, null),
                new Uploads.Details("", null, null, null),
                new Uploads.Details("Rulebook", null, null, null),
                new Uploads.Details(null, "pt", null, null),
                new Uploads.Details(null, "DETECT", null, null),
                new Uploads.Details(null, "", null, null));
        List<ApiException> problems = new ArrayList<>();
        for (Uploads.Details details : refused) {
            Throwable thrown = catchThrowable(() -> as(owner, () -> service.create(10, "a.pdf", details)));
            assertThat(thrown)
                    .as(details.kind() + " " + details.language() + " " + details.campaign() + " " + details.system())
                    .satisfies(problem("upload.metadata_invalid", 400));
            problems.add((ApiException) thrown);
        }
        // The answer is the same whichever detail was wrong.
        assertThat(problems.stream().map(ApiException::problem).distinct()).hasSize(1);
        assertThat(materials.materials).isEmpty();
        assertThat(materials.versions).isEmpty();
        assertThat(materials.systems).isEmpty();
        assertThat(uploads.rows).isEmpty();
    }

    @Test
    void withoutDetailsAMaterialIsAsStoryOneThirteenMadeIt() {
        UUID material = create(owner, "rules.pdf", Uploads.Details.NONE);
        MaterialRow row = materials.materials.get(material);
        assertThat(row.kind()).isNull();
        assertThat(row.language()).isNull();
        assertThat(row.campaignId()).isNull();
        UUID viaOldCall = as(owner, () -> service.create(10, "old.pdf")).uploadId();
        VersionRow version = materials.versions.get(uploads.row(viaOldCall).versionId());
        assertThat(materials.materials.get(version.materialId()).kind()).isNull();
        assertThat(as(owner, () -> service.create(10, "null.pdf", null))).isNotNull();
    }

    @Test
    void aRefusedFormatAlsoRemovesTheSystemTheMaterialBelongedTo() {
        UUID system = as(owner, () -> library.currentCampaign().orElseThrow().system().id());
        byte[] zip = new byte[5000];
        zip[0] = 'P';
        zip[1] = 'K';
        zip[2] = 3;
        zip[3] = 4;
        UUID upload = as(owner, () -> service.create(zip.length, "a.pdf", new Uploads.Details(null, null, null,
                system.toString()))).uploadId();
        assertThat(materials.systems).hasSize(1);

        assertThatThrownBy(() -> as(owner, () -> service.patch(upload, 0, zip.length, UploadsTest.sha256(zip),
                new ByteArrayInputStream(zip)))).satisfies(problem("upload.format_refused", 415));

        assertThat(materials.systems).isEmpty();
        assertThat(materials.materials).isEmpty();
    }

    // --- the list and the read ---

    @Test
    void theListHasTheMaterialsWhoseNewestVersionIsInTheStatesNewestFirstWithTheUploadOnlyWhileUploading() {
        UUID uploading = create(owner, "uploading.pdf", new Uploads.Details("rulebook", null, null, null));
        UUID waiting = create(owner, "waiting.pdf", Uploads.Details.NONE);
        UUID processing = create(owner, "processing.pdf", Uploads.Details.NONE);
        UUID ready = create(owner, "ready.pdf", Uploads.Details.NONE);
        setState(waiting, "waiting");
        setState(processing, "processing");
        setState(ready, "ready");
        // A newer version decides: this material was ready, and its new version is processing.
        UUID renewed = create(owner, "renewed.pdf", Uploads.Details.NONE);
        setState(renewed, "ready");
        clock.advance(Duration.ofMinutes(1));
        addVersion(renewed, "processing");
        create(other, "theirs.pdf", Uploads.Details.NONE);

        Materials.Page page = as(owner, () -> reads.list(List.of("uploading", "waiting", "processing"), null, 20));

        assertThat(page.items()).extracting(Materials.Material::id).containsExactly(renewed, processing, waiting, uploading);
        assertThat(page.items()).extracting(Materials.Material::name)
                .containsExactly("renewed.pdf", "processing.pdf", "waiting.pdf", "uploading.pdf");
        assertThat(page.nextCursor()).isNull();
        Materials.Material first = page.items().getLast();
        assertThat(first.state()).isEqualTo("uploading");
        assertThat(first.kind()).isEqualTo("rulebook");
        UploadRow row = uploads.rows.values().stream()
                .filter(upload -> materials.versions.get(upload.versionId()).materialId().equals(uploading))
                .findFirst().orElseThrow();
        assertThat(first.upload()).isEqualTo(new Materials.Upload(row.id(), 0, 10, row.expiresAt()));
        assertThat(page.items().subList(0, 3)).allSatisfy(item -> assertThat(item.upload()).isNull());
        assertThat(first.toString()).doesNotContain("uploading.pdf");

        assertThat(as(owner, () -> reads.list(List.of("ready"), null, 20)).items()).extracting(Materials.Material::id)
                .containsExactly(ready);
    }

    @Test
    void pagesShowOnceThePageTotalIsKnownAndAFailureOnlyWhileTheVersionIsFailed() {
        UUID processing = create(owner, "processing.pdf", Uploads.Details.NONE);
        UUID failed = create(owner, "failed.pdf", Uploads.Details.NONE);
        UUID unknown = create(owner, "unknown.pdf", Uploads.Details.NONE);
        setState(processing, "processing");
        setState(unknown, "processing");
        write(() -> materials.recordIngestion(versionOf(processing).id(), "1", 320, clock.instant()));
        write(() -> materials.failVersion(versionOf(failed).id(), "ingestion.password_required", clock.instant()));

        Materials.Material reading = as(owner, () -> reads.find(processing)).orElseThrow();
        assertThat(reading.pages()).isEqualTo(new Materials.Pages(0, 320));
        assertThat(reading.failure()).isNull();
        Materials.Material refused = as(owner, () -> reads.find(failed)).orElseThrow();
        assertThat(refused.state()).isEqualTo("failed");
        assertThat(refused.failure()).isEqualTo("ingestion.password_required");
        assertThat(refused.pages()).isNull();
        assertThat(as(owner, () -> reads.find(unknown)).orElseThrow().pages()).isNull();
        // A failure code kept on a version that is no longer failed is never shown.
        setState(failed, "processing");
        assertThat(as(owner, () -> reads.find(failed)).orElseThrow().failure()).isNull();
        assertThat(as(owner, () -> reads.list(List.of("processing"), null, 20)).items())
                .filteredOn(item -> item.id().equals(processing)).singleElement()
                .satisfies(item -> assertThat(item.pages().total()).isEqualTo(320));
    }

    // --- page counts (Story 1.17)

    @Test
    void theCountsOfAMixedPdfComeFromItsRecordedLayersAndOutcomes() {
        UUID mixed = create(owner, "mixed.pdf", Uploads.Details.NONE);
        setState(mixed, "processing");
        UUID version = versionOf(mixed).id();
        write(() -> materials.recordIngestion(version, "3", 25, clock.instant()));
        layer(version, 1, 5, PageRangeRow.TEXT);
        layer(version, 6, 7, PageRangeRow.IMAGE);
        layer(version, 8, 20, PageRangeRow.TEXT);
        layer(version, 21, 25, PageRangeRow.IMAGE);
        outcome(version, 1, 5, PageRangeRow.READ_DIRECTLY);
        outcome(version, 6, 6, PageRangeRow.READ_BY_OCR);
        outcome(version, 7, 7, PageRangeRow.UNREADABLE);

        Materials.Pages pages = as(owner, () -> reads.find(mixed)).orElseThrow().pages();

        assertThat(pages).isEqualTo(new Materials.Pages(7, 25, new Materials.Count(5, 18), new Materials.Count(2, 7),
                5, 1, 1));
        // Another owner's ranges never count.
        assertThat(as(other, () -> materials.pageLayers(List.of(version)))).isEmpty();
    }

    @Test
    void anOutcomeAcrossLayersCountsForEachAndTheTrayProgressRowReadsTextFirst() {
        List<PageRangeRow> layers = List.of(range(1, 364, PageRangeRow.TEXT), range(365, 444, PageRangeRow.IMAGE));
        assertThat(Materials.pages(444, layers, List.of(range(1, 40, PageRangeRow.READ_DIRECTLY))))
                .isEqualTo(new Materials.Pages(40, 444, new Materials.Count(40, 364), new Materials.Count(0, 80),
                        40, 0, 0));
        List<PageRangeRow> split = List.of(range(1, 5, PageRangeRow.TEXT), range(6, 10, PageRangeRow.IMAGE),
                range(11, 12, PageRangeRow.TEXT));
        List<PageRangeRow> outcomes = List.of(range(3, 7, PageRangeRow.UNREADABLE), range(9, 11,
                PageRangeRow.READ_BY_OCR));
        assertThat(Materials.pages(12, split, outcomes)).isEqualTo(new Materials.Pages(8, 12,
                new Materials.Count(4, 7), new Materials.Count(4, 5), 0, 3, 5));
        assertThat(Materials.pages(5, List.of(), List.of(range(1, 5, PageRangeRow.READ_DIRECTLY))))
                .isEqualTo(new Materials.Pages(5, 5, Materials.Count.NONE, Materials.Count.NONE, 5, 0, 0));
    }

    @Test
    void aListReadsEachKindOfRangeOnceForAllItsVersions() {
        for (int i = 0; i < 3; i++) {
            UUID material = create(owner, "scan" + i + ".pdf", Uploads.Details.NONE);
            setState(material, "processing");
            UUID version = versionOf(material).id();
            write(() -> materials.recordIngestion(version, "3", 10, clock.instant()));
            layer(version, 1, 10, PageRangeRow.IMAGE);
            outcome(version, 1, i + 1, PageRangeRow.READ_BY_OCR);
        }
        materials.rangeReads.set(0);

        List<Materials.Material> items = as(owner, () -> reads.list(List.of("processing"), null, 20)).items();

        // Layers, outcomes and unended steps: one read each for the whole list.
        assertThat(materials.rangeReads.get()).isEqualTo(3);
        assertThat(items).extracting(item -> item.pages().image().done()).containsExactlyInAnyOrder(1, 2, 3);
    }

    @Test
    void readingIsTheLayerOfThePagesOfTheStepInProgressEvenWhileTextPagesOfALaterBatchWait() {
        // Matrix row "OCR between text batches": batch 1's OCR 9-18 runs while batch 2's text pages 21-30 wait.
        UUID mixed = create(owner, "misto.pdf", Uploads.Details.NONE);
        setState(mixed, "processing");
        UUID version = versionOf(mixed).id();
        write(() -> materials.recordIngestion(version, "3", 30, clock.instant()));
        layer(version, 1, 8, PageRangeRow.TEXT);
        layer(version, 9, 20, PageRangeRow.IMAGE);
        layer(version, 21, 30, PageRangeRow.TEXT);
        outcome(version, 1, 8, PageRangeRow.READ_DIRECTLY);
        UUID ocr = step(version, 9, 18);
        UUID waitingOcr = step(version, 19, 20);
        step(version, 21, 30);
        running.add(ocr);

        Materials.Pages pages = as(owner, () -> reads.find(mixed)).orElseThrow().pages();

        assertThat(pages.reading()).isEqualTo("image");
        assertThat(pages.text()).isEqualTo(new Materials.Count(8, 18));
        assertThat(pages.image()).isEqualTo(new Materials.Count(0, 12));
        // No step in progress: null.
        running.clear();
        assertThat(as(owner, () -> reads.find(mixed)).orElseThrow().pages().reading()).isNull();
        running.add(waitingOcr);
        assertThat(as(owner, () -> reads.find(mixed)).orElseThrow().pages().reading()).isEqualTo("image");
    }

    @Test
    void readingIsNullWithoutPagesWithoutALayerYetOrAcrossLayers() {
        List<PageRangeRow> layers = List.of(range(1, 5, PageRangeRow.TEXT), range(6, 10, PageRangeRow.IMAGE));
        assertThat(Materials.reading(layers, List.of(stepRow(1, 5)))).isEqualTo("text");
        assertThat(Materials.reading(layers, List.of(stepRow(6, 7)))).isEqualTo("image");
        assertThat(Materials.reading(layers, List.of())).isNull();
        assertThat(Materials.reading(layers, List.of(stepRow(null, null)))).isNull();     // clean
        assertThat(Materials.reading(layers, List.of(stepRow(11, 20)))).isNull();         // triage, no layer yet
        assertThat(Materials.reading(layers, List.of(stepRow(9, 12)))).isNull();          // partly sorted
        assertThat(Materials.reading(layers, List.of(stepRow(4, 7)))).isNull();           // two layers
        assertThat(Materials.reading(layers, List.of(stepRow(1, 2), stepRow(6, 6)))).isNull();
        assertThat(Materials.reading(layers, List.of(stepRow(1, 2), stepRow(4, 5)))).isEqualTo("text");
    }

    @Test
    void neitherAHeldStepNorTheStepOverEveryTextFileCountsTowardReading() {
        // Matrix row "Held step": the passages step waits with no job, then runs over no page of its own.
        UUID material = create(owner, "livro.pdf", Uploads.Details.NONE);
        setState(material, "processing");
        UUID version = versionOf(material).id();
        write(() -> materials.recordIngestion(version, "4", 30, clock.instant()));
        layer(version, 1, 30, PageRangeRow.TEXT);
        outcome(version, 1, 8, PageRangeRow.READ_DIRECTLY);
        UUID held = Ids.newId();
        write(() -> {
            materials.insertStep(StepRow.held(held, owner, version, "passages", 1, 30, clock.instant()));
            return true;
        });
        UUID layout = step(version, 9, 30);
        running.add(layout);
        List<java.util.Collection<UUID>> asked = new ArrayList<>();
        Materials watched = new Materials(materials, uploads, vault, files, ids -> {
            asked.add(List.copyOf(ids));
            return ids.stream().filter(running::contains).collect(java.util.stream.Collectors.toSet());
        }, new IngestionSettings(4), beansFor(transactions));

        assertThat(as(owner, () -> watched.find(material)).orElseThrow().pages().reading()).isEqualTo("text");
        assertThat(asked).singleElement().satisfies(ids -> assertThat(ids).containsExactly(layout));

        // The layout step ends, every page has an outcome, and the held step gets its job, which then runs.
        running.clear();
        write(() -> materials.endStep(layout, clock.instant()));
        outcome(version, 9, 30, PageRangeRow.READ_DIRECTLY);
        UUID job = Ids.newId();
        write(() -> materials.startHeldStep(held, job));
        running.add(job);
        asked.clear();

        Materials.Pages pages = as(owner, () -> watched.find(material)).orElseThrow().pages();

        assertThat(pages.reading()).isNull();
        assertThat(pages.done()).isEqualTo(30);
        assertThat(asked).isEmpty();
    }

    // --- passages and outline (Story 1.18)

    @Test
    void aPassageNamesItsMaterialItsSectionPathAndItsPageToItsOwnerOnly() {
        UUID material = create(owner, "Livro do Mestre.pdf", Uploads.Details.NONE);
        UUID version = versionOf(material).id();
        UUID first = passage(version, 0, 21, List.of("Magic Items", "Vorpal Sword"), "Vorpal Sword\nA legendary blade.");
        UUID second = passage(version, 1, 22, List.of(), "Prefácio");
        // Story 1.20: only a ready version's passages are read; until then none is.
        for (String state : List.of("processing", "needs_review", "failed", "superseded", "discarded")) {
            setState(material, state);
            assertThat(as(owner, () -> reads.passage(first))).as(state).isEmpty();
        }
        setState(material, "ready");

        Materials.Passage found = as(owner, () -> reads.passage(first)).orElseThrow();

        assertThat(found.id()).isEqualTo(first);
        assertThat(found.materialId()).isEqualTo(material);
        assertThat(found.materialName()).isEqualTo("Livro do Mestre.pdf");
        assertThat(found.versionId()).isEqualTo(version);
        assertThat(found.sectionPath()).containsExactly("Magic Items", "Vorpal Sword");
        assertThat(found.page()).isEqualTo(21);
        assertThat(found.text()).isEqualTo("Vorpal Sword\nA legendary blade.");
        assertThat(found.toString()).doesNotContain("Vorpal").doesNotContain("Livro");
        assertThat(as(owner, () -> reads.passage(second)).orElseThrow().sectionPath()).isEmpty();
        // Matrix row "Two owners": B asks for A's passage id, and for an unknown one.
        assertThat(as(other, () -> reads.passage(first))).isEmpty();
        assertThat(as(owner, () -> reads.passage(Ids.newId()))).isEmpty();
        // Nothing is stored in the clear.
        assertThat(new String(materials.passages.get(first).text(), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("Vorpal");
        assertThat(new String(materials.passages.get(first).place(), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("Magic");
    }

    @Test
    void aPassageCopiedToAnotherRowFailsItsIntegrityCheck() {
        UUID material = create(owner, "livro.pdf", Uploads.Details.NONE);
        UUID version = versionOf(material).id();
        UUID first = passage(version, 0, 1, List.of("A"), "Primeiro");
        UUID second = passage(version, 1, 2, List.of("B"), "Segundo");
        setState(material, "ready");
        io.github.gmshield.materials.data.PassageRow original = materials.passages.get(second);
        materials.passages.put(second, new io.github.gmshield.materials.data.PassageRow(second, owner, version, 1, 2,
                materials.passages.get(first).text(), original.place(), original.createdAt()));

        assertThatThrownBy(() -> as(owner, () -> reads.passage(second)))
                .isInstanceOf(io.github.gmshield.vault.service.IntegrityException.class);
    }

    @Test
    void theOutlineOfAVersionIsItsOwnersOnly() {
        UUID material = create(owner, "livro.pdf", Uploads.Details.NONE);
        UUID version = versionOf(material).id();
        assertThat(as(owner, () -> reads.outline(version))).isEmpty();
        UUID row = Ids.newId();
        write(() -> {
            materials.insertOutline(new io.github.gmshield.materials.data.OutlineRow(row, owner, version,
                    vault.encrypt(Passages.outline(row, owner), Passages.outlineJson(List.of(
                            new Passages.Entry(1, "Magic Items", 19), new Passages.Entry(2, "Vorpal Sword", 21)))),
                    clock.instant()));
            return true;
        });

        assertThat(as(owner, () -> reads.outline(version))).hasValueSatisfying(sections -> assertThat(sections)
                .containsExactly(new Materials.Section(1, "Magic Items", 19), new Materials.Section(2, "Vorpal Sword",
                        21)));
        assertThat(as(other, () -> reads.outline(version))).isEmpty();
        assertThat(as(owner, () -> reads.outline(Ids.newId()))).isEmpty();
    }

    // --- Story 1.19: pages on the review ground

    @Test
    void aPageIsServedOnTheReviewGroundOnlyFromTheCurrentVersionWhileItNeedsReviewOrIsReady() throws Exception {
        UUID material = create(owner, "livro.pdf", Uploads.Details.NONE);
        UUID version = versionOf(material).id();
        write(() -> materials.recordIngestion(version, "5", 3, clock.instant()));
        byte[] webp = "RIFF\0\0\0\0WEBPVP8 picture".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        UUID picture = pageView(version, 2, webp, List.of(new PageViews.Line("Plate | 1,500 gp | 18",
                List.of(0.1, 0.2, 0.9, 0.25))));
        UUID first = passage(version, 0, 2, List.of("Armor"), "Plate");
        passage(version, 1, 3, List.of("Armor"), "Shield");

        for (String state : List.of("waiting", "processing", "failed", "superseded", "discarded")) {
            setState(material, state);
            assertThat(as(owner, () -> reads.page(material, 2, Materials.Ground.REVIEW))).as(state).isEmpty();
            assertThat(as(owner, () -> reads.picture(material, 2, Materials.Ground.REVIEW))).as(state).isEmpty();
        }
        for (String state : List.of("needs_review", "ready")) {
            setState(material, state);
            Materials.VersionPage page = as(owner, () -> reads.page(material, 2, Materials.Ground.REVIEW))
                    .orElseThrow();
            assertThat(page.page()).isEqualTo(2);
            assertThat(page.pageTotal()).isEqualTo(3);
            assertThat(page.picture()).isEqualTo(new Materials.PictureSize(1545, 2000));
            assertThat(page.lines()).containsExactly(new Materials.Line("Plate | 1,500 gp | 18",
                    List.of(0.1, 0.2, 0.9, 0.25)));
            assertThat(page.text()).isNull();
            assertThat(page.passages()).containsExactly(new Materials.PagePassage(first,
                    List.of(List.of(0.1, 0.2, 0.9, 0.3)), null));
            assertThat(page.toString()).doesNotContain("Plate");
            Materials.Picture found = as(owner, () -> reads.picture(material, 2, Materials.Ground.REVIEW)).orElseThrow();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            found.writeTo(out);
            assertThat(out.toByteArray()).isEqualTo(webp);
            assertThat(found.size()).isEqualTo(webp.length);
        }
        // A page with no view, a page outside 1..3, another owner, an unknown material.
        assertThat(as(owner, () -> reads.page(material, 1, Materials.Ground.REVIEW))).isEmpty();
        assertThat(as(owner, () -> reads.page(material, 4, Materials.Ground.REVIEW))).isEmpty();
        assertThat(as(owner, () -> reads.page(material, 0, Materials.Ground.REVIEW))).isEmpty();
        assertThat(as(other, () -> reads.page(material, 2, Materials.Ground.REVIEW))).isEmpty();
        assertThat(as(other, () -> reads.picture(material, 2, Materials.Ground.REVIEW))).isEmpty();
        assertThat(as(owner, () -> reads.page(Ids.newId(), 2, Materials.Ground.REVIEW))).isEmpty();
        // Only the material's current version is served: a newer one that is processing hides the page.
        addVersion(material, "processing");
        assertThat(as(owner, () -> reads.page(material, 2, Materials.Ground.REVIEW))).isEmpty();
        assertThat(picture).isNotNull();
        // Grounds are words: review only, in this release.
        assertThat(Materials.Ground.of("review")).hasValue(Materials.Ground.REVIEW);
        assertThat(Materials.Ground.of(null)).isEmpty();
        assertThat(Materials.Ground.of("scope")).isEmpty();
        assertThat(Materials.Ground.of("Review")).isEmpty();
    }

    @Test
    void aTextPagesViewIsItsTextAndItsContentIsStoredOnlyEncrypted() {
        UUID material = create(owner, "regras.md", Uploads.Details.NONE);
        UUID version = versionOf(material).id();
        write(() -> materials.recordIngestion(version, "5", 1, clock.instant()));
        setState(material, "needs_review");
        UUID id = Ids.newId();
        write(() -> {
            materials.insertPageView(new io.github.gmshield.materials.data.PageViewRow(id, owner, version, 1, null,
                    null, null, vault.encrypt(PageViews.content(id, owner), PageViews.contentJson(
                            new PageViews.Content(null, "# Regras da Casa\n"))), clock.instant()));
            return true;
        });

        Materials.VersionPage page = as(owner, () -> reads.page(material, 1, Materials.Ground.REVIEW)).orElseThrow();
        assertThat(page.picture()).isNull();
        assertThat(page.lines()).isEmpty();
        assertThat(page.text()).isEqualTo("# Regras da Casa\n");
        assertThat(as(owner, () -> reads.picture(material, 1, Materials.Ground.REVIEW))).isEmpty();
        assertThat(new String(materials.pageViews.get(id).content(), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("Regras");
        // Moved to another row, the ciphertext fails its integrity check.
        UUID moved = Ids.newId();
        materials.pageViews.put(moved, new io.github.gmshield.materials.data.PageViewRow(moved, owner, version, 1,
                null, null, null, materials.pageViews.remove(id).content(), clock.instant()));
        assertThatThrownBy(() -> as(owner, () -> reads.page(material, 1, Materials.Ground.REVIEW)))
                .isInstanceOf(io.github.gmshield.vault.service.IntegrityException.class);
    }

    /** A picture page's view of the owner's version, its picture stored as a file of the version; answers the file. */
    private UUID pageView(UUID version, int page, byte[] picture, List<PageViews.Line> lines) throws Exception {
        UUID fileId = Ids.newId();
        files.writePiece(owner, fileId, 0, picture);
        UUID id = Ids.newId();
        write(() -> {
            materials.insertVersionFile(new io.github.gmshield.materials.data.VersionFileRow(Ids.newId(), owner,
                    version, fileId, "picture", page, page, picture.length, 1, clock.instant()));
            materials.insertPageView(new io.github.gmshield.materials.data.PageViewRow(id, owner, version, page,
                    fileId, 1545, 2000, vault.encrypt(PageViews.content(id, owner), PageViews.contentJson(
                            new PageViews.Content(lines, null))), clock.instant()));
            return true;
        });
        return fileId;
    }

    /** A passage of the owner's version, stored as the report stores it; answers its id. */
    private UUID passage(UUID version, int ordinal, int page, List<String> path, String text) {
        UUID id = Ids.newId();
        write(() -> {
            materials.insertPassages(List.of(new io.github.gmshield.materials.data.PassageRow(id, owner, version,
                    ordinal, page, vault.encryptText(Passages.text(id, owner), text),
                    vault.encrypt(Passages.place(id, owner), Passages.placeJson(new Passages.Place(path,
                            List.of(List.of(0.1, 0.2, 0.9, 0.3)), null))), clock.instant())));
            return true;
        });
        return id;
    }

    private static org.springframework.beans.factory.ObjectProvider<PlatformTransactionManager> beansFor(
            PlatformTransactionManager manager) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("transactions", manager);
        return beans.getBeanProvider(PlatformTransactionManager.class);
    }

    private StepRow stepRow(Integer first, Integer last) {
        return new StepRow(Ids.newId(), owner, Ids.newId(), Ids.newId(), "any", first, last, null, clock.instant(), null);
    }

    /** An unended step over pages of the version, with a job id of its own; answers that job id. */
    private UUID step(UUID version, int first, int last) {
        UUID job = Ids.newId();
        clock.advance(Duration.ofSeconds(1));
        write(() -> {
            materials.insertStep(new StepRow(Ids.newId(), owner, version, job, "any", first, last, Ids.newId(),
                    clock.instant(), null));
            return true;
        });
        return job;
    }

    private void layer(UUID version, int first, int last, String value) {
        write(() -> {
            materials.insertPageLayer(new PageRangeRow(Ids.newId(), owner, version, first, last, value,
                    clock.instant()));
            return true;
        });
    }

    private void outcome(UUID version, int first, int last, String value) {
        write(() -> {
            materials.insertPageOutcome(new PageRangeRow(Ids.newId(), owner, version, first, last, value,
                    clock.instant()));
            return true;
        });
    }

    private PageRangeRow range(int first, int last, String value) {
        return new PageRangeRow(Ids.newId(), owner, Ids.newId(), first, last, value, clock.instant());
    }

    @Test
    void theCursorAndTheLimitPageThroughTheList() {
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(create(owner, "m" + i + ".pdf", Uploads.Details.NONE));
        }
        List<UUID> newestFirst = created.reversed();

        Materials.Page first = as(owner, () -> reads.list(List.of("uploading"), null, 2));
        assertThat(first.items()).extracting(Materials.Material::id).containsExactlyElementsOf(newestFirst.subList(0, 2));
        assertThat(first.nextCursor()).isEqualTo(newestFirst.get(1));
        Materials.Page second = as(owner, () -> reads.list(List.of("uploading"), first.nextCursor(), 2));
        assertThat(second.items()).extracting(Materials.Material::id).containsExactlyElementsOf(newestFirst.subList(2, 4));
        Materials.Page last = as(owner, () -> reads.list(List.of("uploading"), second.nextCursor(), 2));
        assertThat(last.items()).extracting(Materials.Material::id).containsExactly(newestFirst.get(4));
        assertThat(last.nextCursor()).isNull();
        assertThat(as(owner, () -> reads.list(List.of("uploading"), null, 5)).nextCursor()).isNull();
        assertThat(as(owner, () -> reads.list(List.of("uploading"), null, 50)).items()).hasSize(5);
        assertThat(as(owner, () -> reads.list(List.of("uploading"), null, 1)).items()).hasSize(1);

        for (int limit : new int[] {0, -1, 51}) {
            assertThatThrownBy(() -> as(owner, () -> reads.list(List.of("uploading"), null, limit)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> as(owner, () -> reads.list(List.of(), null, 20))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> as(owner, () -> reads.list(List.of("lost"), null, 20)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneMaterialIsTheOwnersOnlyAndAnotherOwnersReadsLikeAnUnknownOne() {
        UUID mine = create(owner, "Livro do Jogador.pdf", new Uploads.Details("homebrew", null, null, null));
        setState(mine, "waiting");

        Materials.Material found = as(owner, () -> reads.find(mine)).orElseThrow();
        assertThat(found.name()).isEqualTo("Livro do Jogador.pdf");
        assertThat(found.state()).isEqualTo("waiting");
        assertThat(found.kind()).isEqualTo("homebrew");
        assertThat(found.upload()).isNull();

        assertThat(as(other, () -> reads.find(mine))).isEmpty();
        assertThat(as(owner, () -> reads.find(UUID.randomUUID()))).isEmpty();
        assertThat(as(other, () -> reads.list(Materials.STATES, null, 50)).items()).isEmpty();
    }

    // --- helpers ---

    /**
     * A material created through the upload, with its version in {@code uploading}: its id. Ids made in the same
     * millisecond sort randomly ({@code Ids}), so each comes a millisecond after the one before.
     */
    private UUID create(UUID gm, String name, Uploads.Details details) {
        pause();
        UUID upload = as(gm, () -> service.create(10, name, details)).uploadId();
        return materials.versions.get(uploads.row(upload).versionId()).materialId();
    }

    private static void pause() {
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void setState(UUID material, String state) {
        VersionRow version = materials.versions.values().stream().filter(row -> row.materialId().equals(material))
                .findFirst().orElseThrow();
        write(() -> materials.changeVersion(version.id(), state, "pdf", 10L, clock.instant()));
    }

    private VersionRow versionOf(UUID material) {
        return materials.versions.values().stream().filter(row -> row.materialId().equals(material)).findFirst()
                .orElseThrow();
    }

    private void addVersion(UUID material, String state) {
        write(() -> {
            materials.insertVersion(new VersionRow(Ids.newId(), owner, material, state, "pdf", 10L, clock.instant(),
                    clock.instant()));
            return true;
        });
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

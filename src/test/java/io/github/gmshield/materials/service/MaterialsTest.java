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

    @BeforeEach
    void wire() {
        FileStore files = TestVaults.files(vault, root);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("transactions", transactions);
        beans.addBean("clock", clock);
        library = TestLibraries.library(libraryStore, vault, beans.getBeanProvider(PlatformTransactionManager.class),
                beans.getBeanProvider(Clock.class));
        service = new Uploads(materials, uploads, vault, files, library,
                beans.getBeanProvider(PlatformTransactionManager.class), beans.getBeanProvider(Clock.class));
        reads = new Materials(materials, uploads, vault, beans.getBeanProvider(PlatformTransactionManager.class));
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

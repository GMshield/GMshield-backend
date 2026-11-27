package io.github.gmshield.artifacts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.conversations.service.TurnReference;
import io.github.gmshield.library.service.Language;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.OwnerContext;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Story 5.7 part A, the renderers without the application: an export never creates a file (PDF, TXT, Markdown, JPG and a
 * zip, with ImageIO's disk cache left on in a folder no file can be made in, every file made in that folder and every
 * ImageIO or PDFBox file made in {@code java.io.tmpdir} watched), a source's line, a safe file name, the JPG's scale
 * for a long artifact, and characters the fonts lack or that are not text.
 */
class ArtifactExportsTest {

    private static final Map<String, String> TYPES = Map.of("item", "Item", "creature", "Criatura");

    @Test
    void anExportNeverCreatesAFileOnDisk(@TempDir Path parent) throws Exception {
        boolean cache = ImageIO.getUseCache();
        File cacheDirectory = ImageIO.getCacheDirectory();
        // ImageIO's disk cache stays on, in a folder no file can be made in: a cache file would fail the render.
        Path imageCache = Files.createDirectory(parent.resolve("imageio-cache"));
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        List<ExportSheet> sheets = List.of(sheet("Espada de Kael", "Forjada nas montanhas.\n".repeat(400)),
                sheet("Escudo", "Leve."));
        byte[] pdf;
        byte[] txt;
        byte[] markdown;
        byte[] jpg;
        byte[] zip;
        List<String> created = new ArrayList<>();
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            // Every file made in either folder is seen, even one deleted at once (a cache file, a stream cache).
            tmp.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            imageCache.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            ImageIO.setUseCache(true);
            ImageIO.setCacheDirectory(imageCache.toFile());
            assertThat(imageCache.toFile().setWritable(false, false)).isTrue();

            pdf = ExportPdf.render(sheets, ExportLabels.PT_BR, new ExportPdf.Pages());
            txt = ExportText.txt(sheets, ExportLabels.PT_BR);
            markdown = ExportText.markdown(sheets, ExportLabels.PT_BR);
            jpg = ExportJpg.render(sheets.getFirst(), ExportLabels.PT_BR, new ExportPdf.Pages());
            zip = ExportJpg.zip(List.of(new ExportJpg.Entry("01-a.jpg", jpg), new ExportJpg.Entry("02-b.jpg",
                    ExportJpg.render(sheets.get(1), ExportLabels.EN, new ExportPdf.Pages()))));

            for (WatchKey key = watcher.poll(500, TimeUnit.MILLISECONDS); key != null;
                    key = watcher.poll(500, TimeUnit.MILLISECONDS)) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    // Only the files a render could make: ImageIO's cache (imageio*.tmp) and PDFBox's scratch and
                    // temp-file stream caches (PDFBox*.tmp). java.io.tmpdir is shared with other processes, whose
                    // files would make this test fail at random.
                    String name = String.valueOf(event.context());
                    if (name.startsWith("imageio") || name.startsWith("PDFBox")
                            || key.watchable().equals(imageCache)) {
                        created.add(key.watchable() + "/" + name);
                    }
                }
                key.reset();
            }
        } finally {
            ImageIO.setUseCache(cache);
            ImageIO.setCacheDirectory(cacheDirectory);
            imageCache.toFile().setWritable(true, false);
        }

        assertThat(created).as("files made during the export").isEmpty();
        assertThat(pdf).isNotEmpty();
        assertThat(txt).isNotEmpty();
        assertThat(markdown).isNotEmpty();
        assertThat(zip).isNotEmpty();
        // The long artifact's image holds every page of its own PDF.
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpg));
        int pages = pages(ExportPdf.render(List.of(sheets.getFirst()), ExportLabels.PT_BR, new ExportPdf.Pages()));
        assertThat(pages).isGreaterThan(1);
        assertThat(image.getHeight()).isEqualTo(pages * 1286);
        assertThat(pages(pdf)).isEqualTo(pages + 1);
    }

    @Test
    void eachKindOfSourceIsOneLineAndARemovedOneIsMarked() {
        TurnReference place = new TurnReference(1, "SRD", List.of("Magias", "Fire Bolt"), 12, List.of(UUID.randomUUID()),
                false, "system");
        TurnReference gone = new TurnReference(2, "SRD", List.of(), 0, List.of(), true, "system");
        TurnReference record = new TurnReference(3, "SRD", List.of("Itens"), 4, List.of(), false, "system",
                new TurnReference.Cited(UUID.randomUUID(), "item", "Corda", List.of()));
        TurnReference artifact = new TurnReference(4, null, List.of(), 0, List.of(), true, "campaign",
                new TurnReference.Cited(UUID.randomUUID(), "arma_magica", "Lâmina", List.of(), 3));

        assertThat(ArtifactExports.source(place, TYPES, ExportLabels.PT_BR)).isEqualTo("SRD, Magias > Fire Bolt, p. 12");
        assertThat(ArtifactExports.source(gone, TYPES, ExportLabels.EN)).isEqualTo("SRD (source removed)");
        assertThat(ArtifactExports.source(record, TYPES, ExportLabels.PT_BR)).isEqualTo("Item: Corda");
        // A type the system no longer names prints its key.
        assertThat(ArtifactExports.source(artifact, TYPES, ExportLabels.PT_BR))
                .isEqualTo("arma_magica: Lâmina (versão 3) (fonte removida)");
    }

    @Test
    void aFileNameIsMadeSafe() {
        assertThat(ArtifactExports.safeName("Espada de Kael", "artefato")).isEqualTo("Espada de Kael");
        assertThat(ArtifactExports.safeName(" ../a/b\\c:d*e?f\"g<h>i|j\tk\u202El ", "artefato"))
                .isEqualTo("a b c d e f g h i j k l");
        assertThat(ArtifactExports.safeName("...", "artefato")).isEqualTo("artefato");
        // At most 200 UTF-8 bytes, cut after a whole character: 50 emoji of 4 bytes, never half of one.
        assertThat(ArtifactExports.safeName("\uD83D\uDE00".repeat(150), "x")).isEqualTo("\uD83D\uDE00".repeat(50));
        assertThat(ArtifactExports.safeName("a".repeat(199) + "x\u0301b", "x")).isEqualTo("a".repeat(199));
        assertThat(ArtifactExports.safeName("é".repeat(150), "x").getBytes(StandardCharsets.UTF_8)).hasSize(200);
        // A reserved device name, whatever its case, extension or the spaces before its extension.
        for (String reserved : List.of("CON", "con.txt", "CON .txt", "Nul", "COM0", "LPT0", "COM\u00B9", "com\u00B2",
                "LPT\u00B3", "CONIN$", "conout$.md", "COM9", "LPT1")) {
            assertThat(ArtifactExports.safeName(reserved, "x")).as(reserved).isEqualTo("_" + reserved);
        }
        assertThat(ArtifactExports.safeName("Console", "x")).isEqualTo("Console");
        assertThat(ArtifactExports.safeName("COM10", "x")).isEqualTo("COM10");
    }

    @Test
    void aVeryLongArtifactKeepsEveryPageAtALowerResolution() {
        float a4Width = ExportPdf.PAGE.getWidth();
        float a4Height = ExportPdf.PAGE.getHeight();

        assertThat(ExportJpg.scale(1, a4Width, a4Height)).isEqualTo(110f / 72);
        float many = ExportJpg.scale(200, a4Width, a4Height);
        assertThat(many).isLessThan(110f / 72);
        assertThat(Math.floor(a4Height * many) * 200).isLessThanOrEqualTo(ExportJpg.MAX_HEIGHT);
        assertThat(Math.floor(a4Width * many) * Math.floor(a4Height * many) * 200)
                .isLessThanOrEqualTo(ExportJpg.MAX_PIXELS);
        // Under the JDK JPEG writer's own limit.
        assertThat(ExportJpg.MAX_HEIGHT).isLessThanOrEqualTo(65_500);
    }

    @Test
    void aLongArtifactsJpgTakesTheLowerResolutionAndStaysWithinItsLimitsWithWholePages() throws Exception {
        // About 30 pages: past the pixel budget at 110 DPI (about 20 pages), within the 200-page cap.
        ExportSheet sheet = sheet("Crônica", "Linha da história de Kael, com acentos: ação, coração, pão.\n".repeat(1500));
        ExportPdf.Pages counted = new ExportPdf.Pages();

        byte[] jpg = ExportJpg.render(sheet, ExportLabels.PT_BR, counted);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpg));
        int pages = counted.used();
        assertThat(pages).isBetween(25, 200);
        assertThat(image.getWidth()).isLessThan(909);
        assertThat(image.getHeight()).isLessThanOrEqualTo(ExportJpg.MAX_HEIGHT);
        assertThat((long) image.getWidth() * image.getHeight()).isLessThanOrEqualTo(ExportJpg.MAX_PIXELS);
        assertThat(image.getHeight() % pages).isZero();
        assertThat(image.getHeight() / pages).isEqualTo((int) Math.floor(ExportPdf.PAGE.getHeight()
                * ExportJpg.scale(pages, ExportPdf.PAGE.getWidth(), ExportPdf.PAGE.getHeight())));
    }

    @Test
    void theTwoHundredAndFirstPageIsRefusedAsTooLarge() {
        ExportPdf.Pages pages = new ExportPdf.Pages();
        for (int i = 0; i < ExportPdf.Pages.MAX; i++) {
            pages.add();
        }

        assertThatThrownBy(pages::add).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.problem().status()).isEqualTo(400);
            assertThat(e.problem().code()).isEqualTo("artifact.export_too_large");
        });
        // A PDF of more pages stops there, whatever it holds after.
        ExportPdf.Pages fresh = new ExportPdf.Pages();
        ExportSheet huge = sheet("Longa", "Linha.\n".repeat(20_000));
        assertThatThrownBy(() -> ExportPdf.render(List.of(huge), ExportLabels.PT_BR, fresh))
                .isInstanceOf(ApiException.class);
        assertThat(fresh.used()).isEqualTo(ExportPdf.Pages.MAX + 1);
    }

    @Test
    void aThirdPdfOrJpgExportWhileTwoRenderIsBusyAtOnceAndTextExportsAreNotGated() {
        ExportGate gate = new ExportGate();
        assertThat(gate.tryEnter()).isTrue();
        assertThat(gate.tryEnter()).isTrue();
        // Nothing is read once the gate refuses: the services are never reached.
        ArtifactExports exports = new ArtifactExports(null, null, null, gate);
        List<UUID> ids = List.of(UUID.randomUUID());

        OwnerContext.runAs(UUID.randomUUID(), () -> {
            for (ArtifactExports.Format format : List.of(ArtifactExports.Format.PDF, ArtifactExports.Format.JPG)) {
                assertThatThrownBy(() -> exports.export(UUID.randomUUID(), ids, format, Language.PT_BR))
                        .isInstanceOfSatisfying(ApiException.class, e -> {
                            assertThat(e.problem().status()).isEqualTo(503);
                            assertThat(e.problem().code()).isEqualTo("artifact.export_busy");
                        });
            }
            // TXT is not gated: it goes on to the reads (here none, so it fails there, not on the gate).
            assertThatThrownBy(() -> exports.export(UUID.randomUUID(), ids, ArtifactExports.Format.TXT,
                    Language.PT_BR)).isInstanceOf(NullPointerException.class);
        });
        // A refused export leaves nothing held; one that ends gives its place back.
        gate.leave();
        assertThat(gate.tryEnter()).isTrue();
        assertThat(gate.tryEnter()).isFalse();
    }

    @Test
    void aCharacterTheFontsLackPrintsAsAQuestionMarkAndControlsAreLeftOut() throws Exception {
        ExportSheet sheet = sheet("Kael 😀 中", "a\u0007b\tc​d ação");

        String text = pdfText(ExportPdf.render(List.of(sheet), ExportLabels.PT_BR, new ExportPdf.Pages()));

        assertThat(text).contains("Kael ? ?").containsPattern("ab\\s+cd ação");
    }

    private static ExportSheet sheet(String name, String content) {
        return new ExportSheet(name, "Item", List.of(new Artifacts.Field("Dano", "1d8")), content,
                List.of(new ExportSheet.Source(1, "SRD, p. 3")));
    }

    private static int pages(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        }
    }

    private static String pdfText(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }
}

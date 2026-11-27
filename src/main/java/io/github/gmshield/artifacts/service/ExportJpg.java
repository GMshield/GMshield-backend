package io.github.gmshield.artifacts.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * The JPG export (Story 5.7): one artifact's PDF ({@link ExportPdf}) rendered page by page at {@value #DPI} DPI into one
 * tall image, the pages stacked top to bottom with a thin grey line between them, written with {@code ImageIO}. Several
 * artifacts are packed with {@code java.util.zip}, one image each.
 *
 * <p>Everything stays in memory: the PDF is read back with PDFBox's memory-only stream cache, and the JPEG is written
 * through a {@link MemoryCacheImageOutputStream} (ImageIO's default cache for a stream is a temporary file). An image
 * is at most {@value #MAX_HEIGHT} pixels tall (the JDK's JPEG writer takes at most 65,500) and {@value #MAX_PIXELS}
 * pixels in all, so a very long artifact is drawn at a lower resolution rather than exhausting the memory; every page
 * is always there, each a whole number of pixels tall.
 */
final class ExportJpg {

    static final int DPI = 110;
    static final int MAX_HEIGHT = 65_000;
    static final long MAX_PIXELS = 24_000_000L;
    private static final float POINTS_PER_INCH = 72;
    private static final float QUALITY = 0.85f;
    private static final Color PAGE_BREAK = new Color(0xC8, 0xC8, 0xC8);

    private ExportJpg() {
    }

    /** One artifact as one JPEG image of all its pages, counted in {@code budget}. */
    static byte[] render(ExportSheet sheet, ExportLabels labels, ExportPdf.Pages budget) {
        byte[] pdf = ExportPdf.render(List.of(sheet), labels, budget);
        try (PDDocument document = Loader.loadPDF(pdf, "", null, null, IOUtils.createMemoryOnlyStreamCache())) {
            int pages = document.getNumberOfPages();
            PDRectangle box = document.getPage(0).getMediaBox();
            float scale = scale(pages, box.getWidth(), box.getHeight());
            // Whole pixels, rounded down: the stacked pages never pass MAX_HEIGHT nor MAX_PIXELS.
            int width = (int) Math.floor(box.getWidth() * scale);
            int pageHeight = Math.min((int) Math.floor(box.getHeight() * scale), MAX_HEIGHT / pages);
            BufferedImage image = new BufferedImage(width, pageHeight * pages, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setBackground(Color.WHITE);
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, pageHeight * pages);
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                PDFRenderer renderer = new PDFRenderer(document);
                for (int page = 0; page < pages; page++) {
                    Graphics2D onPage = (Graphics2D) graphics.create(0, page * pageHeight, width, pageHeight);
                    try {
                        renderer.renderPageToGraphics(page, onPage, scale);
                    } finally {
                        onPage.dispose();
                    }
                    if (page > 0) {
                        graphics.setColor(PAGE_BREAK);
                        graphics.drawLine(0, page * pageHeight, width, page * pageHeight);
                    }
                }
            } finally {
                graphics.dispose();
            }
            return jpeg(image);
        } catch (IOException e) {
            throw new UncheckedIOException("artifacts.export_jpg_failed", e);
        }
    }

    /**
     * The scale from PDF points to pixels: {@value #DPI} DPI, or less when {@code pages} pages that size would make an
     * image over {@value #MAX_HEIGHT} pixels tall or over {@value #MAX_PIXELS} pixels in all.
     */
    static float scale(int pages, float width, float height) {
        float scale = DPI / POINTS_PER_INCH;
        float byHeight = MAX_HEIGHT / (pages * height);
        float byPixels = (float) Math.sqrt(MAX_PIXELS / ((double) pages * width * height));
        return Math.min(scale, Math.min(byHeight, byPixels));
    }

    /** These images, each with its entry's name, as one zip, in the order given. */
    static byte[] zip(List<Entry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.bytes());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("artifacts.export_zip_failed", e);
        }
        return out.toByteArray();
    }

    /** One file of a zip: its name and bytes. {@code toString} names the size only. */
    record Entry(String name, byte[] bytes) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Entry entry && name.equals(entry.name)
                    && Arrays.equals(bytes, entry.bytes);
        }

        @Override
        public int hashCode() {
            return 31 * name.hashCode() + Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "Entry[bytes=" + bytes.length + "]";
        }
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("artifacts.export_jpeg_writer_missing");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(QUALITY);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}

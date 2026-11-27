package io.github.gmshield.artifacts.service;

import io.github.gmshield.shared.service.ApiException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * The PDF export (Story 5.7), with Apache PDFBox: A4 pages, each artifact from a new page, in the order given. The name
 * in bold, the record type's name, the fields ("name: value", the name in bold), the content, then the sources as
 * numbered lines under their title. Lines wrap by the font's widths; a word wider than a line is cut where it fills it.
 *
 * <p>The fonts are Noto Sans Regular and Bold (SIL Open Font License, {@code fonts/OFL.txt}), read once from the
 * classpath and embedded as subsets, so accents print the same everywhere. A character neither has (an emoji, a CJK
 * ideograph) prints as {@code ?}; a tab as four spaces; other control characters are left out. The document is built
 * with PDFBox's memory-only stream cache and saved to bytes: nothing is written to disk. Text is data: it is drawn as
 * text and never interpreted (Markdown included). Every page is counted ({@link Pages}): an export stops at the 201st.
 */
final class ExportPdf {

    static final PDRectangle PAGE = PDRectangle.A4;
    private static final float MARGIN = 56;
    private static final float TITLE_SIZE = 18;
    private static final float HEADING_SIZE = 13;
    private static final float BODY_SIZE = 11;
    private static final float LEADING = 1.4f;
    private static final float GAP = 10;
    private static final float CONTINUATION_INDENT = 14;
    private static final byte[] REGULAR = font("fonts/NotoSans-Regular.ttf");
    private static final byte[] BOLD = font("fonts/NotoSans-Bold.ttf");

    private ExportPdf() {
    }

    /**
     * The pages one export may still add (Story 5.7, Dalton's limit): {@value #MAX} PDF pages in all, across every PDF the
     * export builds (one per artifact for a JPG). The page past the last is refused with
     * {@link ArtifactExports#EXPORT_TOO_LARGE} before it is drawn, so the rest is never built.
     */
    static final class Pages {
        static final int MAX = 200;
        private int used;

        void add() {
            used++;
            if (used > MAX) {
                throw new ApiException(ArtifactExports.EXPORT_TOO_LARGE);
            }
        }

        int used() {
            return used;
        }
    }

    /** The PDF of these artifacts, each from a new page, its pages counted in {@code pages}. */
    static byte[] render(List<ExportSheet> sheets, ExportLabels labels, Pages pages) {
        try (PDDocument document = new PDDocument(IOUtils.createMemoryOnlyStreamCache())) {
            Writer writer = new Writer(document, pages);
            for (ExportSheet sheet : sheets) {
                writer.sheet(sheet, labels);
            }
            writer.close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("artifacts.export_pdf_failed", e);
        }
    }

    private static byte[] font(String resource) {
        try (InputStream in = ExportPdf.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("artifacts.export_font_missing");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("artifacts.export_font_unreadable", e);
        }
    }

    /** A piece of a paragraph in one font. */
    private record Run(PDType0Font font, String text) {
        @Override
        public String toString() {
            return "Run";
        }
    }

    /** A word placed on a line: its font, text and width. */
    private record Placed(PDType0Font font, String text, float width) {
        @Override
        public String toString() {
            return "Placed";
        }
    }

    /** Lays the sheets out on pages, one content stream open at a time. */
    private static final class Writer {

        private final PDDocument document;
        private final PDType0Font regular;
        private final PDType0Font bold;
        private final Map<Integer, Boolean> glyphs = new HashMap<>();
        private final Pages pages;
        private PDPageContentStream stream;
        private float y;

        Writer(PDDocument document, Pages pages) throws IOException {
            this.document = document;
            this.pages = pages;
            this.regular = PDType0Font.load(document, new ByteArrayInputStream(REGULAR), true);
            this.bold = PDType0Font.load(document, new ByteArrayInputStream(BOLD), true);
        }

        void sheet(ExportSheet sheet, ExportLabels labels) throws IOException {
            newPage();
            paragraph(List.of(new Run(bold, ExportText.oneLine(sheet.name()))), TITLE_SIZE, 0);
            paragraph(List.of(new Run(regular, ExportText.oneLine(sheet.type()))), BODY_SIZE, 0);
            if (!sheet.fields().isEmpty()) {
                gap();
                for (Artifacts.Field field : sheet.fields()) {
                    String[] lines = ExportText.lines(field.value()).split("\n", -1);
                    paragraph(List.of(new Run(bold, ExportText.oneLine(field.name()) + ": "),
                            new Run(regular, lines[0])), BODY_SIZE, CONTINUATION_INDENT);
                    for (int i = 1; i < lines.length; i++) {
                        paragraph(List.of(new Run(regular, lines[i])), BODY_SIZE, CONTINUATION_INDENT);
                    }
                }
            }
            if (sheet.content() != null) {
                gap();
                for (String line : ExportText.lines(sheet.content()).split("\n", -1)) {
                    paragraph(List.of(new Run(regular, line)), BODY_SIZE, 0);
                }
            }
            if (!sheet.sources().isEmpty()) {
                gap();
                paragraph(List.of(new Run(bold, labels.sources())), HEADING_SIZE, 0);
                for (ExportSheet.Source source : sheet.sources()) {
                    paragraph(List.of(new Run(regular, "[" + source.number() + "] "
                            + ExportText.oneLine(source.text()))), BODY_SIZE, CONTINUATION_INDENT);
                }
            }
        }

        void close() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }

        private void newPage() throws IOException {
            close();
            pages.add();
            PDPage page = new PDPage(PAGE);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = PAGE.getHeight() - MARGIN;
        }

        private void gap() {
            y -= GAP;
        }

        /**
         * One paragraph: its runs' words placed greedily on lines as wide as the page allows; the lines after the first
         * start at {@code indent}. A space where a line breaks is dropped; a word wider than a line is cut where it fills
         * it. An empty paragraph is an empty line.
         */
        private void paragraph(List<Run> runs, float size, float indent) throws IOException {
            float width = PAGE.getWidth() - 2 * MARGIN;
            Line line = new Line(size, indent);
            for (Run run : runs) {
                PDType0Font font = run.font();
                for (String piece : words(printable(run.text()))) {
                    String word = piece;
                    float wordWidth = width(font, word, size);
                    if (line.used + wordWidth > width - line.indent) {
                        if (word.isBlank()) {
                            line = line.flush();
                            continue;
                        }
                        if (!line.words.isEmpty() && wordWidth <= width - indent) {
                            line = line.flush();
                        }
                        while (line.used + wordWidth > width - line.indent) {
                            int cut = fitting(font, word, size, width - line.indent - line.used);
                            if (cut == 0 && !line.words.isEmpty()) {
                                line = line.flush();
                                continue;
                            }
                            cut = Math.max(cut, Character.charCount(word.codePointAt(0)));
                            String head = word.substring(0, cut);
                            line.add(font, head, width(font, head, size));
                            line = line.flush();
                            word = word.substring(cut);
                            wordWidth = width(font, word, size);
                        }
                    }
                    if (!word.isEmpty()) {
                        line.add(font, word, wordWidth);
                    }
                }
            }
            line.flush();
        }

        /** The line being filled: its words, the width they use and where it starts. */
        private final class Line {
            private final float size;
            private final float indent;
            private final float next;
            private final List<Placed> words = new ArrayList<>();
            private float used;

            Line(float size, float next) {
                this(size, 0, next);
            }

            private Line(float size, float indent, float next) {
                this.size = size;
                this.indent = indent;
                this.next = next;
            }

            void add(PDType0Font font, String text, float width) {
                words.add(new Placed(font, text, width));
                used += width;
            }

            /** Writes this line and answers the next, which starts at the paragraph's indent. */
            Line flush() throws IOException {
                write(words, size, indent);
                return new Line(size, next, next);
            }
        }

        /** Writes one line at the next place, on a new page when this one is full. */
        private void write(List<Placed> line, float size, float indent) throws IOException {
            float height = size * LEADING;
            if (y - height < MARGIN) {
                newPage();
            }
            y -= height;
            float x = MARGIN + indent;
            int at = 0;
            while (at < line.size()) {
                // The words of one font in a row are drawn as one text, their spaces included.
                PDType0Font font = line.get(at).font();
                StringBuilder text = new StringBuilder();
                float width = 0;
                while (at < line.size() && line.get(at).font() == font) {
                    text.append(line.get(at).text());
                    width += line.get(at).width();
                    at++;
                }
                if (!text.toString().isBlank()) {
                    stream.beginText();
                    stream.setFont(font, size);
                    stream.newLineAtOffset(x, y + (height - size) / 2);
                    stream.showText(text.toString());
                    stream.endText();
                }
                x += width;
            }
        }

        /** How many chars of {@code word} fit in {@code room}. */
        private int fitting(PDType0Font font, String word, float size, float room) throws IOException {
            int end = 0;
            while (end < word.length()) {
                int next = end + Character.charCount(word.codePointAt(end));
                if (width(font, word.substring(0, next), size) > room) {
                    break;
                }
                end = next;
            }
            return end;
        }

        private static float width(PDType0Font font, String text, float size) throws IOException {
            return font.getStringWidth(text) / 1000 * size;
        }

        /** The text's words, each space kept as a word of its own so that runs of spaces print as written. */
        private static List<String> words(String text) {
            List<String> words = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == ' ') {
                    if (i > start) {
                        words.add(text.substring(start, i));
                    }
                    words.add(" ");
                    start = i + 1;
                }
            }
            if (start < text.length()) {
                words.add(text.substring(start));
            }
            return words;
        }

        /**
         * The text as both fonts can print it: a tab as four spaces, any other space as a space, control and format
         * characters left out, and a character the fonts lack as {@code ?}.
         */
        private String printable(String text) {
            StringBuilder out = new StringBuilder(text.length());
            text.codePoints().forEach(codePoint -> {
                int type = Character.getType(codePoint);
                if (codePoint == '\t') {
                    out.append("    ");
                } else if (type == Character.SPACE_SEPARATOR) {
                    out.append(' ');
                } else if (!Character.isISOControl(codePoint) && type != Character.FORMAT
                        && type != Character.LINE_SEPARATOR && type != Character.PARAGRAPH_SEPARATOR) {
                    if (glyphs.computeIfAbsent(codePoint, this::printable)) {
                        out.appendCodePoint(codePoint);
                    } else {
                        out.append('?');
                    }
                }
            });
            return out.toString();
        }

        private boolean printable(int codePoint) {
            return encodes(regular, codePoint) && encodes(bold, codePoint);
        }

        private static boolean encodes(PDType0Font font, int codePoint) {
            try {
                font.encode(new String(Character.toChars(codePoint)));
                return true;
            } catch (IllegalArgumentException | IOException e) {
                return false;
            }
        }
    }
}

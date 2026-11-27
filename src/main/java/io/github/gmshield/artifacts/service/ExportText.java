package io.github.gmshield.artifacts.service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The TXT and Markdown exports (Story 5.7): one UTF-8 file, the artifacts in the order given, each a section of its own:
 * the name, the record type's name, the fields in the order written, the content, then the sources as numbered lines.
 *
 * <p>Text is data. In TXT it is written as it is (line ends as {@code \n}). In Markdown every character that could make
 * the artifact's text markup is escaped ({@link #escaped}), so a viewer shows exactly the text a PDF prints: a name, a
 * field or a content line never becomes a heading, a list, a link, an image, HTML, a table or code.
 */
final class ExportText {

    /** Between two artifacts of a TXT file. */
    static final String TXT_SEPARATOR = "\n========================================\n\n";
    /** Between two artifacts of a Markdown file: a thematic break. */
    static final String MARKDOWN_SEPARATOR = "\n---\n\n";
    /** Escaped wherever they are: they make emphasis, code, links, HTML, entities, tables or headings. */
    private static final String ALWAYS = "\\`*_[]<>&|~#";
    /** Escaped first on a line: they make lists, quotes and setext headings there. */
    private static final String FIRST = "-+=";
    private static final char NO_BREAK_SPACE = '\u00A0';

    private ExportText() {
    }

    /** The TXT file of these artifacts. */
    static byte[] txt(List<ExportSheet> sheets, ExportLabels labels) {
        StringBuilder out = new StringBuilder();
        for (ExportSheet sheet : sheets) {
            if (!out.isEmpty()) {
                out.append(TXT_SEPARATOR);
            }
            out.append(oneLine(sheet.name())).append('\n').append(oneLine(sheet.type())).append('\n');
            if (!sheet.fields().isEmpty()) {
                out.append('\n');
                for (Artifacts.Field field : sheet.fields()) {
                    out.append(oneLine(field.name())).append(": ").append(lines(field.value())).append('\n');
                }
            }
            if (sheet.content() != null) {
                out.append('\n').append(lines(sheet.content())).append('\n');
            }
            if (!sheet.sources().isEmpty()) {
                out.append('\n').append(labels.sources()).append('\n');
                for (ExportSheet.Source source : sheet.sources()) {
                    out.append('[').append(source.number()).append("] ").append(oneLine(source.text())).append('\n');
                }
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The Markdown file of these artifacts, every value escaped. */
    static byte[] markdown(List<ExportSheet> sheets, ExportLabels labels) {
        StringBuilder out = new StringBuilder();
        for (ExportSheet sheet : sheets) {
            if (!out.isEmpty()) {
                out.append(MARKDOWN_SEPARATOR);
            }
            out.append("# ").append(escaped(oneLine(sheet.name()))).append("\n\n");
            out.append('*').append(escaped(oneLine(sheet.type()))).append("*\n");
            if (!sheet.fields().isEmpty()) {
                out.append('\n');
                for (Artifacts.Field field : sheet.fields()) {
                    out.append("- **").append(escaped(oneLine(field.name()))).append(":** ")
                            .append(block(field.value(), "  ")).append('\n');
                }
            }
            if (sheet.content() != null) {
                out.append('\n').append(block(sheet.content(), "")).append('\n');
            }
            if (!sheet.sources().isEmpty()) {
                out.append("\n## ").append(labels.sources()).append("\n\n");
                for (ExportSheet.Source source : sheet.sources()) {
                    out.append("- \\[").append(source.number()).append("\\] ").append(escaped(oneLine(source.text())))
                            .append('\n');
                }
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A text of several lines as Markdown: each line escaped, the lines of a paragraph joined by hard breaks, blank lines
     * between paragraphs; every line after the first starts with {@code indent} (a list item's continuation).
     */
    static String block(String text, String indent) {
        StringBuilder out = new StringBuilder();
        boolean blank = false;
        for (String line : lines(text).split("\n", -1)) {
            if (line.isBlank()) {
                blank = !out.isEmpty();
                continue;
            }
            if (!out.isEmpty()) {
                out.append(blank ? "\n" + (indent.isEmpty() ? "" : indent.stripTrailing()) + "\n" : "\\\n")
                        .append(indent);
            }
            blank = false;
            out.append(escaped(line));
        }
        return out.toString();
    }

    /**
     * One line of the artifact's text as Markdown that shows exactly that text: leading spaces and tabs become no-break
     * spaces (never an indented code block), {@link #ALWAYS}' characters are escaped everywhere, {@link #FIRST}'s and a
     * number's {@code .} or {@code )} first on the line (never a list, a quote or a heading).
     */
    static String escaped(String line) {
        StringBuilder out = new StringBuilder(line.length() + 8);
        int at = 0;
        while (at < line.length() && (line.charAt(at) == ' ' || line.charAt(at) == '\t')) {
            out.append(line.charAt(at) == '\t' ? String.valueOf(NO_BREAK_SPACE).repeat(4)
                    : String.valueOf(NO_BREAK_SPACE));
            at++;
        }
        int digits = at;
        while (digits < line.length() && Character.isDigit(line.charAt(digits))) {
            digits++;
        }
        for (int i = at; i < line.length(); i++) {
            char c = line.charAt(i);
            boolean first = i == at && FIRST.indexOf(c) >= 0;
            boolean listNumber = i == digits && digits > at && (c == '.' || c == ')');
            if (ALWAYS.indexOf(c) >= 0 || first || listNumber) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    /** The text with its line ends as {@code \n}. */
    static String lines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** A value printed on one line: its line ends as spaces. */
    static String oneLine(String text) {
        return lines(text).replace('\n', ' ');
    }
}

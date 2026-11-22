package io.github.gmshield.conversations.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An answer's Markdown made plain text (Story 3.12: a saved answer's excerpt). A small strip, not a parser: code fence
 * lines go (the code between them stays), and so do horizontal rules and table separator lines; heading marks, quote
 * marks and list marks at a line's start go; a link or an image becomes its text; inline code loses its backticks; and
 * emphasis marks ({@code *}, {@code _}, {@code ~}) at a word's edge go, so a mark inside a word ({@code snake_case})
 * stays; a table's cell bars become spaces. Lines are joined by one space. Citation markers are events, never text,
 * so none is here.
 */
final class Markdown {

    /** A link or an image: {@code [text](target)} or {@code ![text](target)}; the text is kept. */
    private static final Pattern LINK = Pattern.compile("!?\\[([^\\]\\n]*)]\\([^)\\n]*\\)");
    /** Emphasis marks at a word's edge: not between two letters or digits. */
    private static final Pattern EMPHASIS = Pattern.compile("(?<![\\p{L}\\p{N}])[*_~]+|[*_~]+(?![\\p{L}\\p{N}])");
    /** A heading's marks and the space after them. */
    private static final Pattern HEADING = Pattern.compile("^#{1,6}(?:[ \\t]|$)");
    /** A list item's mark and the space after it: {@code -}, {@code *}, {@code +}, or a number and a dot or bracket. */
    private static final Pattern LIST_MARK = Pattern.compile("^(?:[-*+]|\\d{1,9}[.)])[ \\t]+");

    private Markdown() {
    }

    /** {@code markdown} as plain text, as the class says. */
    static String plain(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        boolean code = false;
        for (String raw : markdown.split("\n", -1)) {
            String line = raw.strip();
            if (line.startsWith("```") || line.startsWith("~~~")) {
                code = !code;
                continue;
            }
            if (code) {
                lines.add(line);
                continue;
            }
            if (rule(line)) {
                continue;
            }
            line = blockMarks(line);
            if (!line.isEmpty() && !rule(line)) {
                lines.add(inline(line));
            }
        }
        return String.join(" ", lines).strip();
    }

    /** A line without its quote marks, heading marks and list mark. */
    private static String blockMarks(String line) {
        String rest = line;
        while (rest.startsWith(">")) {
            rest = rest.substring(1).stripLeading();
        }
        rest = HEADING.matcher(rest).replaceFirst("");
        return LIST_MARK.matcher(rest).replaceFirst("").strip();
    }

    /** Whether the line is a horizontal rule ({@code ---}, {@code ***}, {@code ___}) or a table's separator line. */
    private static boolean rule(String line) {
        int marks = 0;
        for (int index = 0; index < line.length(); index++) {
            char at = line.charAt(index);
            if (at == '-' || at == '*' || at == '_') {
                marks++;
            } else if (at != ' ' && at != '\t' && at != '|' && at != ':') {
                return false;
            }
        }
        return marks >= 3;
    }

    /** A line's inline marks removed: links and images become their text, then backticks and emphasis marks go. */
    private static String inline(String line) {
        String text = LINK.matcher(line).replaceAll(match -> Matcher.quoteReplacement(match.group(1)));
        text = text.replace("`", "").replace('|', ' ');
        return EMPHASIS.matcher(text).replaceAll("").strip();
    }
}

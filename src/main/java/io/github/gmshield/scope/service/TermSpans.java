package io.github.gmshield.scope.service;

import io.github.gmshield.library.service.Library;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The spans of a question that may name a term (Story 4.6): every run of 1 to {@value #MAX_WORDS} contiguous words, a
 * word being a run of characters between white space (the question first put in NFC, so a final accent written as a
 * combining mark stays with its letter), the words joined by one space and put in their unique form
 * ({@link Library#uniqueForm}). Each span is taken as written, without the punctuation at its end, without the
 * punctuation at its start, and without both ("Fireball?" also gives "fireball", "(PV)" gives "pv", "+1 Sword?" gives
 * "+1 sword"), so a name with punctuation inside ("Bigby's Hand") or at an end ("+1 Sword") still matches. Words are
 * split by a loop over the code points, never a pattern; a span longer than {@value #MAX_SPAN} code points (no stored
 * name is), or one with no letter or digit, is left out. Distinct, in the order of the question, at most {@value #MAX_SPANS}: a long question is not
 * matched past them, so its lookups stay within a few queries.
 */
final class TermSpans {

    /** The most words one span holds. */
    static final int MAX_WORDS = 6;
    /** The longest span, in code points: no record, type or term name is longer. */
    static final int MAX_SPAN = 200;
    /** The most spans one question gives, in its order. */
    static final int MAX_SPANS = 1_000;

    private TermSpans() {
    }

    /** The spans of {@code text}, as the class says; none for a text without a word. */
    static List<String> of(String text) {
        List<String> words = words(Normalizer.normalize(text, Normalizer.Form.NFC));
        Set<String> spans = new LinkedHashSet<>();
        for (int first = 0; first < words.size() && spans.size() < MAX_SPANS; first++) {
            StringBuilder span = new StringBuilder();
            for (int last = first; last < Math.min(words.size(), first + MAX_WORDS); last++) {
                if (last > first) {
                    span.append(' ');
                }
                span.append(words.get(last));
                String written = span.toString();
                if (written.codePointCount(0, written.length()) > MAX_SPAN) {
                    break;
                }
                add(spans, written);
                add(spans, trimmed(written, false, true));
                add(spans, trimmed(written, true, false));
                add(spans, trimmed(written, true, true));
            }
        }
        return spans.stream().limit(MAX_SPANS).toList();
    }

    /** Adds the span's unique form, unless it holds no letter or digit (a span of punctuation names nothing). */
    private static void add(Set<String> spans, String span) {
        if (span.codePoints().anyMatch(Character::isLetterOrDigit)) {
            spans.add(Library.uniqueForm(span));
        }
    }

    /** The runs of characters between white space, in order. */
    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        int at = 0;
        while (at < text.length()) {
            int codePoint = text.codePointAt(at);
            if (blank(codePoint)) {
                at += Character.charCount(codePoint);
                continue;
            }
            int start = at;
            while (at < text.length() && !blank(text.codePointAt(at))) {
                at += Character.charCount(text.codePointAt(at));
            }
            words.add(text.substring(start, at));
        }
        return words;
    }

    /**
     * The span without the code points that are neither letters nor digits at its start ({@code start}) and at its end
     * ({@code end}).
     */
    private static String trimmed(String span, boolean start, boolean end) {
        int from = 0;
        int to = span.length();
        while (start && from < to && !Character.isLetterOrDigit(span.codePointAt(from))) {
            from += Character.charCount(span.codePointAt(from));
        }
        while (end && to > from && !Character.isLetterOrDigit(span.codePointBefore(to))) {
            to -= Character.charCount(span.codePointBefore(to));
        }
        return span.substring(from, to);
    }

    private static boolean blank(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}

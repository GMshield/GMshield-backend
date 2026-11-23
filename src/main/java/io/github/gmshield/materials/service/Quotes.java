package io.github.gmshield.materials.service;

import java.text.Normalizer;

/**
 * How a proposal's quote is compared with the passage it cites (Story 4.4): both in NFC with every run of whitespace
 * collapsed to one space and none at either end, so a quote found across a line break still matches. One pass over
 * the text, no pattern.
 */
final class Quotes {

    /** U+0085, which Java does not count as whitespace and the worker's {@code str.split()} does. */
    private static final int NEXT_LINE = 0x85;

    private Quotes() {
    }

    /** {@code text} in NFC, each run of whitespace one space, trimmed. */
    static String collapsed(String text) {
        String normal = Normalizer.normalize(text, Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder(normal.length());
        boolean space = false;
        int index = 0;
        while (index < normal.length()) {
            int point = normal.codePointAt(index);
            index += Character.charCount(point);
            if (Character.isWhitespace(point) || Character.isSpaceChar(point) || point == NEXT_LINE) {
                space = !out.isEmpty();
                continue;
            }
            if (space) {
                out.append(' ');
                space = false;
            }
            out.appendCodePoint(point);
        }
        return out.toString();
    }
}

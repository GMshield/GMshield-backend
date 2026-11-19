package io.github.gmshield.scope.service;

import java.util.List;
import java.util.UUID;

/**
 * One page of a cited source (Story 2.7): its number and its version's page total, its picture's size (both null for a
 * page with no picture: a text page, or an unreadable page with no view), its text lines (a picture page) or its text
 * (a text page), its passages' places in reading order, and its outcome ({@code read_directly}, {@code read_by_ocr},
 * {@code unreadable}, or null). {@code toString} names numbers only.
 */
public record SourcePage(int page, int pageTotal, Integer pictureWidth, Integer pictureHeight, List<Line> lines,
                         String text, List<Place> passages, String outcome) {

    public SourcePage {
        lines = List.copyOf(lines);
        passages = List.copyOf(passages);
    }

    /** A text line and its box, {@code [left, top, right, bottom]} as fractions of the picture. */
    public record Line(String text, List<Double> box) {
        @Override
        public String toString() {
            return "Line[box=" + box + "]";
        }
    }

    /** A passage's place: its boxes (a picture page; null otherwise), or its code-point range (a text page). */
    public record Place(UUID id, List<List<Double>> boxes, List<Integer> range) {
    }

    @Override
    public String toString() {
        return "SourcePage[page=" + page + ", pageTotal=" + pageTotal + "]";
    }
}

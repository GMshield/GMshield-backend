package io.github.gmshield.artifacts.service;

import java.util.List;

/**
 * One artifact as an export prints it (Story 5.7): its name, its record type's name, its fields in the order written,
 * its free content (null for none), and its source references as numbered lines ({@code [1] ...}, each already worded
 * in the export's language, a removed one marked). Every value is the artifact's text, printed as text: nothing here is
 * ever interpreted. {@code toString} names counts only.
 */
record ExportSheet(String name, String type, List<Artifacts.Field> fields, String content, List<Source> sources) {

    ExportSheet {
        fields = List.copyOf(fields);
        sources = List.copyOf(sources);
    }

    /** A numbered source reference: its number and its line without the number. Names nothing. */
    record Source(int number, String text) {
        @Override
        public String toString() {
            return "Source[number=" + number + "]";
        }
    }

    @Override
    public String toString() {
        return "ExportSheet[fields=" + fields.size() + ", content=" + (content != null) + ", sources=" + sources.size()
                + "]";
    }
}

package io.github.gmshield.artifacts.service;

import io.github.gmshield.library.service.Language;

/**
 * The words an export adds around the artifacts' own text (Story 5.7), in the export's language: the sources' title,
 * the mark of a removed source, the word before an artifact reference's version and before a page, the file name of
 * several artifacts, and the file name of one whose name leaves nothing safe.
 */
record ExportLabels(String sources, String removed, String version, String page, String several, String one) {

    static final ExportLabels PT_BR = new ExportLabels("Fontes", "fonte removida", "versão", "p.", "artefatos",
            "artefato");
    static final ExportLabels EN = new ExportLabels("Sources", "source removed", "version", "p.", "artifacts",
            "artifact");

    static ExportLabels of(Language language) {
        return language == Language.EN ? EN : PT_BR;
    }
}

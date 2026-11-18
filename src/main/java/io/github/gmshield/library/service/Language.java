package io.github.gmshield.library.service;

import java.util.Arrays;
import java.util.Optional;

/** The languages of the interface, by their tag: a first sign-in's starter library is named in one of them. */
public enum Language {

    PT_BR("pt-BR"),
    EN("en");

    /** When the frontend sends none. */
    public static final Language DEFAULT = PT_BR;

    private final String tag;

    Language(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }

    /** The language with exactly this tag, if there is one. */
    public static Optional<Language> fromTag(String tag) {
        return Arrays.stream(values()).filter(language -> language.tag.equals(tag)).findFirst();
    }
}

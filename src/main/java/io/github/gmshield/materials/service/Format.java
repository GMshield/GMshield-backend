package io.github.gmshield.materials.service;

import java.util.List;
import java.util.Locale;

/** The file formats a material may have (FR-6), by the code the API and the tables use. */
public enum Format {
    PDF("pdf"),
    TXT("txt"),
    MARKDOWN("markdown"),
    JPEG("jpeg"),
    PNG("png"),
    WEBP("webp"),
    TIFF("tiff"),
    BMP("bmp"),
    HEIC("heic");

    /** Every accepted format's code, in the order the refusal names them. */
    public static final List<String> CODES = java.util.Arrays.stream(values()).map(Format::code).toList();

    private final String code;

    Format(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** A text file's format: Markdown when its name ends in {@code .md} or {@code .markdown}, else plain text. */
    static Format textNamed(String name) {
        String lower = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown") ? MARKDOWN : TXT;
    }
}

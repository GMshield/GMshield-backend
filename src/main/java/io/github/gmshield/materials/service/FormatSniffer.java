package io.github.gmshield.materials.service;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Decides a file's format from its first bytes alone (Story 1.13): the signature of a PDF, JPEG, PNG, WebP, TIFF or
 * HEIC, then text, then BMP. Text is valid UTF-8 (a byte order mark is allowed, and a sequence cut at the end of the
 * sample is tolerated when the file goes on), with no NUL and no control character other than tab, line feed,
 * carriage return and form feed. The file's name only tells Markdown from plain text: it never makes binary content
 * acceptable. BMP comes after text because its signature, {@code BM}, may also begin a text; a real BMP header holds
 * NUL bytes, so it is never text.
 */
final class FormatSniffer {

    /** How much of the first part is read before anything is written. */
    static final int SAMPLE_BYTES = 4096;
    private static final byte[] PDF = ascii("%PDF-");
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] TIFF_LITTLE = {'I', 'I', '*', 0};
    private static final byte[] TIFF_BIG = {'M', 'M', 0, '*'};
    private static final java.util.Set<String> HEIC_BRANDS =
            java.util.Set.of("heic", "heix", "mif1", "msf1", "heim", "heis", "hevc");

    private FormatSniffer() {
    }

    /**
     * The format of a file whose first {@code length} bytes are {@code sample}; {@code more} says whether the file
     * goes on after them. Empty when the content is none of the accepted formats.
     */
    static Optional<Format> sniff(byte[] sample, int length, boolean more, String name) {
        if (startsWith(sample, length, PDF)) {
            return Optional.of(Format.PDF);
        }
        if (startsWith(sample, length, JPEG)) {
            return Optional.of(Format.JPEG);
        }
        if (startsWith(sample, length, PNG)) {
            return Optional.of(Format.PNG);
        }
        if (length >= 12 && startsWith(sample, length, ascii("RIFF")) && region(sample, 8, 4).equals("WEBP")) {
            return Optional.of(Format.WEBP);
        }
        if (startsWith(sample, length, TIFF_LITTLE) || startsWith(sample, length, TIFF_BIG)) {
            return Optional.of(Format.TIFF);
        }
        if (length >= 12 && region(sample, 4, 4).equals("ftyp") && HEIC_BRANDS.contains(region(sample, 8, 4))) {
            return Optional.of(Format.HEIC);
        }
        if (isText(sample, length, more)) {
            return Optional.of(Format.textNamed(name));
        }
        if (startsWith(sample, length, ascii("BM"))) {
            return Optional.of(Format.BMP);
        }
        return Optional.empty();
    }

    /** Valid UTF-8 without NUL or a control character other than tab, LF, CR and FF. */
    static boolean isText(byte[] sample, int length, boolean more) {
        if (length == 0) {
            return false;
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(sample, 0, length);
        CharBuffer out = CharBuffer.allocate(length + 1);
        CoderResult result = decoder.decode(in, out, !more);
        if (result.isError()) {
            return false;
        }
        if (in.hasRemaining() && (!more || in.remaining() >= 4)) {
            // Bytes left over are a sequence cut at the edge: only tolerated when the file goes on after the sample.
            return false;
        }
        if (!more) {
            try {
                if (decoder.flush(out).isError()) {
                    return false;
                }
            } catch (IllegalStateException alreadyFlushed) {
                return false;
            }
        }
        out.flip();
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (i == 0 && c == '﻿') {
                continue;
            }
            if (control(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean control(char c) {
        if (c == '\t' || c == '\n' || c == '\r' || c == '\f') {
            return false;
        }
        return c < 0x20 || c == 0x7F || (c >= 0x80 && c <= 0x9F);
    }

    private static boolean startsWith(byte[] sample, int length, byte[] prefix) {
        if (length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (sample[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String region(byte[] sample, int from, int count) {
        return new String(sample, from, count, StandardCharsets.ISO_8859_1);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}

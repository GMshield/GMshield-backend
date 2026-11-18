package io.github.gmshield.materials.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The format of a file from its first bytes alone (Story 1.13): every accepted format, and the refusals. */
class FormatSnifferTest {

    @Test
    void everyAcceptedSignatureIsRecognisedWhateverTheName() {
        assertThat(sniff(bytes("%PDF-1.7\n%âã\n"), "notes.txt")).contains(Format.PDF);
        assertThat(sniff(concat(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}, zeros(16)), "x.md"))
                .contains(Format.JPEG);
        assertThat(sniff(concat(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}, zeros(16)), "x"))
                .contains(Format.PNG);
        assertThat(sniff(concat(bytes("RIFF"), new byte[] {0x24, 0, 0, 0}, bytes("WEBPVP8 "), zeros(8)), "a.webp"))
                .contains(Format.WEBP);
        assertThat(sniff(concat(new byte[] {'I', 'I', '*', 0}, zeros(8)), "scan.tif")).contains(Format.TIFF);
        assertThat(sniff(concat(new byte[] {'M', 'M', 0, '*'}, zeros(8)), "scan.tif")).contains(Format.TIFF);
        assertThat(sniff(concat(new byte[] {'B', 'M', 0x36, 0, 0, 0, 0, 0, 0, 0}, zeros(8)), "a.bmp")).contains(Format.BMP);
        for (String brand : new String[] {"heic", "heix", "mif1", "msf1", "heim", "heis", "hevc"}) {
            assertThat(sniff(concat(new byte[] {0, 0, 0, 0x18}, bytes("ftyp" + brand), zeros(12)), "photo.heic"))
                    .as(brand).contains(Format.HEIC);
        }
    }

    @Test
    void textIsMarkdownByItsNameAndPlainTextOtherwise() {
        byte[] text = bytes("# Rules\n\nRoll 2d6 and add your\tbonus.\r\n\f");
        assertThat(sniff(text, "rules.md")).contains(Format.MARKDOWN);
        assertThat(sniff(text, "RULES.MARKDOWN ")).contains(Format.MARKDOWN);
        assertThat(sniff(text, "rules.txt")).contains(Format.TXT);
        assertThat(sniff(text, "rules")).contains(Format.TXT);
        assertThat(sniff(text, "rules.md.txt")).contains(Format.TXT);
        // A byte order mark, accents and characters outside the BMP are text.
        assertThat(sniff(concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, bytes("Ação: 🎲 rolar")), "a.md"))
                .contains(Format.MARKDOWN);
        // A text that happens to start with BM is text, not a bitmap.
        assertThat(sniff(bytes("BMX rules, second edition\n"), "bmx.txt")).contains(Format.TXT);
    }

    @Test
    void aSequenceCutAtTheEdgeOfTheSampleIsToleratedOnlyWhenTheFileGoesOn() {
        byte[] cut = Arrays.copyOf(bytes("Dano: ação"), bytes("Dano: a").length + 1); // the first byte of "ç"
        assertThat(FormatSniffer.sniff(cut, cut.length, true, "a.txt")).contains(Format.TXT);
        assertThat(FormatSniffer.sniff(cut, cut.length, false, "a.txt")).isEmpty();
        byte[] sample = new byte[FormatSniffer.SAMPLE_BYTES];
        Arrays.fill(sample, (byte) 'a');
        sample[sample.length - 2] = (byte) 0xF0; // a four-byte sequence, two bytes of it in the sample
        sample[sample.length - 1] = (byte) 0x9F;
        assertThat(FormatSniffer.sniff(sample, sample.length, true, "a.txt")).contains(Format.TXT);
    }

    @Test
    void binaryContentIsRefusedEvenWithATextName() {
        assertThat(sniff(concat(new byte[] {'P', 'K', 3, 4}, bytes("[Content_Types].xml")), "rules.md")).isEmpty();
        assertThat(sniff(concat(new byte[] {'P', 'K', 3, 4}, zeros(30)), "rules.pdf")).isEmpty();
        assertThat(sniff(bytes("MZ\u0090\u0000"), "x.md")).isEmpty();                          // an executable
        assertThat(sniff(concat(bytes("text then"), new byte[] {0}, bytes("NUL")), "x.md")).isEmpty();
        assertThat(sniff(bytes("escape \u001b[31m red"), "x.txt")).isEmpty();
        assertThat(sniff(bytes("delete \u007f"), "x.txt")).isEmpty();
        assertThat(sniff(new byte[] {'a', (byte) 0xC3, 0x28}, "x.txt")).isEmpty();             // invalid UTF-8
        assertThat(sniff(new byte[] {'a', (byte) 0xC0, (byte) 0x80}, "x.txt")).isEmpty();      // overlong NUL
        assertThat(sniff(new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80}, "x.txt")).isEmpty(); // a surrogate
        assertThat(sniff(bytes("C1 control \u0085 here"), "x.txt")).isEmpty();
        // A part of a signature is not the signature.
        assertThat(sniff(concat(bytes("RIFF"), zeros(4), bytes("WAVE")), "a.webp")).isEmpty();
        assertThat(sniff(concat(new byte[] {0, 0, 0, 0x18}, bytes("ftypavif"), zeros(12)), "a.heic")).isEmpty();
        assertThat(sniff(new byte[0], "a.txt")).isEmpty();
    }

    private static Optional<Format> sniff(byte[] content, String name) {
        return FormatSniffer.sniff(content, content.length, false, name);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zeros(int count) {
        return new byte[count];
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}

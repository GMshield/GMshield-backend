package io.github.gmshield.vault.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** The vault's incremental SHA-256 (Story 1.13): the same digest however the content is split. */
class Sha256Test {

    private final Vault vault = TestVaults.vault();

    @Test
    void theDigestOfPartsIsTheDigestOfTheWhole() {
        byte[] content = "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.US_ASCII);
        Sha256 whole = vault.sha256();
        whole.update(content, 0, content.length);
        Sha256 split = vault.sha256();
        split.update(content, 0, 4);
        split.update(content, 4, 0);
        split.update(content, 4, content.length - 4);

        byte[] expected = HexFormat.of().parseHex("d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592");
        assertThat(whole.digest()).isEqualTo(expected);
        assertThat(split.digest()).isEqualTo(expected);
        assertThat(split.matches(expected)).isTrue();
        expected[31] ^= 1;
        assertThat(split.matches(expected)).isFalse();
        assertThat(vault.sha256().matches(new byte[0])).isFalse();
    }

    @Test
    void nothingIsTheDigestOfEmptyContentAndAFinalDigestTakesNoMore() {
        Sha256 empty = vault.sha256();
        assertThat(HexFormat.of().formatHex(empty.digest()))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        // The digest is final, and handed out as a copy.
        empty.digest()[0] = 0;
        assertThat(empty.digest()[0]).isEqualTo((byte) 0xe3);
        assertThatThrownBy(() -> empty.update(new byte[1], 0, 1)).isInstanceOf(IllegalStateException.class);
    }
}

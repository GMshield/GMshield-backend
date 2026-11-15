package io.github.gmshield.vault.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The stored rows compare their bytes by content and never show them in text (SonarQube S6218). */
class StoredRowsTest {

    private static final UUID OWNER = UUID.fromString("0190a0b0-0000-7000-8000-000000000001");
    private static final UUID ROW = UUID.fromString("0190a0b0-0000-7000-8000-000000000002");
    private static final UUID FILE = UUID.fromString("0190a0b0-0000-7000-8000-000000000003");

    @Test
    void wrappedDataKeysWithEqualBytesAreEqualAndTheirTextHidesTheBytes() {
        WrappedDataKey key = new WrappedDataKey(OWNER, 1, new byte[] {1, 2, (byte) 0xab});
        WrappedDataKey same = new WrappedDataKey(OWNER, 1, new byte[] {1, 2, (byte) 0xab});

        assertThat(key).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(key).isNotEqualTo(new WrappedDataKey(OWNER, 1, new byte[] {1, 2, 3}));
        assertThat(key).isNotEqualTo(new WrappedDataKey(OWNER, 2, new byte[] {1, 2, (byte) 0xab}));
        assertThat(key.toString()).contains("3 bytes").doesNotContain("ab").doesNotContain("[B@");
    }

    @Test
    void probeRowsWithEqualBytesAreEqualAndTheirTextHidesTheBytes() {
        byte[] text = HexFormat.of().parseHex("c0ffee");
        ProbeRow row = new ProbeRow(ROW, OWNER, text, new byte[] {9, 9}, FILE);
        ProbeRow same = new ProbeRow(ROW, OWNER, text.clone(), new byte[] {9, 9}, FILE);

        assertThat(row).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(row).isNotEqualTo(new ProbeRow(ROW, OWNER, new byte[] {0}, new byte[] {9, 9}, FILE));
        assertThat(row.toString()).contains("text=3 bytes", "textFingerprint=2 bytes")
                .doesNotContain("c0ffee").doesNotContain("[B@");
    }
}

package io.github.gmshield.library.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The library's stored rows compare their bytes by content and never show them in text (SonarQube S6218). */
class StoredRowsTest {

    private static final UUID OWNER = UUID.fromString("0190a0b0-0000-7000-8000-000000000001");
    private static final UUID ROW = UUID.fromString("0190a0b0-0000-7000-8000-000000000002");
    private static final UUID SYSTEM = UUID.fromString("0190a0b0-0000-7000-8000-000000000003");
    private static final Instant AT = Instant.parse("2026-11-16T12:00:00Z");

    @Test
    void systemRowsWithEqualBytesAreEqualAndTheirTextHidesTheBytes() {
        SystemRow row = new SystemRow(ROW, OWNER, new byte[] {(byte) 0xc0, (byte) 0xff, (byte) 0xee}, new byte[] {9, 9}, AT);
        SystemRow same = new SystemRow(ROW, OWNER, new byte[] {(byte) 0xc0, (byte) 0xff, (byte) 0xee}, new byte[] {9, 9}, AT);

        assertThat(row).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(row).isNotEqualTo(new SystemRow(ROW, OWNER, new byte[] {0}, new byte[] {9, 9}, AT));
        assertThat(row).isNotEqualTo(new SystemRow(ROW, SYSTEM, row.name(), row.nameFingerprint(), AT));
        assertThat(row.toString()).contains("name=3 bytes", "nameFingerprint=2 bytes")
                .doesNotContain("c0ffee").doesNotContain("[B@");
    }

    @Test
    void campaignRowsWithEqualBytesAreEqualAndTheirTextHidesTheBytes() {
        CampaignRow row = new CampaignRow(ROW, OWNER, SYSTEM, new byte[] {(byte) 0xc0, (byte) 0xff, (byte) 0xee}, AT);
        CampaignRow same = new CampaignRow(ROW, OWNER, SYSTEM, new byte[] {(byte) 0xc0, (byte) 0xff, (byte) 0xee}, AT);

        assertThat(row).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(row).isNotEqualTo(new CampaignRow(ROW, OWNER, SYSTEM, new byte[] {1}, AT));
        assertThat(row).isNotEqualTo(new CampaignRow(ROW, OWNER, OWNER, row.name(), AT));
        assertThat(row.toString()).contains("name=3 bytes").contains(SYSTEM.toString())
                .doesNotContain("c0ffee").doesNotContain("[B@");
        // Story 3.2: the fingerprint, the archive, the choice and the version count too.
        CampaignRow full = new CampaignRow(ROW, OWNER, SYSTEM, new byte[] {1}, new byte[] {(byte) 0xc0, (byte) 0xde}, AT,
                null, 2, AT);
        assertThat(full).isEqualTo(new CampaignRow(ROW, OWNER, SYSTEM, new byte[] {1}, new byte[] {(byte) 0xc0, (byte) 0xde},
                AT, null, 2, AT));
        assertThat(full).isNotEqualTo(new CampaignRow(ROW, OWNER, SYSTEM, new byte[] {1}, new byte[] {9}, AT, null, 2, AT));
        assertThat(full).isNotEqualTo(new CampaignRow(ROW, OWNER, SYSTEM, new byte[] {1}, full.nameFingerprint(), null,
                null, 2, AT));
        assertThat(full).isNotEqualTo(new CampaignRow(ROW, OWNER, SYSTEM, new byte[] {1}, full.nameFingerprint(), AT, AT,
                2, AT));
        assertThat(full).isNotEqualTo(new CampaignRow(ROW, OWNER, SYSTEM, new byte[] {1}, full.nameFingerprint(), AT,
                null, 3, AT));
        assertThat(full.archived()).isTrue();
        assertThat(row.archived()).isFalse();
        assertThat(full.toString()).contains("nameFingerprint=2 bytes", "archived=true", "version=2")
                .doesNotContain("c0de").doesNotContain("[B@");
        CampaignChange change = new CampaignChange(ROW, 2, new byte[] {(byte) 0xc0, (byte) 0xff, (byte) 0xee}, new byte[] {7},
                true, AT);
        assertThat(change).isEqualTo(new CampaignChange(ROW, 2, new byte[] {(byte) 0xc0, (byte) 0xff, (byte) 0xee},
                new byte[] {7}, true, AT)).hasSameHashCodeAs(new CampaignChange(ROW, 2,
                new byte[] {(byte) 0xc0, (byte) 0xff, (byte) 0xee}, new byte[] {7}, true, AT));
        assertThat(change).isNotEqualTo(new CampaignChange(ROW, 2, change.name(), new byte[] {7}, false, AT));
        assertThat(change.toString()).contains("name=3 bytes", "nameFingerprint=1 bytes", "archived=true").doesNotContain("c0ffee")
                .doesNotContain("[B@");
    }
}

package io.github.gmshield.jobs.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A job row compares its grant hash by content and never shows it in text (SonarQube S6218). */
class JobRowTest {

    private static final UUID ID = UUID.fromString("0190a0b0-0000-7000-8000-000000000001");
    private static final UUID OWNER = UUID.fromString("0190a0b0-0000-7000-8000-000000000002");
    private static final UUID CLAIM = UUID.fromString("0190a0b0-0000-7000-8000-000000000003");
    private static final Instant AT = Instant.parse("2026-11-16T12:00:00Z");

    private static JobRow row(byte[] grantHash) {
        return new JobRow(ID, OWNER, "probe", "interactive", "running", 1, "0af7651916cd43dd8448eb211c80319c", AT,
                CLAIM, grantHash, AT, AT, null, AT, AT, null, null, null);
    }

    @Test
    void rowsWithEqualGrantHashesAreEqualAndTheirTextHidesTheHash() {
        JobRow row = row(new byte[] {(byte) 0xc0, (byte) 0xff, (byte) 0xee});

        assertThat(row).isEqualTo(row(new byte[] {(byte) 0xc0, (byte) 0xff, (byte) 0xee}))
                .hasSameHashCodeAs(row(new byte[] {(byte) 0xc0, (byte) 0xff, (byte) 0xee}));
        assertThat(row).isNotEqualTo(row(new byte[] {0})).isNotEqualTo(row(null));
        assertThat(row(null)).isEqualTo(row(null));
        assertThat(row.toString()).doesNotContain("c0ffee").doesNotContain("[B@");
    }

    @Test
    void theModelOfTheLastClaimStaysThroughRenewalsReleasesAndOffersAndCountsForEquality() {
        JobRow row = row(null).withModel("azure-openai", "gpt-6.1-terra", "full");

        for (JobRow copy : java.util.List.of(row.renewed(AT, AT, AT), row.released("done", null, AT), row.offered(AT),
                row.withState("waiting", AT), row.claimed(CLAIM, null, AT, AT, AT))) {
            assertThat(java.util.List.of(copy.modelProvider(), copy.modelId(), copy.modelTier()))
                    .containsExactly("azure-openai", "gpt-6.1-terra", "full");
        }
        assertThat(row).isNotEqualTo(row(null)).isEqualTo(row(null).withModel("azure-openai", "gpt-6.1-terra", "full"));
        assertThat(row(null).modelProvider()).isNull();
    }
}

package io.github.gmshield.shared.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Every id the backend mints is a UUID version 7 (RFC 9562). */
class IdsTest {

    @Test
    void versionSevenVariantTwoWithTheCurrentMillisecond() {
        long before = System.currentTimeMillis();
        UUID id = Ids.newId();
        long after = System.currentTimeMillis();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        long millis = id.getMostSignificantBits() >>> 16;
        assertThat(millis).isBetween(before, after);
    }

    @Test
    void idsFromLaterMillisecondsSortAfterEarlierOnes() throws InterruptedException {
        UUID earlier = Ids.newId();
        long started = System.currentTimeMillis();
        while (System.currentTimeMillis() <= started + 1) {
            Thread.onSpinWait();
        }
        UUID later = Ids.newId();

        assertThat(later.toString()).isGreaterThan(earlier.toString());
        assertThat(Long.compareUnsigned(later.getMostSignificantBits(), earlier.getMostSignificantBits())).isPositive();
        assertThat(Ids.newId()).isNotEqualTo(Ids.newId());
    }
}

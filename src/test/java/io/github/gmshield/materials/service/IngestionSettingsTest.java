package io.github.gmshield.materials.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The step cap's setting (Story 1.15): steps-per-page x pages + 1, and a start refused outside 1 to 100. */
class IngestionSettingsTest {

    @Test
    void theCapIsStepsPerPageTimesThePagesPlusOne() {
        IngestionSettings settings = new IngestionSettings(4);
        assertThat(settings.stepCap(1)).isEqualTo(5);
        assertThat(settings.stepCap(320)).isEqualTo(1281);
        assertThat(settings.stepCap(0)).isEqualTo(1);
        assertThat(new IngestionSettings(100).stepCap(1_000_000)).isEqualTo(100_000_001L);
    }

    @Test
    void aValueOutsideOneToAHundredStopsTheStartNamingOnlyTheSetting() {
        for (int bad : new int[] {0, -1, 101}) {
            assertThatThrownBy(() -> new IngestionSettings(bad))
                    .hasMessage("setting.invalid: GMSHIELD_INGESTION_STEPS_PER_PAGE");
        }
    }
}

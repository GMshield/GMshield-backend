package io.github.gmshield.vault.service;

import java.util.UUID;

/**
 * Test mode only: the NFR-1 gate's known text was stored by the probe. Other modules store it through their
 * own real paths too (Story 1.9: an account), synchronously, so a failure fails the seed. Its
 * {@code toString} never shows the text.
 */
public record Nfr1ProbeSeeded(UUID probeId, String text) {

    @Override
    public String toString() {
        return "Nfr1ProbeSeeded[probeId=" + probeId + "]";
    }
}

package io.github.gmshield.materials.data;

/**
 * A material with its current version, whose state is the material's (Story 1.14; from Story 3.4 the current version
 * is the newest one that is not discarded, or the newest one when every version is discarded).
 */
public record CurrentVersion(MaterialRow material, VersionRow version) {
}

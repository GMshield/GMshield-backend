package io.github.gmshield.materials.data;

/** A material with its newest version, whose state is the material's (Story 1.14). */
public record NewestVersion(MaterialRow material, VersionRow version) {
}

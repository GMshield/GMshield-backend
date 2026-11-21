package io.github.gmshield.materials.data;

/**
 * A change of where a material belongs (Story 3.3) named a version the material has left: someone changed it
 * elsewhere. Nothing is written; the write's transaction cannot commit after it. Its message names no value.
 */
public class StaleMaterialVersion extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StaleMaterialVersion() {
        super("material.stale_version");
    }
}

package io.github.gmshield.jobs.service;

/**
 * The worker stated, at a claim, an index recipe other than the last one this backend saw (Story 2.1, AD-10): the
 * first one stated after a start, or a new one. Published after that claim ended, outside any transaction, for the
 * module that keeps the index's sources to reconcile them; this module depends on no feature module.
 */
public record IndexRecipeStated(String recipe) {
}

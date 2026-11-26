/**
 * Artifacts (Story 5.1 onward): what the GM writes and keeps in a campaign. An artifact is a record of
 * its campaign: its identity and its record type are the {@code knowledge} module's ({@code knowledge.service.
 * ArtifactRecords}, a row of {@code record} with the campaign instead of a material version), and its content (name,
 * fields and an optional free text) is this module's, as numbered versions in {@code record_version}, encrypted through
 * {@code vault}. Story 5.1 part A writes version 1 by hand ({@code service.Artifacts}) and reads a campaign's artifacts
 * and one artifact through the public API ({@code web.ArtifactController}); a campaign's or a system's deletion takes
 * its artifacts ({@code service.ArtifactsDeletion}, a {@code library.service.SystemDeletionHandler}). An artifact is
 * private to its owner: another owner's, another campaign's or an unknown one is "not found". Indexing, citations, edits
 * and later versions come with later stories (part B adds {@code jobs} for the index jobs).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Artifacts",
        allowedDependencies = {"shared :: service", "vault :: service", "library :: service", "knowledge :: service"})
package io.github.gmshield.artifacts;

/**
 * Artifacts (Story 5.1 onward): what the GM writes and keeps in a campaign. An artifact is a record of
 * its campaign: its identity and its record type are the {@code knowledge} module's ({@code knowledge.service.
 * ArtifactRecords}, a row of {@code record} with the campaign instead of a material version), and its content (name,
 * fields and an optional free text) is this module's, as numbered versions in {@code record_version}, encrypted through
 * {@code vault}. Story 5.1 part A writes version 1 by hand ({@code service.Artifacts}) and reads a campaign's artifacts
 * and one artifact through the public API ({@code web.ArtifactController}); a campaign's or a system's deletion takes
 * its artifacts ({@code service.ArtifactsDeletion}, a {@code library.service.SystemDeletionHandler}). An artifact is
 * private to its owner: another owner's, another campaign's or an unknown one is "not found". Story 5.1 part B indexes
 * each version through a job ({@code jobs}: {@code index.artifact}, and {@code index.artifact_removal} when its campaign
 * goes) and implements the {@code scope} module's {@code CampaignArtifacts} port, so a campaign's artifacts are
 * campaign-tier sources of its questions. Story 5.2 part A edits an artifact as its next version, keeps every earlier
 * version, restores one as the newest and opens a cited one by its number. Story 5.4 part A saves a finished draft as an
 * artifact ({@code web.DraftSaveController}, {@code service.Artifacts#saveDraft}): it reads the draft and marks the
 * turn saved through {@code conversations :: service}, in the transaction that creates the artifact; nothing in
 * {@code conversations} depends on this module. Story 5.4 part B implements that module's {@code ConversationArtifacts}
 * port ({@code service.SavedArtifacts}): a conversation's deletion lists the artifacts saved from it and takes the
 * ticked ones through {@code service.Artifacts#delete}; an artifact names its conversation only while it is ready.
 * Story 5.5 part A marks an artifact as overriding one system record of its campaign through
 * {@code knowledge.service.RecordRelations}, which owns that relation; every artifact answer carries its mark. Part B
 * relates an artifact to records of its campaign's scope and to the campaign's other artifacts the same way; every
 * artifact answer carries its relations. Story 5.6 part A shares an artifact with the owner's other campaigns of its
 * system ({@code artifact_share}, this module's): it stays one artifact, a source of their system tier through the
 * {@code CampaignArtifacts} port, readable there read-only.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Artifacts",
        allowedDependencies = {"shared :: service", "vault :: service", "library :: service", "knowledge :: service",
                "jobs :: service", "scope :: service", "conversations :: service"})
package io.github.gmshield.artifacts;

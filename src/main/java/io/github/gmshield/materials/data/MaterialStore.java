package io.github.gmshield.materials.data;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code material} and {@code material_version}, always within the owner of the work: every read sees that owner's
 * rows only, and a write for another owner is refused. Writes join the caller's transaction, which must exist.
 */
public interface MaterialStore {

    void insertMaterial(MaterialRow row);

    void insertVersion(VersionRow row);

    /** Says that a material belongs to a game system (Story 1.14). */
    void insertMaterialSystem(MaterialSystemRow row);

    /** The current owner's game systems of a material, oldest first. */
    List<MaterialSystemRow> systemsOf(UUID materialId);

    /**
     * The current owner's game systems of all these materials, in one read (Story 3.3: the places of a page of
     * materials), by material and oldest first; another owner's material has none.
     */
    List<MaterialSystemRow> systemsOf(Collection<UUID> materialIds);

    /**
     * Where the current owner's material {@code id} belongs changes (Story 3.3), with the material locked first: to the
     * campaign {@code campaignId} and no system when it is not null, else to the systems of {@code systems} (rows of
     * this material and owner, at least one) and no campaign. An attachment to a system not among them goes, one already
     * there stays as it is, and the others are added; the material's version rises by one. Answers the material as
     * stored; empty for an unknown id or another owner's. Throws {@link StaleMaterialVersion} when the material is not at
     * {@code version}, and nothing is written. Never touches a version, a passage or the index.
     */
    Optional<MaterialRow> changeBelonging(UUID id, long version, UUID campaignId, List<MaterialSystemRow> systems,
                                          Instant now);

    /**
     * The current owner's materials whose newest version (the latest created, then the greatest id) is in one of
     * {@code states}, each with that version: newest material first (descending id, a UUID v7), only those whose id is
     * below {@code before} when it is given, at most {@code limit}.
     */
    List<NewestVersion> newestIn(Collection<String> states, UUID before, int limit);

    /**
     * As {@link #newestIn}, only the materials attached to the system {@code systemId} ({@code material_system}) or
     * whose campaign is one of {@code campaignIds} (Story 3.1: a system's materials).
     */
    List<NewestVersion> newestAttached(Collection<String> states, UUID systemId, Collection<UUID> campaignIds, UUID before,
                                       int limit);

    /**
     * As {@link #newestIn}, by position instead of by cursor (a numbered page): the materials after the {@code offset}
     * newest ones, at most {@code limit}.
     */
    List<NewestVersion> newestFrom(Collection<String> states, int offset, int limit);

    /** How many materials {@link #newestIn} lists in all for these states. */
    long newestCount(Collection<String> states);

    /** As {@link #newestAttached}, by position instead of by cursor. */
    List<NewestVersion> newestAttachedFrom(Collection<String> states, UUID systemId, Collection<UUID> campaignIds,
                                           int offset, int limit);

    /** How many materials {@link #newestAttached} lists in all for these states, this system and these campaigns. */
    long newestAttachedCount(Collection<String> states, UUID systemId, Collection<UUID> campaignIds);

    /** The current owner's material with this id and its newest version; empty for an unknown id or another owner's. */
    Optional<NewestVersion> newest(UUID materialId);

    /** The current owner's material with this id; empty for an unknown id or another owner's. */
    Optional<MaterialRow> findMaterial(UUID id);

    /** The current owner's version with this id; empty for an unknown id or another owner's. */
    Optional<VersionRow> findVersion(UUID id);

    /** Moves the current owner's version to {@code state}, with its format and size; false when there is none. */
    boolean changeVersion(UUID id, String state, String format, Long sizeBytes, Instant now);

    /** Moves the current owner's version to {@code state}, changing nothing else; false when there is none. */
    boolean changeVersionState(UUID id, String state, Instant now);

    /**
     * Records what an ingestion step reported on the current owner's version (Story 1.15): the pipeline version and
     * the page total, each only when not null; false when there is no such version.
     */
    boolean recordIngestion(UUID id, String pipelineVersion, Integer pageTotal, Instant now);

    /**
     * The current owner's version with this id, locked until the end of the transaction (Story 1.16: a report's checks
     * of its page ranges and the end of its chain see no other report's writes); empty for an unknown id or another
     * owner's.
     */
    Optional<VersionRow> lockVersion(UUID id);

    /** A restarted chain (Story 1.16): the version's pipeline version goes, its page total stays; false when none. */
    boolean clearPipelineVersion(UUID id, Instant now);

    /** The current owner's version ends {@code failed} with {@code code}; false when there is none. */
    boolean failVersion(UUID id, String code, Instant now);

    /** Records an ingestion step of the current owner (Story 1.15). */
    void insertStep(StepRow row);

    /** The current owner's step of this job; empty for an unknown job or another owner's. */
    Optional<StepRow> findStepByJob(UUID jobId);

    /** How many steps the current owner's version has had. */
    long countSteps(UUID versionId);

    /** The current owner's step of this job ends at {@code now} unless it ended already (Story 1.16); false when none. */
    boolean endStep(UUID jobId, Instant now);

    /** The current owner's held step with this id ends at {@code now} with no job (Story 1.18); false when none. */
    boolean endHeldStep(UUID stepId, Instant now);

    /** The current owner's held step with this id gets its job (Story 1.18); false when there is no such held step. */
    boolean startHeldStep(UUID stepId, UUID jobId);

    /** The current owner's steps of a version that have not ended, oldest first (Story 1.16). */
    List<StepRow> unendedSteps(UUID versionId);

    /** The current owner's unended steps of all these versions, in one read (Story 1.17). */
    List<StepRow> unendedSteps(Collection<UUID> versionIds);

    /** Records a file of the current owner's version (Story 1.15). */
    void insertVersionFile(VersionFileRow row);

    /** The current owner's files of a version, by role and first page. */
    List<VersionFileRow> versionFiles(UUID versionId);

    /** {@link #versionFiles(UUID)} of these roles only (Story 1.19: a version may have a picture per page). */
    List<VersionFileRow> versionFiles(UUID versionId, Collection<String> roles);

    /** The current owner's file of a version with this file id; empty when there is none. */
    Optional<VersionFileRow> versionFile(UUID versionId, UUID fileId);

    /** Deletes the current owner's files of a version and answers them, so the caller removes them from the store. */
    List<VersionFileRow> deleteVersionFiles(UUID versionId);

    /** {@link #deleteVersionFiles(UUID)} for the files of these roles only (Story 1.16). */
    List<VersionFileRow> deleteVersionFiles(UUID versionId, Collection<String> roles);

    // --- page_layer and page_outcome (Story 1.16)

    /** Records the layer of a range of pages of the current owner's version. */
    void insertPageLayer(PageRangeRow row);

    /** Records the outcome of a range of pages of the current owner's version. */
    void insertPageOutcome(PageRangeRow row);

    /** Whether a page from {@code firstPage} to {@code lastPage} of the version already has a layer. */
    boolean layerOverlaps(UUID versionId, int firstPage, int lastPage);

    /** Whether a page from {@code firstPage} to {@code lastPage} of the version already has an outcome. */
    boolean outcomeOverlaps(UUID versionId, int firstPage, int lastPage);

    /** The version's outcome ranges that touch {@code firstPage} to {@code lastPage}, by first page. */
    List<PageRangeRow> outcomesWithin(UUID versionId, int firstPage, int lastPage);

    /** The version's layer ranges, by first page. */
    List<PageRangeRow> pageLayers(UUID versionId);

    /** The layer ranges of all these versions, in one read, by version and first page (Story 1.17). */
    List<PageRangeRow> pageLayers(Collection<UUID> versionIds);

    /** The outcome ranges of all these versions, in one read, by version and first page (Story 1.17). */
    List<PageRangeRow> pageOutcomes(Collection<UUID> versionIds);

    /** How many pages of each version have an outcome; a version with none is left out. */
    Map<UUID, Long> pagesDone(Collection<UUID> versionIds);

    /** Deletes the version's layers and outcomes (a restarted chain); answers how many ranges went. */
    int deletePageRanges(UUID versionId);

    // --- passage and version_outline (Story 1.18)

    /** Records passages of the current owner's versions. */
    void insertPassages(List<PassageRow> rows);

    /** The current owner's passage with this id; empty for an unknown id or another owner's. */
    Optional<PassageRow> findPassage(UUID id);

    /** The current owner's passages of a version, in reading order. */
    List<PassageRow> passages(UUID versionId);

    /** The ordinals of the current owner's passages of a version, ascending (Story 2.1). */
    List<Integer> passageOrdinals(UUID versionId);

    /**
     * How many of the current owner's passages of a version have an ordinal from {@code first} to {@code last}, counted
     * without reading one; 0 for another owner's.
     */
    long countPassages(UUID versionId, int first, int last);

    /** The current owner's passages of a version from ordinal {@code first} to {@code last}, in order (Story 2.1). */
    List<PassageRow> passages(UUID versionId, int first, int last);

    /** Deletes the current owner's passages of a version; answers how many went. */
    int deletePassages(UUID versionId);

    /** Records the outline of the current owner's version, which must have none. */
    void insertOutline(OutlineRow row);

    /** The current owner's outline of a version; empty when it has none, for an unknown version or another owner's. */
    Optional<OutlineRow> findOutline(UUID versionId);

    /** Deletes the current owner's outline of a version; false when it had none. */
    boolean deleteOutline(UUID versionId);

    // --- page_view (Story 1.19)

    /** Records the page view of a page of the current owner's version, which must have none. */
    void insertPageView(PageViewRow row);

    /** The current owner's page view of a page of a version; empty when there is none or for another owner's. */
    Optional<PageViewRow> findPageView(UUID versionId, int page);

    /**
     * Deletes the current owner's page views of the pages {@code firstPage} to {@code lastPage} of a version and
     * answers them, so the caller removes their pictures.
     */
    List<PageViewRow> deletePageViews(UUID versionId, int firstPage, int lastPage);

    /** Deletes every page view of the current owner's version; answers how many went. */
    int deletePageViews(UUID versionId);

    /** Deletes the current owner's file of a version with this file id and answers it; empty when there is none. */
    Optional<VersionFileRow> deleteVersionFile(UUID versionId, UUID fileId);

    /** The current owner's passages on a page of a version, in reading order (Story 1.19). */
    List<PassageRow> passagesOnPage(UUID versionId, int page);

    // --- review (Story 1.20)

    /** The current owner's versions of a material, oldest first; empty for an unknown material or another owner's. */
    List<VersionRow> versionsOf(UUID materialId);

    /** Every step of the current owner's version, ended or not, oldest first. */
    List<StepRow> steps(UUID versionId);

    /** Deletes the current owner's outcome ranges of a version that have this outcome; answers how many went. */
    int deleteOutcomes(UUID versionId, String outcome);

    /** The pages from {@code firstPage} to {@code lastPage} of the current owner's version that have a page view. */
    List<Integer> pagesWithView(UUID versionId, int firstPage, int lastPage);

    /** The current owner's version becomes {@code discarded} with its removal pending; false when there is none. */
    boolean discardVersion(UUID id, Instant now);

    /** The removal of the current owner's discarded version has finished; false when there is no such version. */
    boolean clearRemovalPending(UUID id, Instant now);

    // --- the index (Story 2.1)

    /** Records the index recipe of the current owner's version, or clears it with null; false when there is none. */
    boolean recordIndexRecipe(UUID id, String recipe);

    /** The current owner's {@code ready} versions whose index recipe is not {@code recipe} (null counts as not). */
    List<VersionRow> readyOutside(String recipe);

    // --- scope (Story 2.2): what a campaign may use, read only through materials.service.ScopeReads

    /**
     * The current owner's {@code ready} versions of the materials whose campaign is {@code campaignId}, oldest first;
     * empty for an unknown campaign or another owner's.
     */
    List<VersionRow> readyOfCampaign(UUID campaignId);

    /**
     * The current owner's {@code ready} versions of the materials that belong to the game system {@code systemId}
     * ({@code material_system}), oldest first; empty for an unknown system or another owner's.
     */
    List<VersionRow> readyOfSystem(UUID systemId);

    /** The ids of the current owner's passages of a version from ordinal {@code first} to {@code last}, in order. */
    List<UUID> passageIds(UUID versionId, int first, int last);

    /** The current owner's passages with these ids, in no set order; an unknown id or another owner's is left out. */
    List<PassageRow> findPassages(Collection<UUID> ids);

    /** Deletes the current owner's version; false when there is none. */
    boolean deleteVersion(UUID id);

    /**
     * Deletes the current owner's material, which must have no version left, with the game systems it belongs to;
     * false when there is none.
     */
    boolean deleteMaterial(UUID id);

    // --- a system's deletion (Story 3.1 part B)

    /** The current owner's materials whose campaign is one of {@code campaignIds}, by id. */
    List<MaterialRow> materialsOfCampaigns(Collection<UUID> campaignIds);

    /** The current owner's materials attached to the system {@code systemId} and to no other system, by id. */
    List<MaterialRow> materialsOnlyOfSystem(UUID systemId);

    /** The current owner's materials attached to the system {@code systemId} and to another system as well, by id. */
    List<MaterialRow> materialsAlsoOfOtherSystems(UUID systemId);

    /** The current owner's material is no longer attached to the system {@code systemId}; false when it was not. */
    boolean detachSystem(UUID materialId, UUID systemId);

    /** Deletes every step of the current owner's version, ended or not; answers how many went. */
    int deleteSteps(UUID versionId);
}

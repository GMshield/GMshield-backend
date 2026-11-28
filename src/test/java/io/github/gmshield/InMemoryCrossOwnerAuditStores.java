package io.github.gmshield;

import io.github.gmshield.accounts.data.InMemoryAccountStore;
import io.github.gmshield.artifacts.data.InMemoryArtifactIndexStore;
import io.github.gmshield.artifacts.data.InMemoryArtifactReferenceStore;
import io.github.gmshield.artifacts.data.InMemoryArtifactShareStore;
import io.github.gmshield.artifacts.data.InMemoryArtifactVersionStore;
import io.github.gmshield.conversations.data.InMemoryConversationStore;
import io.github.gmshield.conversations.data.InMemorySearchStore;
import io.github.gmshield.conversations.data.InMemoryTurnReferenceStore;
import io.github.gmshield.jobs.data.InMemoryJobStorage;
import io.github.gmshield.jobs.data.InMemoryJobStream;
import io.github.gmshield.jobs.data.MutableClock;
import io.github.gmshield.knowledge.data.InMemoryKnowledgeStore;
import io.github.gmshield.knowledge.data.InMemoryOntologyStore;
import io.github.gmshield.knowledge.data.InMemoryRecordRelationStore;
import io.github.gmshield.knowledge.data.InMemoryRecordStore;
import io.github.gmshield.library.data.InMemoryDeletionStore;
import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.materials.data.InMemoryIndexStore;
import io.github.gmshield.materials.data.InMemoryMaterialStore;
import io.github.gmshield.materials.data.InMemoryUploadStorage;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.scope.data.InMemoryScopeStore;
import io.github.gmshield.vault.service.Vault;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The unit audit's stores: the in-memory twins of the stores, the clock it sets and the in-memory work streams. Each
 * table of {@link #tables} is read whole (the unit context holds the audit's rows only); that list is kept by hand, so
 * a new in-memory table is compared only once it is added there.
 */
final class InMemoryCrossOwnerAuditStores implements CrossOwnerAuditStores {

    private static final Set<String> OPEN = Set.of("offered", "running", "waiting");

    /** The in-memory stores the audit reads. */
    record Twins(InMemoryAccountStore accounts, InMemoryJobStorage jobs, InMemoryJobStream stream,
                 InMemoryLibraryStore library, InMemoryDeletionStore deletions, InMemoryScopeStore scopes,
                 InMemoryKnowledgeStore knowledge, InMemoryRecordStore records, InMemoryOntologyStore ontology,
                 InMemoryArtifactVersionStore artifactVersions, InMemoryArtifactIndexStore artifactIndex,
                 InMemoryArtifactReferenceStore artifactReferences, InMemoryRecordRelationStore recordRelations,
                 InMemoryArtifactShareStore artifactShares, InMemoryMaterialStore materials,
                 InMemoryUploadStorage uploads, InMemoryIndexStore index,
                 InMemoryConversationStore conversations, InMemoryTurnReferenceStore references,
                 InMemorySearchStore searches, MutableClock clock, Vault vault) {
    }

    private final Twins twins;
    private final Instant start;

    InMemoryCrossOwnerAuditStores(Twins twins, Instant start) {
        this.twins = twins;
        this.start = start;
    }

    @Override
    public void start() {
        twins.clock().set(start);
        // One ingestion job at a time: end whatever an earlier test left unended.
        twins.jobs().rows.replaceAll((id, row) -> OPEN.contains(row.state())
                ? row.released("cancelled", null, row.updatedAt()) : row);
        twins.stream().clear();
    }

    @Override
    public void endIngestion() {
        twins.jobs().rows.replaceAll((id, row) -> row.lane().equals("ingestion") && OPEN.contains(row.state())
                ? row.released("cancelled", null, row.updatedAt()) : row);
    }

    @Override
    public UUID account(String sub) {
        return twins.accounts().findByGoogleIdFingerprint(twins.vault().fingerprint("account", "google_id", sub))
                .orElseThrow().id();
    }

    @Override
    public String versionState(UUID versionId) {
        return twins.materials().versions.get(versionId).state();
    }

    @Override
    public UUID passage(UUID versionId) {
        return twins.materials().passages.values().stream().filter(row -> row.versionId().equals(versionId))
                .map(PassageRow::id).findFirst().orElseThrow();
    }

    @Override
    public UUID turnJob(UUID turnId) {
        return twins.conversations().turns.get(turnId).jobId();
    }

    @Override
    public Set<UUID> jobIds(UUID ownerId) {
        return twins.jobs().rows.values().stream().filter(job -> ownerId.equals(job.ownerId()))
                .map(job -> job.id()).collect(Collectors.toSet());
    }

    @Override
    public List<Step> offeredSteps(UUID versionId) {
        return twins.materials().steps.values().stream().filter(step -> step.versionId().equals(versionId))
                .filter(step -> step.jobId() != null && twins.jobs().row(step.jobId()).state().equals("offered"))
                .map(step -> new Step(step.jobId(), step.stage(), step.inputFileId())).toList();
    }

    /**
     * A copy of these in-memory tables, by name: system, campaign and their deleting marks, shield (Story 6.1), roll (Story 6.4), deletion, scope, record type,
     * record (Story 4.2, its passage links inside each row), proposal, term and term alias (Story 4.4), artifact (a
     * record of a campaign, kept apart in memory) and record version (Story 5.1), artifact index and artifact index
     * removal (Story 5.1 part B), artifact reference (Story 5.4 part A), record relation (Story 5.5 part A), artifact
     * share (Story 5.6 part A); material, material version, material system, ingestion step, version file, page layer,
     * page outcome, passage, outline, page view, upload, index change, index removal; conversation and its deleting
     * marks, turn, event batch, turn reference, search, search result; job and job output. Every owner's rows.
     */
    @Override
    public Map<String, Map<Object, Object>> tables(Collection<UUID> owners) {
        Map<String, Map<Object, Object>> tables = new LinkedHashMap<>();
        tables.put("system", copy(twins.library().systems));
        tables.put("campaign", copy(twins.library().campaigns));
        tables.put("system deleting", copy(twins.library().deleting));
        tables.put("campaign deleting", copy(twins.library().deletingCampaigns));
        tables.put("shield", copy(twins.library().shields));
        tables.put("roll", copy(twins.library().rolls));
        tables.put("deletion", copy(twins.deletions().rows));
        tables.put("scope", copy(twins.scopes().rows));
        tables.put("record type", copy(twins.knowledge().recordTypes));
        tables.put("record", copy(twins.records().records));
        tables.put("proposal", copy(twins.ontology().proposals));
        tables.put("term", copy(twins.ontology().terms));
        tables.put("term alias", copy(twins.ontology().aliases));
        tables.put("artifact", copy(twins.records().artifacts));
        tables.put("record version", copy(twins.artifactVersions().rows));
        tables.put("artifact index", copy(twins.artifactIndex().rows));
        tables.put("artifact index removal", copy(twins.artifactIndex().removalRows));
        tables.put("artifact reference", copy(twins.artifactReferences().rows));
        tables.put("record relation", copy(twins.recordRelations().rows));
        tables.put("artifact share", copy(twins.artifactShares().rows));
        tables.put("material", copy(twins.materials().materials));
        tables.put("material version", copy(twins.materials().versions));
        tables.put("material system", copy(twins.materials().systems));
        tables.put("ingestion step", copy(twins.materials().steps));
        tables.put("version file", copy(twins.materials().files));
        tables.put("page layer", copy(twins.materials().layers));
        tables.put("page outcome", copy(twins.materials().outcomes));
        tables.put("passage", copy(twins.materials().passages));
        tables.put("outline", copy(twins.materials().outlines));
        tables.put("page view", copy(twins.materials().pageViews));
        tables.put("upload", copy(twins.uploads().rows));
        tables.put("index change", copy(twins.index().changes));
        tables.put("index removal", copy(twins.index().removals));
        tables.put("record removal", copy(twins.index().recordRemovals));
        tables.put("conversation", copy(twins.conversations().conversations));
        tables.put("conversation deleting", copy(twins.conversations().deleting));
        tables.put("turn", copy(twins.conversations().turns));
        tables.put("event batch", copy(twins.conversations().batches));
        tables.put("turn reference", copy(twins.references().rows));
        tables.put("search", copy(twins.searches().searches));
        tables.put("search result", copy(twins.searches().results));
        tables.put("job", copy(twins.jobs().rows));
        tables.put("job output", copy(twins.jobs().outputs));
        return tables;
    }

    @Override
    public Map<String, List<String>> held() {
        return Map.ofEntries(Map.entry("system", List.of("system")), Map.entry("record type", List.of("recordType")),
                Map.entry("record", List.of("record")), Map.entry("artifact", List.of("artifact")),
                Map.entry("campaign", List.of("campaign")), Map.entry("shield", List.of("shield", "firstShield")),
                Map.entry("roll", List.of("roll")),
                Map.entry("proposal", List.of("proposal", "termProposal")), Map.entry("term", List.of("term")),
                Map.entry("material", List.of("material", "imageSetMaterial")),
                Map.entry("material version", List.of("version", "superseded", "imageSet")),
                Map.entry("passage", List.of("passage")), Map.entry("upload", List.of("upload")),
                Map.entry("conversation", List.of("conversation", "rulesConversation")),
                Map.entry("turn", List.of("turn", "dismissedTurn", "waitingTurn")), Map.entry("search", List.of("search")));
    }

    @Override
    public Map<String, List<UUID>> offered() {
        Map<String, List<UUID>> offered = new HashMap<>();
        twins.stream().streams.keySet().forEach(name -> offered.put(name, twins.stream().offered(name)));
        return offered;
    }

    @Override
    public List<Job> jobs(Collection<UUID> owners) {
        return twins.jobs().rows.values().stream().map(row -> new Job(row.id(), row.ownerId(), row.kind())).toList();
    }

    @Override
    public List<Search> searches(Collection<UUID> owners) {
        return twins.searches().searches.values().stream()
                .map(row -> new Search(row.id(), row.ownerId(), row.campaignId())).toList();
    }

    private static Map<Object, Object> copy(Map<?, ?> table) {
        return new HashMap<>(table);
    }

    private static Map<Object, Object> copy(Collection<?> marks) {
        Map<Object, Object> copied = new HashMap<>();
        marks.forEach(mark -> copied.put(mark, Boolean.TRUE));
        return copied;
    }
}

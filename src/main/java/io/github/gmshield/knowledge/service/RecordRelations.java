package io.github.gmshield.knowledge.service;

import io.github.gmshield.knowledge.data.ArtifactRow;
import io.github.gmshield.knowledge.data.RecordRelationRow;
import io.github.gmshield.knowledge.data.RecordRelationStore;
import io.github.gmshield.knowledge.data.RecordRow;
import io.github.gmshield.knowledge.data.RecordStore;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.service.RecordSources;
import io.github.gmshield.scope.service.CampaignArtifacts;
import io.github.gmshield.scope.service.CampaignOverrides;
import io.github.gmshield.scope.service.CampaignScopes;
import io.github.gmshield.scope.service.ResolvedScope;
import io.github.gmshield.scope.service.Scopes;
import io.github.gmshield.scope.service.Tier;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Vault;
import java.text.Collator;
import java.text.Normalizer;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The relations between records (Story 5.5 part A), for the owner of the work ({@link OwnerContext}): an artifact
 * overrides one system record in its own campaign ({@code record_relation}, kind {@value RecordRelationRow#OVERRIDES}).
 * The {@code artifacts} module marks and unmarks through it, in its own transaction, once it has found the artifact in
 * its campaign; the {@code scope} module reads the marks through the port {@link CampaignOverrides}, which this class
 * implements, so {@code scope} depends on nothing here.
 *
 * <ul>
 *   <li>A target is a live extracted record of the system tier of the campaign's resolved scope ({@link
 *       Scopes#resolveSources} with its stored settings): its version is an allowed source of that tier, and its key one
 *       of the system's record types. Any record type may be a target. {@link #targets} lists them a page at a time,
 *       alphabetical as {@link Records#records} sorts (the names decrypted, a root-locale collator at secondary
 *       strength, the id breaking ties), filtered by a case- and accent-folded "contains" on the decrypted name.</li>
 *   <li>{@link #override}: the artifact's mark points to the target (a new mark, or its mark replaced). A record that
 *       is not a target is {@value #TARGET_CODE} (400). A record a live artifact of the same campaign overrides already
 *       is {@value #TAKEN_CODE} (409), its params naming that artifact's id. Both rows are locked first, so two marks of
 *       one artifact or of one record take turns. {@link #removeOverride} drops the mark, and is safe to repeat.</li>
 *   <li>{@link #mark}: an artifact's mark as its page shows it: the record's id, name and type key, the campaign's
 *       system, and {@code removed} when the record is tombstoned or is no target any more (out of the scope, or of a
 *       superseded version); the mark then has no effect. A record whose row is gone takes its mark with it.</li>
 *   <li>A mark applies in the artifact's campaign only, from a live artifact to a live record. A deleting artifact's
 *       mark has no effect, and no artifact of another campaign ever sees it.</li>
 * </ul>
 *
 * <p>Story 5.5 part B: an artifact is related to other records (kind {@value RecordRelationRow#RELATED}), with no
 * direction: one row, from the artifact whose page made it, read from both sides.
 *
 * <ul>
 *   <li>A relation target is a live extracted record of any tier of the campaign's resolved scope (its version an
 *       allowed source, its key one of the system's record types), with its tier, or another live artifact of the
 *       same campaign ({@link CampaignArtifacts}, tier campaign). {@link #relationTargets} lists those of one type key,
 *       sorted and filtered as {@link #targets}, leaving out the artifact itself and the records it is related to
 *       already.</li>
 *   <li>{@link #relate}: a relation that exists already, in either direction, is a success that stores nothing
 *       (also once its record is no target). Otherwise a record that is no target is {@value #RELATION_TARGET_CODE}
 *       (400). An artifact on either side with
 *       {@value #MAX_RELATIONS} relations already is {@value #RELATIONS_FULL_CODE} (409). Both rows are locked first.
 *       {@link #unrelate} removes the relation in either direction, and is safe to repeat.</li>
 *   <li>{@link #related}: an artifact's relations as its page shows them, oldest first. Another artifact shows while it
 *       is a live artifact of the campaign (a deleting one is left out); an extracted record shows {@code removed}
 *       when it is tombstoned or no target any more (out of the scope, or of a superseded version). A record whose row
 *       is gone takes its relations with it. {@link #relatedArtifacts}: the live artifacts of a campaign related to a
 *       record, as the campaign's record page shows them.</li>
 *   <li>A relation changes no answer: no read of a question's scope looks at it.</li>
 * </ul>
 *
 * Names leave only in these answers; logs carry ids and counts only.
 */
@Service
public class RecordRelations implements CampaignOverrides {

    static final String TARGET_CODE = "artifact.override_target";
    static final String TAKEN_CODE = "artifact.override_taken";
    /** A record that is not a live record of the system tier of the campaign's scope. */
    public static final ApiProblem OVERRIDE_TARGET = ApiProblem.of(400, "artifact", "override_target",
            "Not a record this artifact can override");
    /** A record another artifact of the campaign overrides already. */
    public static final ApiProblem OVERRIDE_TAKEN = ApiProblem.of(409, "artifact", "override_taken",
            "Record overridden already");
    static final String RELATION_TARGET_CODE = "artifact.relation_target";
    static final String RELATIONS_FULL_CODE = "artifact.relations_full";
    /** A record that is no relation target of the artifact (Story 5.5 part B). */
    public static final ApiProblem RELATION_TARGET = ApiProblem.of(400, "artifact", "relation_target",
            "Not a record this artifact can be related to");
    /** An artifact with the most relations already (Story 5.5 part B). */
    public static final ApiProblem RELATIONS_FULL = ApiProblem.of(409, "artifact", "relations_full",
            "Artifact has the most relations already");
    /** The most relations one artifact has, whichever side it is on (Story 5.5 part B). */
    public static final int MAX_RELATIONS = 50;
    /** A name filter: at most this many code points after trimming. */
    public static final int MAX_QUERY = 200;
    private static final Logger log = LoggerFactory.getLogger(RecordRelations.class);

    private final RecordStore records;
    private final RecordRelationStore relations;
    private final Vault vault;
    private final Library library;
    private final RecordSources sources;
    private final ObjectProvider<Scopes> scopes;
    private final ObjectProvider<CampaignScopes> campaignScopes;
    private final ObjectProvider<CampaignArtifacts> campaignArtifacts;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final Clock clock;

    RecordRelations(RecordStore records, RecordRelationStore relations, Vault vault, Library library,
                    RecordSources sources, ObjectProvider<Scopes> scopes, ObjectProvider<CampaignScopes> campaignScopes,
                    ObjectProvider<CampaignArtifacts> campaignArtifacts,
                    ObjectProvider<PlatformTransactionManager> transactions, ObjectProvider<Clock> clock) {
        this.records = records;
        this.relations = relations;
        this.vault = vault;
        this.library = library;
        this.sources = sources;
        this.scopes = scopes;
        this.campaignScopes = campaignScopes;
        this.campaignArtifacts = campaignArtifacts;
        this.transactions = transactions;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** A record an artifact can override, as the picker lists it: its id, name, type key and its material's name. */
    public record Target(UUID id, String name, String recordType, String materialName) {
        @Override
        public String toString() {
            return "Target[id=" + id + ", recordType=" + recordType + "]";
        }
    }

    /** One page of targets, alphabetical, with how many match in all. */
    public record TargetPage(List<Target> items, long total) {
        public TargetPage {
            items = List.copyOf(items);
        }
    }

    /**
     * An artifact's mark as its page shows it: the record's id, name and type key, the campaign's system's id, and
     * whether it has no effect ({@code removed}: tombstoned or no target any more). {@code toString} names ids only.
     */
    public record Mark(UUID recordId, String name, String recordType, UUID systemId, boolean removed) {
        @Override
        public String toString() {
            return "Mark[recordId=" + recordId + ", recordType=" + recordType + ", removed=" + removed + "]";
        }
    }

    /**
     * A record an artifact can be related to (Story 5.5 part B), as the picker lists it: its id, name, type key, tier
     * (campaign for an artifact), whether it is an artifact, and its material's name (null for an artifact).
     */
    public record RelationTarget(UUID id, String name, String recordType, Tier tier, boolean artifact,
                                 String materialName) {
        @Override
        public String toString() {
            return "RelationTarget[id=" + id + ", recordType=" + recordType + ", tier=" + tier + ", artifact="
                    + artifact + "]";
        }
    }

    /** One page of relation targets, alphabetical, with how many match in all (Story 5.5 part B). */
    public record RelationTargetPage(List<RelationTarget> items, long total) {
        public RelationTargetPage {
            items = List.copyOf(items);
        }
    }

    /**
     * A relation as the artifact's page shows it (Story 5.5 part B): the other record's id, name, type key and tier
     * (null for an extracted record out of the campaign's scope), whether it is an artifact, and whether it is
     * {@code removed} (an extracted record tombstoned or no target any more). {@code toString} names ids only.
     */
    public record Related(UUID recordId, String name, String recordType, Tier tier, boolean artifact,
                          boolean removed) {
        @Override
        public String toString() {
            return "Related[recordId=" + recordId + ", recordType=" + recordType + ", tier=" + tier + ", artifact="
                    + artifact + ", removed=" + removed + "]";
        }
    }

    /**
     * A live artifact of the campaign related to a record (Story 5.5 part B), as the campaign's record page shows it:
     * its id, its newest version's name and its type key. {@code toString} names the id and the type only.
     */
    public record RelatedArtifact(UUID artifactId, String name, String recordType) {
        @Override
        public String toString() {
            return "RelatedArtifact[artifactId=" + artifactId + ", recordType=" + recordType + "]";
        }
    }

    /**
     * The targets of the owner's campaign {@code campaignId} of the type key {@code recordType}, as the class says:
     * whose folded name contains the folded {@code query} (null or blank: all), the {@code offset} first skipped, at
     * most {@code limit}. Empty when the campaign is not the owner's or not readable; no item for a key that is not one
     * of its system's.
     */
    public Optional<TargetPage> targets(UUID campaignId, String recordType, String query, int offset, int limit) {
        UUID owner = OwnerContext.require();
        String folded = query == null ? "" : fold(query.strip());
        Optional<TargetPage> found = reading(() -> library.campaign(campaignId).map(campaign -> {
            ResolvedScope scope = scopeOf(campaign.id());
            List<UUID> system = scope.sources(Tier.SYSTEM);
            if (system.isEmpty() || !scope.recordTypes().contains(recordType)) {
                return new TargetPage(List.of(), 0);
            }
            Collator collator = Collator.getInstance(Locale.ROOT);
            collator.setStrength(Collator.SECONDARY);
            List<Named> named = new ArrayList<>();
            for (RecordRow row : records.ofTypeIn(system, recordType)) {
                String name = vault.decryptText(ExtractedRecordStore.name(row.id(), owner), row.name());
                if (folded.isEmpty() || fold(name).contains(folded)) {
                    named.add(new Named(row, name));
                }
            }
            named.sort(Comparator.comparing(Named::name, collator).thenComparing(one -> one.row().id()));
            List<Named> shown = named.subList(Math.min(offset, named.size()), Math.min(offset + limit, named.size()));
            Map<UUID, String> materials = sources.materialNames(shown.stream().map(one -> one.row().versionId())
                    .toList());
            return new TargetPage(shown.stream().map(one -> new Target(one.row().id(), one.name(), one.row().type(),
                    materials.getOrDefault(one.row().versionId(), ""))).toList(), named.size());
        }));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("filtered", !folded.isEmpty())
                .addKeyValue("offset", offset).addKeyValue("limit", limit).addKeyValue("found", found.isPresent())
                .addKeyValue("shown", found.map(page -> page.items().size()).orElse(0))
                .addKeyValue("total", found.map(TargetPage::total).orElse(0L)).log("knowledge.override_targets_listed");
        return found;
    }

    /**
     * The owner's artifact {@code artifactId} of the campaign {@code campaignId} overrides the record
     * {@code recordId} from now on, as the class says, in the caller's transaction, which must exist and in which the
     * caller found the artifact. Answers its mark, or empty when the artifact is not a live one of the campaign.
     */
    public Optional<Mark> override(UUID campaignId, UUID artifactId, UUID recordId) {
        UUID owner = requireTransaction();
        relations.lockRecords(recordId == null ? List.of(artifactId) : List.of(artifactId, recordId));
        Optional<ArtifactRow> artifact = liveArtifact(campaignId, artifactId);
        if (artifact.isEmpty()) {
            return Optional.empty();
        }
        Library.Campaign campaign = library.campaign(campaignId).orElseThrow(() -> new ApiException(
                ApiProblem.forStatus(404)));
        ResolvedScope scope = scopeOf(campaign.id());
        Optional<RecordRow> target = recordId == null ? Optional.empty() : records.find(recordId)
                .filter(row -> target(scope, row));
        if (target.isEmpty()) {
            log.atInfo().addKeyValue("campaignId", campaignId).addKeyValue("artifactId", artifactId)
                    .addKeyValue("reason", TARGET_CODE).log("knowledge.override_refused");
            throw new ApiException(OVERRIDE_TARGET);
        }
        Optional<UUID> taken = takenBy(campaignId, recordId, artifactId);
        if (taken.isPresent()) {
            log.atInfo().addKeyValue("campaignId", campaignId).addKeyValue("artifactId", artifactId)
                    .addKeyValue("recordId", recordId).addKeyValue("takenBy", taken.get())
                    .addKeyValue("reason", TAKEN_CODE).log("knowledge.override_refused");
            throw new ApiException(OVERRIDE_TAKEN.withParams(Map.of("artifactId", taken.get().toString())));
        }
        Optional<RecordRelationRow> existing = relations.from(artifactId, RecordRelationRow.OVERRIDES);
        boolean replaced = existing.isPresent();
        if (replaced) {
            if (!existing.get().toRecordId().equals(recordId)) {
                relations.retarget(existing.get().id(), recordId);
            }
        } else {
            relations.insert(new RecordRelationRow(Ids.newId(), owner, artifactId, recordId,
                    RecordRelationRow.OVERRIDES, clock.instant().truncatedTo(ChronoUnit.MICROS)));
        }
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("artifactId", artifactId).addKeyValue("recordId", recordId)
                .addKeyValue("replaced", replaced).log("knowledge.override_set");
        RecordRow row = target.get();
        return Optional.of(new Mark(row.id(), vault.decryptText(ExtractedRecordStore.name(row.id(), owner),
                row.name()), row.type(), campaign.system().id(), false));
    }

    /**
     * The owner's artifact {@code artifactId} of the campaign {@code campaignId} overrides nothing from now on, in the
     * caller's transaction, which must exist; true when it is a live artifact of the campaign (whether it had a mark or
     * not), false otherwise (nothing changes).
     */
    public boolean removeOverride(UUID campaignId, UUID artifactId) {
        UUID owner = requireTransaction();
        relations.lockRecords(List.of(artifactId));
        if (liveArtifact(campaignId, artifactId).isEmpty()) {
            return false;
        }
        boolean removed = relations.deleteFrom(artifactId, RecordRelationRow.OVERRIDES);
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("artifactId", artifactId).addKeyValue("removed", removed).log("knowledge.override_removed");
        return true;
    }

    /**
     * The mark of the owner's live artifact {@code artifactId} of the campaign {@code campaignId}, as the class says, in
     * the caller's transaction (or a read of its own); empty for none, for an artifact that is not a live one of the
     * campaign, and once the record's row is gone.
     */
    public Optional<Mark> mark(UUID campaignId, UUID artifactId) {
        UUID owner = OwnerContext.require();
        return reading(() -> relations.from(artifactId, RecordRelationRow.OVERRIDES).flatMap(relation -> {
            if (liveArtifact(campaignId, artifactId).isEmpty()) {
                return Optional.empty();
            }
            Optional<Library.Campaign> campaign = library.campaign(campaignId);
            Optional<RecordRow> row = records.findAll(List.of(relation.toRecordId()), RecordStore.Reach.ALL).stream()
                    .findFirst();
            if (campaign.isEmpty() || row.isEmpty()) {
                return Optional.empty();
            }
            RecordRow record = row.get();
            boolean removed = record.deleted() || !target(scopeOf(campaign.get().id()), record);
            return Optional.of(new Mark(record.id(), vault.decryptText(ExtractedRecordStore.name(record.id(), owner),
                    record.name()), record.type(), campaign.get().system().id(), removed));
        }));
    }

    /**
     * The relation targets of the owner's live artifact {@code artifactId} of the campaign {@code campaignId} of the
     * type key {@code recordType} (Story 5.5 part B), as the class says: whose folded name contains the folded
     * {@code query} (null or blank: all), the {@code offset} first skipped, at most {@code limit}. Empty when the
     * campaign is not the owner's or not readable, or the artifact is not a live one of it.
     */
    public Optional<RelationTargetPage> relationTargets(UUID campaignId, UUID artifactId, String recordType,
                                                        String query, int offset, int limit) {
        UUID owner = OwnerContext.require();
        String folded = query == null ? "" : fold(query.strip());
        Optional<RelationTargetPage> found = reading(() -> library.campaign(campaignId)
                .filter(campaign -> liveArtifact(campaign.id(), artifactId).isPresent()).map(campaign -> {
                    Set<UUID> related = relations.ofEither(List.of(artifactId), RecordRelationRow.RELATED).stream()
                            .map(link -> link.other(artifactId)).collect(Collectors.toSet());
                    ResolvedScope scope = scopeOf(campaign.id());
                    List<Candidate> named = new ArrayList<>();
                    List<UUID> allowed = List.copyOf(scope.allowedSources());
                    if (!allowed.isEmpty() && scope.recordTypes().contains(recordType)) {
                        for (RecordRow row : records.ofTypeIn(allowed, recordType)) {
                            Optional<Tier> tier = scope.tierOf(row.versionId());
                            if (related.contains(row.id()) || tier.isEmpty()) {
                                continue;
                            }
                            String name = vault.decryptText(ExtractedRecordStore.name(row.id(), owner), row.name());
                            if (folded.isEmpty() || fold(name).contains(folded)) {
                                named.add(new Candidate(row.id(), name, row.type(), tier.get(), row.versionId()));
                            }
                        }
                    }
                    Set<UUID> others = new HashSet<>(campaignArtifacts.getObject().ids(campaign.id()));
                    others.remove(artifactId);
                    others.removeAll(related);
                    for (CampaignArtifacts.Artifact other : campaignArtifacts.getObject().read(campaign.id(), others)) {
                        if (other.recordType().equals(recordType)
                                && (folded.isEmpty() || fold(other.name()).contains(folded))) {
                            named.add(new Candidate(other.id(), other.name(), other.recordType(), Tier.CAMPAIGN, null));
                        }
                    }
                    Collator collator = Collator.getInstance(Locale.ROOT);
                    collator.setStrength(Collator.SECONDARY);
                    named.sort(Comparator.comparing(Candidate::name, collator).thenComparing(Candidate::id));
                    List<Candidate> shown = named.subList(Math.min(offset, named.size()),
                            Math.min(offset + limit, named.size()));
                    Map<UUID, String> materials = sources.materialNames(shown.stream().map(Candidate::versionId)
                            .filter(Objects::nonNull).toList());
                    return new RelationTargetPage(shown.stream().map(one -> new RelationTarget(one.id(), one.name(),
                            one.recordType(), one.tier(), one.versionId() == null, one.versionId() == null ? null
                            : materials.getOrDefault(one.versionId(), ""))).toList(), named.size());
                }));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("artifactId", artifactId).addKeyValue("filtered", !folded.isEmpty())
                .addKeyValue("offset", offset).addKeyValue("limit", limit).addKeyValue("found", found.isPresent())
                .addKeyValue("shown", found.map(page -> page.items().size()).orElse(0))
                .addKeyValue("total", found.map(RelationTargetPage::total).orElse(0L))
                .log("knowledge.relation_targets_listed");
        return found;
    }

    /**
     * The owner's artifact {@code artifactId} of the campaign {@code campaignId} is related to the record
     * {@code recordId} from now on (Story 5.5 part B), as the class says, in the caller's transaction, which must exist
     * and in which the caller found the artifact. True when the artifact is a live one of the campaign (the relation
     * stored, or there already), false otherwise (nothing changes).
     */
    public boolean relate(UUID campaignId, UUID artifactId, UUID recordId) {
        UUID owner = requireTransaction();
        boolean other = recordId != null && !recordId.equals(artifactId);
        relations.lockRecords(other ? List.of(artifactId, recordId) : List.of(artifactId));
        if (liveArtifact(campaignId, artifactId).isEmpty() || library.campaign(campaignId).isEmpty()) {
            return false;
        }
        // A relation that exists already is a success that stores nothing, even once its extracted record has left the
        // scope; a deleting artifact (neither a live artifact nor an extracted record) is no target, so it falls through.
        boolean toArtifact = other && liveArtifact(campaignId, recordId).isPresent();
        if (other && (toArtifact || !records.findAll(Set.of(recordId), RecordStore.Reach.ALL).isEmpty())
                && !relations.between(artifactId, recordId, RecordRelationRow.RELATED).isEmpty()) {
            log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                    .addKeyValue("artifactId", artifactId).addKeyValue("recordId", recordId)
                    .addKeyValue("stored", false).log("knowledge.relation_set");
            return true;
        }
        boolean target = toArtifact || other && records.find(recordId)
                .filter(row -> relationTarget(scopeOf(campaignId), row)).isPresent();
        if (!target) {
            log.atInfo().addKeyValue("campaignId", campaignId).addKeyValue("artifactId", artifactId)
                    .addKeyValue("reason", RELATION_TARGET_CODE).log("knowledge.relation_refused");
            throw new ApiException(RELATION_TARGET);
        }
        if (full(artifactId) || toArtifact && full(recordId)) {
            log.atInfo().addKeyValue("campaignId", campaignId).addKeyValue("artifactId", artifactId)
                    .addKeyValue("recordId", recordId).addKeyValue("reason", RELATIONS_FULL_CODE)
                    .log("knowledge.relation_refused");
            throw new ApiException(RELATIONS_FULL);
        }
        relations.insert(new RecordRelationRow(Ids.newId(), owner, artifactId, recordId, RecordRelationRow.RELATED,
                clock.instant().truncatedTo(ChronoUnit.MICROS)));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("artifactId", artifactId).addKeyValue("recordId", recordId)
                .addKeyValue("artifact", toArtifact).addKeyValue("stored", true).log("knowledge.relation_set");
        return true;
    }

    /**
     * The owner's artifact {@code artifactId} of the campaign {@code campaignId} is no longer related to the record
     * {@code recordId} (Story 5.5 part B), whichever side made the relation, in the caller's transaction, which must
     * exist; true when it is a live artifact of the campaign (whether it was related or not; a null record is none),
     * false otherwise (nothing changes).
     */
    public boolean unrelate(UUID campaignId, UUID artifactId, UUID recordId) {
        UUID owner = requireTransaction();
        boolean other = recordId != null && !recordId.equals(artifactId);
        relations.lockRecords(other ? List.of(artifactId, recordId) : List.of(artifactId));
        if (liveArtifact(campaignId, artifactId).isEmpty()) {
            return false;
        }
        int removed = 0;
        if (other) {
            for (RecordRelationRow link : relations.between(artifactId, recordId, RecordRelationRow.RELATED)) {
                if (relations.delete(link.id())) {
                    removed++;
                }
            }
        }
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("artifactId", artifactId).addKeyValue("recordId", recordId)
                .addKeyValue("removed", removed).log("knowledge.relation_removed");
        return true;
    }

    /**
     * The relations of the owner's live artifact {@code artifactId} of the campaign {@code campaignId} (Story 5.5 part
     * B), as the class says, oldest first, in the caller's transaction (or a read of its own); none for an artifact that
     * is not a live one of the campaign.
     */
    public List<Related> related(UUID campaignId, UUID artifactId) {
        UUID owner = OwnerContext.require();
        return reading(() -> {
            List<RecordRelationRow> links = relations.ofEither(List.of(artifactId), RecordRelationRow.RELATED);
            if (links.isEmpty() || liveArtifact(campaignId, artifactId).isEmpty()
                    || library.campaign(campaignId).isEmpty()) {
                return List.<Related>of();
            }
            List<UUID> others = links.stream().map(link -> link.other(artifactId)).toList();
            Map<UUID, CampaignArtifacts.Artifact> artifacts = artifactsOf(campaignId, others);
            Map<UUID, RecordRow> extracted = new HashMap<>();
            records.findAll(Set.copyOf(others), RecordStore.Reach.ALL).forEach(row -> extracted.put(row.id(), row));
            ResolvedScope scope = extracted.isEmpty() ? null : scopeOf(campaignId);
            List<Related> shown = new ArrayList<>();
            for (UUID other : others) {
                CampaignArtifacts.Artifact artifact = artifacts.get(other);
                RecordRow row = extracted.get(other);
                if (artifact != null) {
                    shown.add(new Related(other, artifact.name(), artifact.recordType(), Tier.CAMPAIGN, true, false));
                } else if (row != null && scope != null) {
                    shown.add(new Related(row.id(), vault.decryptText(ExtractedRecordStore.name(row.id(), owner),
                            row.name()), row.type(), scope.tierOf(row.versionId()).orElse(null), false,
                            !relationTarget(scope, row)));
                }
                // Otherwise a deleting artifact, or a record whose row is gone: not shown.
            }
            return List.copyOf(shown);
        });
    }

    /**
     * The live artifacts of the owner's campaign {@code campaignId} related to the record {@code recordId} (Story 5.5
     * part B), oldest relation first, in the caller's transaction (or a read of its own).
     */
    public List<RelatedArtifact> relatedArtifacts(UUID campaignId, UUID recordId) {
        OwnerContext.require();
        if (campaignId == null || recordId == null) {
            return List.of();
        }
        return reading(() -> {
            List<UUID> others = relations.ofEither(List.of(recordId), RecordRelationRow.RELATED).stream()
                    .map(link -> link.other(recordId)).distinct().toList();
            Map<UUID, CampaignArtifacts.Artifact> artifacts = artifactsOf(campaignId, others);
            return others.stream().map(artifacts::get).filter(Objects::nonNull)
                    .map(artifact -> new RelatedArtifact(artifact.id(), artifact.name(), artifact.recordType()))
                    .toList();
        });
    }

    /** The live artifacts of the owner's campaign among these ids, each with its newest version, by id. */
    private Map<UUID, CampaignArtifacts.Artifact> artifactsOf(UUID campaignId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, CampaignArtifacts.Artifact> found = new HashMap<>();
        campaignArtifacts.getObject().read(campaignId, Set.copyOf(ids)).forEach(artifact -> found.put(artifact.id(),
                artifact));
        return found;
    }

    /** Whether the artifact has the most relations already, whichever side it is on. */
    private boolean full(UUID artifactId) {
        return relations.ofEither(List.of(artifactId), RecordRelationRow.RELATED).size() >= MAX_RELATIONS;
    }

    /**
     * Whether the extracted record is a relation target of the scope (Story 5.5 part B): live, of an allowed source of
     * any tier, of one of the system's types.
     */
    private static boolean relationTarget(ResolvedScope scope, RecordRow row) {
        return !row.deleted() && scope.allowedSources().contains(row.versionId())
                && scope.recordTypes().contains(row.type());
    }

    /**
     * Whether each of these artifacts of the owner overrides a record, by id, in the caller's transaction (or a read of
     * its own): those with a mark, whatever its effect (Story 5.4 part B's dialog row says so).
     */
    public Set<UUID> overriding(Collection<UUID> artifactIds) {
        OwnerContext.require();
        if (artifactIds.isEmpty()) {
            return Set.of();
        }
        return reading(() -> relations.fromAll(artifactIds, RecordRelationRow.OVERRIDES).stream()
                .map(RecordRelationRow::fromRecordId).collect(Collectors.toUnmodifiableSet()));
    }

    @Override
    public Map<UUID, UUID> overriddenBy(UUID campaignId, Collection<UUID> recordIds) {
        OwnerContext.require();
        if (campaignId == null || recordIds.isEmpty()) {
            return Map.of();
        }
        return reading(() -> {
            Set<UUID> live = liveRecords(recordIds);
            List<RecordRelationRow> marks = relations.to(live, RecordRelationRow.OVERRIDES);
            Set<UUID> ofCampaign = liveArtifactsOf(campaignId, marks.stream().map(RecordRelationRow::fromRecordId)
                    .toList());
            Map<UUID, UUID> found = new HashMap<>();
            marks.stream().filter(mark -> ofCampaign.contains(mark.fromRecordId()))
                    .sorted(Comparator.comparing(RecordRelationRow::createdAt).thenComparing(RecordRelationRow::id))
                    .forEach(mark -> found.putIfAbsent(mark.toRecordId(), mark.fromRecordId()));
            return Map.copyOf(found);
        });
    }

    @Override
    public Map<UUID, UUID> overrides(UUID campaignId, Collection<UUID> artifactIds) {
        OwnerContext.require();
        if (campaignId == null || artifactIds.isEmpty()) {
            return Map.of();
        }
        return reading(() -> {
            Set<UUID> ofCampaign = liveArtifactsOf(campaignId, artifactIds);
            List<RecordRelationRow> marks = relations.fromAll(ofCampaign, RecordRelationRow.OVERRIDES);
            Set<UUID> live = liveRecords(marks.stream().map(RecordRelationRow::toRecordId).toList());
            return marks.stream().filter(mark -> live.contains(mark.toRecordId()))
                    .collect(Collectors.toUnmodifiableMap(RecordRelationRow::fromRecordId,
                            RecordRelationRow::toRecordId));
        });
    }

    /** The live artifact of the owner's campaign with this id, as the store reads it; empty otherwise. */
    private Optional<ArtifactRow> liveArtifact(UUID campaignId, UUID artifactId) {
        return campaignId == null || artifactId == null ? Optional.empty()
                : records.findArtifact(campaignId, artifactId);
    }

    /** The ids among these of the owner's campaign's live artifacts. */
    private Set<UUID> liveArtifactsOf(UUID campaignId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return records.findArtifacts(Set.copyOf(ids), RecordStore.Reach.LIVE).stream()
                .filter(row -> campaignId.equals(row.campaignId())).map(ArtifactRow::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** The ids among these of the owner's live extracted records. */
    private Set<UUID> liveRecords(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return records.findAll(Set.copyOf(ids), RecordStore.Reach.LIVE).stream().map(RecordRow::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The live artifact of the campaign, other than {@code artifactId}, that overrides the record already; the oldest
     * mark when several do (never written so, but a reprocess may bring two together).
     */
    private Optional<UUID> takenBy(UUID campaignId, UUID recordId, UUID artifactId) {
        List<RecordRelationRow> marks = relations.to(List.of(recordId), RecordRelationRow.OVERRIDES).stream()
                .filter(mark -> !mark.fromRecordId().equals(artifactId)).toList();
        Set<UUID> ofCampaign = liveArtifactsOf(campaignId, marks.stream().map(RecordRelationRow::fromRecordId)
                .toList());
        return marks.stream().filter(mark -> ofCampaign.contains(mark.fromRecordId()))
                .min(Comparator.comparing(RecordRelationRow::createdAt).thenComparing(RecordRelationRow::id))
                .map(RecordRelationRow::fromRecordId);
    }

    /** Whether the record is a target of the scope: live, of a system-tier source, of one of the system's types. */
    private static boolean target(ResolvedScope scope, RecordRow row) {
        return !row.deleted() && scope.sources(Tier.SYSTEM).contains(row.versionId())
                && scope.recordTypes().contains(row.type());
    }

    /** The campaign's scope under its stored settings, without pending passages. */
    private ResolvedScope scopeOf(UUID campaignId) {
        return scopes.getObject().resolveSources(campaignId, campaignScopes.getObject().settings(campaignId));
    }

    /**
     * Moves the links of the kind {@code kind} to the version {@code fromVersionId}'s live records to the record of
     * {@code toVersionId} each matches (Story 5.5 part A, Q2: a reprocess's acceptance, where Story 4.5 carries
     * corrections; from part B the relations too), in the caller's transaction, as
     * {@link ExtractedRecordStore#carryCorrections} calls it once the corrections are carried: a new record matches by
     * the old record's (type key, name fingerprint), its corrected one when it was corrected (the carried correction
     * gives the new record the same), the nearest first page winning, then the lowest ordinal. A link with no match
     * stays on the old record, shown removed, and goes with its row. A deleting artifact's link is not moved. An
     * override is not moved when that would give one record two marks of artifacts of the same campaign; a relation is
     * not moved when its artifact is related to the matching record already, and is then deleted, since the relation
     * exists. Answers how many links moved.
     */
    static int carry(RecordStore store, RecordRelationStore relations, UUID fromVersionId, UUID toVersionId,
                     String kind, Function<RecordRow, Optional<RecordRow>> matchOf) {
        Map<UUID, RecordRow> old = new LinkedHashMap<>();
        store.ofVersion(fromVersionId).forEach(row -> old.put(row.id(), row));
        List<RecordRelationRow> marks = old.isEmpty() ? List.of()
                : relations.to(old.keySet(), kind).stream()
                .sorted(Comparator.comparing(RecordRelationRow::createdAt).thenComparing(RecordRelationRow::id))
                .toList();
        if (marks.isEmpty()) {
            return 0;
        }
        // Live artifacts only: a deleting artifact's mark has no effect, so it is never moved (nor takes a match).
        Map<UUID, UUID> campaignOf = new HashMap<>();
        store.findArtifacts(marks.stream().map(RecordRelationRow::fromRecordId).collect(Collectors.toSet()),
                RecordStore.Reach.LIVE).forEach(row -> campaignOf.put(row.id(), row.campaignId()));
        boolean overrides = RecordRelationRow.OVERRIDES.equals(kind);
        Set<String> taken = new HashSet<>();
        int moved = 0;
        for (RecordRelationRow mark : marks) {
            Optional<RecordRow> match = matchOf.apply(old.get(mark.toRecordId()));
            UUID campaign = campaignOf.get(mark.fromRecordId());
            if (match.isEmpty() || campaign == null) {
                continue;
            }
            UUID to = match.get().id();
            if (overrides && !taken.add(campaign + "/" + to)) {
                continue;
            }
            if (!overrides && !relations.between(mark.fromRecordId(), to, kind).isEmpty()) {
                relations.delete(mark.id());
                continue;
            }
            if (relations.retarget(mark.id(), to)) {
                moved++;
            }
        }
        return moved;
    }

    /** A name folded for the filter: decomposed, its marks dropped, lower case. */
    static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private record Named(RecordRow row, String name) {
    }

    /** A relation target before it is paged: an extracted record with its version, or an artifact (no version). */
    private record Candidate(UUID id, String name, String recordType, Tier tier, UUID versionId) {
    }

    private static UUID requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("knowledge.transaction_required");
        }
        return OwnerContext.require();
    }

    private <T> T reading(Supplier<T> read) {
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }
}

package io.github.gmshield.scope.service;

import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.service.ExtractedRecords;
import io.github.gmshield.materials.service.RecordTypeKeys;
import io.github.gmshield.materials.service.ScopeReads;
import io.github.gmshield.materials.service.TermMatches;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Vault;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The one resolver of what a campaign may use (Story 2.2, AD-9). Everything here acts for the owner of the work
 * ({@link OwnerContext}), in one read transaction (the caller's, when there is one).
 *
 * <ul>
 *   <li>{@link #resolve}: a campaign of the owner and its {@link ScopeSettings} become a {@link ResolvedScope}. The
 *       campaign tier is the {@code ready} version of each material of the campaign; the system tier the {@code ready}
 *       version of each material that belongs to the campaign's game system (a version already in the campaign tier
 *       is left out of it); from Story 3.5 the other tier every other {@code ready} version of the owner (the
 *       materials of the GM's other campaigns, archived ones included, and other systems). A tier switched off has no
 *       source, and a material switched off ({@link ScopeSettings#materialsOff}) is left out of whichever tier it
 *       falls in. {@code pending} names the in-scope passages that are not
 *       indexed: their index change has not ended, or its job failed (they stay in the keyword fallback; the unended
 *       runs' passages come first). A campaign that is not found (unknown, or another owner's) is
 *       {@code request.not_found} (404). {@link #resolveSources} is the same without {@code pending} (not computed,
 *       empty), for the reads that check only sources and tiers: an answer's passages and references.</li>
 *   <li>{@link #indexing}: how many passages of the campaign's scope under {@code settings} are still waiting for
 *       search, over the same sources as {@link #resolve}: the distinct passages of the in-scope versions covered by
 *       an index change that has not ended. Counted, never read, and kept out of {@link #resolve} so a question is not
 *       slowed. Empty when nothing in scope is pending; an unknown campaign is {@code request.not_found} (404), as for
 *       {@link #resolve}. {@link #status} answers it with whether the scope has a source (an allowed source: a pending
 *       passage always belongs to one), and with the passages of the in-scope versions whose index change failed, and
 *       whether a retry remains ({@link IndexingFailure}; null when none failed), from the same one look-up of the
 *       sources; the line {@code scope.indexing} is at DEBUG, since the GM's notice asks for it every few seconds.</li>
 *   <li>{@link #passages}: passages by id within a resolved scope, decrypted and labelled with their tier, in the
 *       order asked, each once. Every id is checked first: when any is not a passage of an allowed source that is
 *       still {@code ready} (unknown, another owner's, of a version no longer {@code ready}, or out of every tier), the
 *       whole request is refused with {@value #REFUSED_CODE} (403) before anything is decrypted, and the refusal names
 *       no id.</li>
 *   <li>{@link #questionInput} (Story 2.3): the input of an interactive job that carries a question, built from a
 *       resolved scope only: {@code {question, owner, modelKnowledge, tiers: [{tier, sources, pending}], tokenKey}}
 *       ({@code modelKnowledge}, Story 3.8: the scope's settings' switch, frozen with the turn), the tiers in
 *       order (campaign, system, then other from Story 3.5, each always present, a tier with no source with empty
 *       lists), {@code pending}
 *       the first {@value #MAX_PENDING} of the tier's pending passages in order (the worker reads them within a time
 *       budget; the total and how many were left out are logged as counts), and {@code tokenKey} Base64 of the
 *       owner's {@link Vault#ownerTokenKey}, whose buffer is zeroed once encoded. The question is 1 to
 *       {@value #MAX_QUESTION} characters (code points), else {@code IllegalArgumentException}. Provider, model and
 *       tier join it with Story 2.10.</li>
 *   <li>{@link #references} (Story 2.5): where cited passages stand within a resolved scope, in the order cited, each
 *       once. A citation is model output, so an id that is not a passage of an allowed source that is still
 *       {@code ready} (unknown, another owner's, out of scope) is dropped, never refused; the line carries the
 *       dropped count only. Each place carries the tier of its source in that scope (Story 3.7), so a reference
 *       keeps the tier it had when it was cited. No passage text is decrypted.</li>
 *   <li>{@link #removedVersions} (Story 2.5): which of these versions are removed for the owner (gone,
 *       {@code discarded}, pending removal, or of a material that is gone), so a stored reference shows as removed.</li>
 *   <li>{@link #referencePage} and {@link #referencePicture} (Story 2.7): one page of the version a source reference
 *       stored with a turn of the campaign names, and its picture: the ground "a source reference stored with a turn of
 *       that campaign" (AD-9), which the caller has checked against the turn. The campaign must be the owner's. The
 *       version is served even when a newer one was accepted since, never once it is removed
 *       ({@link #removedVersions}); any page from 1 to its page total, one per call. Empty for anything else.</li>
 *   <li>Story 3.5: {@link #members} lists the materials of every tier, whatever its switch, for the settings view
 *       ({@link CampaignScopes}); {@link #sourcePage} and {@link #sourcePicture} serve a page of an allowed source of a
 *       resolved scope, the ground "the current scope".</li>
 *   <li>Story 3.9 (search): {@link #resolve(UUID, ScopeSettings, ScopeFilter)} and
 *       {@link #resolveSources(UUID, ScopeSettings, ScopeFilter)} narrow the allowed sources by a {@link ScopeFilter}
 *       before anything else is read, so a narrowed scope's pending passages are those of its own sources and a filter
 *       never adds a source. {@link #searchInput} is {@link #questionInput}'s shape with {@code query} (1 to
 *       {@value #MAX_QUERY} code points) in place of {@code question}. {@link #results} reads search results within a
 *       resolved scope, in the order given, each once: an id that is not a passage of an allowed source still
 *       {@code ready} is dropped, never refused (a result stored earlier may have left the scope since); each carries
 *       its place, its material's name, its text and its tier.</li>
 *   <li>Story 4.3 part B (records in search): {@link #searchInput(ResolvedScope, String, String)} adds a record-type
 *       filter ({@code recordType}, a record type key) when the search has one. {@link #records} reads records by id
 *       within a resolved scope, in the order given, each once: a record whose version is not an allowed source still
 *       {@code ready} (unknown, another owner's, out of scope) is dropped, never refused; each carries its first page's
 *       place (the section path of its first passage there), the ids of its passages on that page, its type's key, its
 *       name and its tier; its line {@code scope.records_read} counts the ids asked, the records kept and the ids that
 *       are no allowed record ({@code notRecords}: a search's passage ids among them, or a record out of the scope).
 *       {@link #namedRecords} finds the records of a resolved scope whose name equals a query (an equality on a keyed
 *       fingerprint, {@link ExtractedRecords#named}), of one type when one is given, leaving out those of a records run
 *       not indexed yet ({@link ScopeReads#unindexedRecordRuns}), so a record is found by name only once the index can
 *       find it too; a record the worker returned (it is in the index, whatever its run says, for instance a run cut
 *       again by a reconcile) is never left out.</li>
 *   <li>Story 4.3 part C (records in answers): {@link #recordContents} reads records by id within a resolved scope with
 *       their names and fields, as {@link #passages} reads passages: in the order asked, each once, and the whole
 *       request refused with {@value #REFUSED_CODE} (403), before anything is decrypted, when any id is not a record of
 *       an allowed source still {@code ready}. {@link #citedRecords} reads an answer's cited records the same way, in
 *       one read, but drops (never refuses) an id that is not one, as {@link #references} drops a cited passage.</li>
 *   <li>Story 4.4 part B (approved record types): a record stores its type's key, and every read of records here
 *       ({@link #records}, {@link #namedRecords}, {@link #recordContents}, {@link #citedRecords}) keeps only those
 *       whose key is a record type of the campaign's system ({@link ResolvedScope#recordTypes}, read through
 *       {@link RecordTypeKeys} when the scope is resolved): in a system that never approved a type, its records stay
 *       passages, so a record id of another key is not a record of the scope ({@link #recordContents} refuses it).
 *       {@link #questionInput} and {@link #searchInput} carry those keys as {@code recordTypes}, and the worker filters
 *       the index's record points to them. A record-type filter may name any key ({@link #recordTypeKey}).</li>
 *   <li>Story 4.5 part B (a deleted record): no read here answers a record the GM deleted, except
 *       {@link #recordContents} (an answer's read of the records its search found), which still serves a deleted one
 *       whatever its index point ({@link ExtractedRecords.Reach#ALL}), so an answer whose search found the point (just
 *       before its removal, or a point written back later) does not fail; a cited one is dropped. {@link #deletedRecords} names the owner's deleted records among
 *       some ids, so a library search that finds one drops it instead of failing.</li>
 *   <li>Story 4.6 (term resolution): {@link #resolveTerms} matches a question's spans of 1 to 6 words against the
 *       campaign's system's ontology and the scope's record names ({@link TermMatches}), each an equality on a keyed
 *       fingerprint, and answers the record type keys, names and in-scope record ids it found ({@link ResolvedTerms}).
 *       No model, no text search, no definition.</li>
 *   <li>Story 5.1 part B (artifacts): when the campaign tier is on, {@link #resolve} also reads the ids of the
 *       campaign's artifacts ({@link CampaignArtifacts}, a port the {@code artifacts} module implements) into
 *       {@link ResolvedScope#artifacts}, never among its allowed sources. {@link #questionInput} alone lists them, after
 *       the campaign tier's sources; {@link #searchInput} never does, nor does any read of passages, of search results,
 *       of a records list, of names or of terms. {@link #recordContents} and {@link #citedRecords} read an id of that
 *       set through the port, as a campaign-tier record of the scope (page 1, no section path, its content handed as its
 *       last field by the job), when its type key is one of the campaign's system's; any other id as before. An artifact
 *       is found by a question only once its index job has ended, since the worker finds it through the index.
 *       {@link #status} counts the campaign's artifacts as a source when the campaign tier is on.</li>
 * </ul>
 *
 * Lines carry ids and counts, never a text, a section title or a question.
 */
@Service
public class Scopes {

    public static final String CATEGORY = "scope";
    static final String REFUSED_CODE = "scope.passage_refused";
    static final ApiProblem PASSAGE_REFUSED = ApiProblem.of(403, CATEGORY, "passage_refused",
            "Passage outside the scope");
    /** The longest question, in characters (code points). */
    public static final int MAX_QUESTION = 4000;
    /** The longest search query, in characters (code points) (Story 3.9). */
    public static final int MAX_QUERY = 500;
    /**
     * The most pending passages of a tier a question input lists, in order: the worker reads no more, and reads them a
     * page at a time within its time budget.
     */
    public static final int MAX_PENDING = 5_000;
    /** The most records a question's term resolution names (Story 4.6). */
    public static final int MAX_TERM_RECORDS = 100;
    /**
     * The most records a term resolution's records query reads before the scope leaves out those it cannot place and
     * the first {@value #MAX_TERM_RECORDS} are kept, campaign tier first.
     */
    static final int TERM_RECORDS_READ = 1_000;
    private static final Logger log = LoggerFactory.getLogger(Scopes.class);

    private final Library library;
    private final ScopeReads reads;
    private final ExtractedRecords records;
    private final RecordTypeKeys recordTypeKeys;
    private final TermMatches termMatches;
    private final CampaignArtifacts artifacts;
    private final Vault vault;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    Scopes(Library library, ScopeReads reads, ExtractedRecords records, RecordTypeKeys recordTypeKeys,
           TermMatches termMatches, CampaignArtifacts artifacts, Vault vault,
           ObjectProvider<PlatformTransactionManager> transactions) {
        this.library = library;
        this.reads = reads;
        this.records = records;
        this.recordTypeKeys = recordTypeKeys;
        this.termMatches = termMatches;
        this.artifacts = artifacts;
        this.vault = vault;
        this.transactions = transactions;
    }

    /** The owner's campaign's scope under {@code settings}, as the class says. */
    public ResolvedScope resolve(UUID campaignId, ScopeSettings settings) {
        return resolve(campaignId, settings, true);
    }

    /**
     * The owner's campaign's scope under {@code settings} without its pending passages: the same sources and tiers, and
     * the same refusals, as {@link #resolve}, but {@code pending} is not computed (empty), so no pending passage id is
     * read. For reads that check only the allowed sources and tiers ({@link #passages}, {@link #references}), never for
     * a {@link #questionInput}.
     */
    public ResolvedScope resolveSources(UUID campaignId, ScopeSettings settings) {
        return resolve(campaignId, settings, ScopeFilter.NONE, false);
    }

    /**
     * {@link #resolve(UUID, ScopeSettings)} narrowed by {@code filter} (Story 3.9): only the allowed sources the filter
     * keeps, and only their pending passages.
     */
    public ResolvedScope resolve(UUID campaignId, ScopeSettings settings, ScopeFilter filter) {
        return resolve(campaignId, settings, filter, true);
    }

    /** {@link #resolveSources(UUID, ScopeSettings)} narrowed by {@code filter} (Story 3.9). */
    public ResolvedScope resolveSources(UUID campaignId, ScopeSettings settings, ScopeFilter filter) {
        return resolve(campaignId, settings, filter, false);
    }

    private ResolvedScope resolve(UUID campaignId, ScopeSettings settings, boolean withPending) {
        return resolve(campaignId, settings, ScopeFilter.NONE, withPending);
    }

    private ResolvedScope resolve(UUID campaignId, ScopeSettings settings, ScopeFilter filter, boolean withPending) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(filter, "filter");
        UUID owner = OwnerContext.require();
        return reading(() -> {
            Library.Campaign campaign = campaignOf(campaignId);
            Map<Tier, List<ScopeReads.ReadySource>> allowed = narrowed(allowed(campaign, settings), filter);
            Map<Tier, List<UUID>> sources = new EnumMap<>(Tier.class);
            Map<Tier, List<UUID>> pending = new EnumMap<>(Tier.class);
            Map<UUID, UUID> materialOf = new HashMap<>();
            for (Tier tier : Tier.values()) {
                List<UUID> ids = allowed.get(tier).stream().map(ScopeReads.ReadySource::versionId).toList();
                allowed.get(tier).forEach(source -> materialOf.put(source.versionId(), source.materialId()));
                sources.put(tier, ids);
                pending.put(tier, withPending ? pendingOf(ids) : List.of());
            }
            // Story 5.1 part B: the campaign's artifacts, with the campaign tier only; never an allowed source.
            Set<UUID> ofCampaign = settings.on(Tier.CAMPAIGN) ? artifacts.ids(campaign.id()) : Set.of();
            ResolvedScope scope = new ResolvedScope(owner, campaign.id(), settings, sources, pending, materialOf,
                    recordTypeKeys.keysOf(campaign.system().id()), ofCampaign);
            log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaign.id())
                    .addKeyValue("campaignSources", sources.get(Tier.CAMPAIGN).size())
                    .addKeyValue("artifacts", ofCampaign.size())
                    .addKeyValue("systemSources", sources.get(Tier.SYSTEM).size())
                    .addKeyValue("otherSources", sources.get(Tier.OTHER).size())
                    .addKeyValue("materialsOff", settings.materialsOff().size())
                    .addKeyValue("pending", scope.pending().size())
                    .addKeyValue("pendingRead", withPending)
                    .addKeyValue("filtered", !ScopeFilter.NONE.equals(filter)).log("scope.resolved");
            return scope;
        });
    }

    /** How many passages of the owner's campaign's scope under {@code settings} wait for search, as the class says. */
    public Optional<Indexing> indexing(UUID campaignId, ScopeSettings settings) {
        return Optional.ofNullable(status(campaignId, settings).indexing());
    }

    /**
     * Whether the owner's campaign's scope under {@code settings} has a source, how many of its passages wait for
     * search, and how many could not be prepared for search, from one look-up of its sources and without reading a
     * pending passage's id ({@link #indexing}).
     */
    public ScopeStatus status(UUID campaignId, ScopeSettings settings) {
        Objects.requireNonNull(settings, "settings");
        UUID owner = OwnerContext.require();
        return reading(() -> {
            Library.Campaign campaign = campaignOf(campaignId);
            List<UUID> sources = new ArrayList<>();
            allowed(campaign, settings).values()
                    .forEach(tier -> tier.forEach(source -> sources.add(source.versionId())));
            Indexing indexing = sources.isEmpty() ? null : reads.indexing(sources)
                    .map(count -> new Indexing(count.remaining())).orElse(null);
            IndexingFailure failed = sources.isEmpty() ? null : reads.failed(sources)
                    .map(count -> new IndexingFailure(count.passages(), count.retrying())).orElse(null);
            // Story 5.1 part B: a campaign whose only sources are its artifacts has sources too.
            boolean hasSources = !sources.isEmpty()
                    || settings.on(Tier.CAMPAIGN) && !artifacts.ids(campaign.id()).isEmpty();
            log.atDebug().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaign.id())
                    .addKeyValue("sources", sources.size()).addKeyValue("hasSources", hasSources)
                    .addKeyValue("remaining", indexing == null ? 0 : indexing.remaining())
                    .addKeyValue("failed", failed == null ? 0 : failed.passages())
                    .addKeyValue("retrying", failed != null && failed.retrying()).log("scope.indexing");
            return new ScopeStatus(hasSources, indexing, failed);
        });
    }

    /** The owner's campaign with this id; {@code request.not_found} (404) for an unknown one or another owner's. */
    private Library.Campaign campaignOf(UUID campaignId) {
        Optional<Library.Campaign> found = campaignId == null ? Optional.empty() : library.campaign(campaignId);
        if (found.isEmpty()) {
            log.atInfo().addKeyValue("campaignId", campaignId).addKeyValue("reason", "request.not_found")
                    .log("scope.campaign_not_found");
            throw new ApiException(ApiProblem.forStatus(404));
        }
        return found.get();
    }

    /**
     * The members of each tier, whatever its switch (Story 3.5): the campaign tier the {@code ready} versions of the
     * campaign's materials; the system tier those of its system's materials, minus the campaign tier's when
     * {@code campaignOn} (with the campaign tier off, a version of both stays in the system tier, as before Story 3.5);
     * the other tier (read only when {@code withOther}, else empty) every other {@code ready} version of the owner,
     * never one of the campaign's or its system's. Each oldest first.
     */
    private Map<Tier, List<ScopeReads.ReadySource>> members(Library.Campaign campaign, boolean withOther,
                                                            boolean campaignOn) {
        Map<Tier, List<ScopeReads.ReadySource>> members = new EnumMap<>(Tier.class);
        List<ScopeReads.ReadySource> ofCampaign = reads.readySourcesOfCampaign(campaign.id());
        Set<UUID> inCampaign = new HashSet<>();
        ofCampaign.forEach(source -> inCampaign.add(source.versionId()));
        List<ScopeReads.ReadySource> ofSystem = reads.readySourcesOfSystem(campaign.system().id()).stream()
                .filter(source -> !campaignOn || !inCampaign.contains(source.versionId())).toList();
        Set<UUID> taken = new HashSet<>(inCampaign);
        ofSystem.forEach(source -> taken.add(source.versionId()));
        List<ScopeReads.ReadySource> other = withOther
                ? reads.readySourcesOfOwner().stream().filter(source -> taken.add(source.versionId())).toList()
                : List.of();
        members.put(Tier.CAMPAIGN, ofCampaign);
        members.put(Tier.SYSTEM, ofSystem);
        members.put(Tier.OTHER, other);
        return members;
    }

    /**
     * The allowed sources of each tier under {@code settings}: a tier switched off has none, and a material switched off
     * is left out.
     */
    private Map<Tier, List<ScopeReads.ReadySource>> allowed(Library.Campaign campaign, ScopeSettings settings) {
        Map<Tier, List<ScopeReads.ReadySource>> allowed = new EnumMap<>(Tier.class);
        members(campaign, settings.otherTier(), settings.campaignTier()).forEach((tier, members) ->
                allowed.put(tier, settings.on(tier)
                        ? members.stream().filter(source -> settings.materialOn(source.materialId())).toList()
                        : List.of()));
        return allowed;
    }

    /** The allowed sources the filter keeps, by tier (Story 3.9): never one more. */
    private static Map<Tier, List<ScopeReads.ReadySource>> narrowed(Map<Tier, List<ScopeReads.ReadySource>> allowed,
                                                                   ScopeFilter filter) {
        if (ScopeFilter.NONE.equals(filter)) {
            return allowed;
        }
        Map<Tier, List<ScopeReads.ReadySource>> kept = new EnumMap<>(Tier.class);
        allowed.forEach((tier, sources) -> kept.put(tier, sources.stream()
                .filter(source -> filter.keeps(tier, source.materialId())).toList()));
        return kept;
    }

    /** A material of a campaign's scope (Story 3.5): its id, its name and its tier. */
    record Member(UUID materialId, String name, Tier tier) {
        @Override
        public String toString() {
            return "Member[materialId=" + materialId + ", tier=" + tier + "]";
        }
    }

    /**
     * The materials of every tier of the owner's campaign, whatever the tier's switch (Story 3.5), each once: in tier
     * order, then newest first. Each has a {@code ready} version. An unknown campaign is {@code request.not_found}.
     */
    List<Member> members(UUID campaignId) {
        OwnerContext.require();
        return reading(() -> {
            Library.Campaign campaign = campaignOf(campaignId);
            Map<Tier, List<ScopeReads.ReadySource>> members = members(campaign, true, true);
            Map<UUID, Tier> tierOf = new LinkedHashMap<>();
            for (Tier tier : Tier.values()) {
                members.get(tier).forEach(source -> tierOf.putIfAbsent(source.materialId(), tier));
            }
            Comparator<ScopeReads.MaterialLabel> newest = Comparator.comparing(ScopeReads.MaterialLabel::createdAt,
                    Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(ScopeReads.MaterialLabel::id, Comparator.reverseOrder());
            return reads.materialLabels(tierOf.keySet()).stream()
                    .sorted(Comparator.comparing((ScopeReads.MaterialLabel label) -> tierOf.get(label.id()))
                            .thenComparing(newest))
                    .map(label -> new Member(label.id(), label.name(), tierOf.get(label.id()))).toList();
        });
    }

    /** Page {@code page} of an allowed source of {@code scope} (Story 3.5: the ground "the current scope"), else empty. */
    Optional<SourcePage> sourcePage(ResolvedScope scope, UUID versionId, int page) {
        requireOwner(scope);
        return reading(() -> scope.materialOf(versionId)
                .flatMap(material -> reads.versionPage(material, versionId, page)).map(Scopes::page));
    }

    /** The picture of that page, as {@link #sourcePage} finds it; empty also for a page with no picture. */
    Optional<SourcePicture> sourcePicture(ResolvedScope scope, UUID versionId, int page) {
        requireOwner(scope);
        return reading(() -> scope.materialOf(versionId)
                .flatMap(material -> reads.versionPicture(material, versionId, page)).map(Scopes::picture));
    }

    private static void requireOwner(ResolvedScope scope) {
        if (!OwnerContext.require().equals(scope.ownerId())) {
            throw new IllegalStateException("scope.owner_mismatch");
        }
    }

    /** The passages with these ids within {@code scope}, as the class says. */
    public List<ScopedPassage> passages(ResolvedScope scope, List<UUID> ids) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(ids, "ids");
        UUID owner = OwnerContext.require();
        if (!owner.equals(scope.ownerId()) || ids.stream().anyMatch(Objects::isNull)) {
            throw refused(scope, ids.size());
        }
        Set<UUID> allowed = scope.allowedSources();
        Optional<List<ScopeReads.Passage>> read = reading(() -> reads.passages(ids, allowed));
        if (read.isEmpty()) {
            throw refused(scope, ids.size());
        }
        List<ScopedPassage> passages = read.get().stream().map(passage -> new ScopedPassage(passage.id(),
                scope.tierOf(passage.versionId()).orElseThrow(() -> new IllegalStateException("scope.tier_missing")),
                passage.text(), passage.sectionPath(), passage.page())).toList();
        log.atInfo().addKeyValue("campaignId", scope.campaignId()).addKeyValue("requested", ids.size())
                .addKeyValue("passages", passages.size()).log("scope.passages_read");
        return passages;
    }

    /** Where the cited passages with these ids stand within {@code scope}, as the class says. */
    public List<CitedPlace> references(ResolvedScope scope, List<UUID> ids) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(ids, "ids");
        if (!OwnerContext.require().equals(scope.ownerId())) {
            throw new IllegalStateException("scope.owner_mismatch");
        }
        List<UUID> cited = ids.stream().filter(Objects::nonNull).distinct().toList();
        List<CitedPlace> places = reading(() -> reads.places(cited, scope.allowedSources())).stream()
                .map(place -> new CitedPlace(place.passageId(), place.versionId(), place.materialId(),
                        place.materialName(), place.sectionPath(), place.page(), scope.tierOf(place.versionId())
                        .orElseThrow(() -> new IllegalStateException("scope.tier_missing")))).toList();
        log.atInfo().addKeyValue("campaignId", scope.campaignId()).addKeyValue("cited", cited.size())
                .addKeyValue("kept", places.size()).addKeyValue("dropped", cited.size() - places.size())
                .log("scope.references_read");
        return places;
    }

    /** The search results with these ids within {@code scope}, as the class says (Story 3.9). */
    public List<ScopedResult> results(ResolvedScope scope, List<UUID> ids) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(ids, "ids");
        if (!OwnerContext.require().equals(scope.ownerId())) {
            throw new IllegalStateException("scope.owner_mismatch");
        }
        List<UUID> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        Set<UUID> allowed = scope.allowedSources();
        List<ScopedResult> results = wanted.isEmpty() || allowed.isEmpty() ? List.of() : reading(() -> {
            List<ScopeReads.Place> places = reads.places(wanted, allowed);
            // Every place left is a passage of an allowed source still ready, in this transaction: its text reads.
            Map<UUID, ScopeReads.Passage> texts = new HashMap<>();
            reads.passages(places.stream().map(ScopeReads.Place::passageId).toList(), allowed)
                    .orElseThrow(() -> new IllegalStateException("scope.result_unreadable"))
                    .forEach(passage -> texts.put(passage.id(), passage));
            return places.stream().map(place -> new ScopedResult(place.passageId(), place.versionId(),
                    place.materialId(), place.materialName(), place.sectionPath(), place.page(),
                    texts.get(place.passageId()).text(), scope.tierOf(place.versionId())
                    .orElseThrow(() -> new IllegalStateException("scope.tier_missing")))).toList();
        });
        log.atInfo().addKeyValue("campaignId", scope.campaignId()).addKeyValue("requested", wanted.size())
                .addKeyValue("kept", results.size()).addKeyValue("dropped", wanted.size() - results.size())
                .log("scope.results_read");
        return results;
    }

    /** The records with these ids within {@code scope}, as the class says (Story 4.3 part B). */
    public List<ScopedRecord> records(ResolvedScope scope, List<UUID> ids) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(ids, "ids");
        requireOwner(scope);
        List<UUID> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        List<ScopedRecord> found = wanted.isEmpty() || scope.allowedSources().isEmpty() ? List.of()
                : reading(() -> placed(scope, records.located(wanted)));
        log.atInfo().addKeyValue("campaignId", scope.campaignId()).addKeyValue("requested", wanted.size())
                .addKeyValue("records", found.size()).addKeyValue("notRecords", wanted.size() - found.size())
                .log("scope.records_read");
        return found;
    }

    /**
     * The ids among {@code ids} of the owner's records the GM deleted (Story 4.5 part B), whatever the scope: a library
     * search drops them from what its worker found. Nothing is decrypted.
     */
    public Set<UUID> deletedRecords(ResolvedScope scope, Collection<UUID> ids) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(ids, "ids");
        requireOwner(scope);
        Set<UUID> deleted = ids.isEmpty() ? Set.of() : reading(() -> records.deleted(ids));
        log.atInfo().addKeyValue("campaignId", scope.campaignId()).addKeyValue("requested", ids.size())
                .addKeyValue("deleted", deleted.size()).log("scope.deleted_records_read");
        return deleted;
    }

    /**
     * The records of {@code scope} whose name equals {@code name}, of the type key {@code recordType} when it is not
     * null, as the class says (Story 4.3 part B); by id. A record among {@code returned} (the ids the worker found) is
     * kept even when its records run is not indexed.
     */
    public List<ScopedRecord> namedRecords(ResolvedScope scope, String name, String recordType,
                                           Collection<UUID> returned) {
        Objects.requireNonNull(scope, "scope");
        requireOwner(scope);
        Set<UUID> allowed = scope.allowedSources();
        if (name == null || name.isBlank() || allowed.isEmpty()) {
            return List.of();
        }
        int[] unindexed = {0};
        List<ScopedRecord> found = reading(() -> {
            List<ExtractedRecords.Located> named = records.named(allowed, name).stream()
                    .filter(record -> recordType == null || recordType.equals(record.type())).toList();
            List<ScopeReads.RecordRun> runs = reads.unindexedRecordRuns(named.stream()
                    .map(ExtractedRecords.Located::versionId).distinct().toList());
            Set<UUID> byWorker = Set.copyOf(returned);
            List<ExtractedRecords.Located> indexed = named.stream().filter(record -> byWorker.contains(record.id())
                    || runs.stream().noneMatch(run -> run.covers(record.versionId(), record.firstPage()))).toList();
            unindexed[0] = named.size() - indexed.size();
            return placed(scope, indexed);
        });
        log.atInfo().addKeyValue("campaignId", scope.campaignId()).addKeyValue("found", found.size())
                .addKeyValue("unindexed", unindexed[0]).addKeyValue("recordType", loggedType(recordType))
                .log("scope.named_records_read");
        return found;
    }

    /**
     * The records with these ids within {@code scope}, with their names and fields, as the class says (Story 4.3 part
     * C): every id is checked first, and when any is not a record of an allowed source still {@code ready} (unknown,
     * another owner's, of a version no longer {@code ready}, or out of every tier), the whole request is refused with
     * {@value #REFUSED_CODE} (403) before anything is decrypted, and the refusal names no id.
     */
    public List<ScopedRecordContent> recordContents(ResolvedScope scope, List<UUID> ids) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(ids, "ids");
        if (!OwnerContext.require().equals(scope.ownerId()) || ids.stream().anyMatch(Objects::isNull)) {
            throw refused(scope, ids.size());
        }
        List<UUID> wanted = ids.stream().distinct().toList();
        Optional<List<ScopedRecordContent>> read = wanted.isEmpty() ? Optional.of(List.of())
                : reading(() -> contents(scope, wanted, true));
        if (read.isEmpty()) {
            throw refused(scope, ids.size());
        }
        log.atInfo().addKeyValue("campaignId", scope.campaignId()).addKeyValue("requested", ids.size())
                .addKeyValue("records", read.get().size()).log("scope.record_contents_read");
        return read.get();
    }

    /**
     * The cited records with these ids within {@code scope}, with their names and fields (Story 4.3 part C, an answer's
     * references): as {@link #recordContents}, but a citation is model output, so an id that is not a record of an
     * allowed source still {@code ready} (unknown, another owner's, out of scope, or gone since it was handed) is
     * dropped, never refused, in one read; the line carries the dropped count only.
     */
    public List<ScopedRecordContent> citedRecords(ResolvedScope scope, List<UUID> ids) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(ids, "ids");
        requireOwner(scope);
        List<UUID> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        List<ScopedRecordContent> found = wanted.isEmpty()
                || scope.allowedSources().isEmpty() && scope.artifacts().isEmpty() ? List.of()
                : reading(() -> contents(scope, wanted, false)).orElseThrow();
        log.atInfo().addKeyValue("campaignId", scope.campaignId()).addKeyValue("cited", wanted.size())
                .addKeyValue("kept", found.size()).addKeyValue("dropped", wanted.size() - found.size())
                .log("scope.cited_records_read");
        return found;
    }

    /**
     * A question's terms resolved within {@code scope} (Story 4.6, internal contract 1.23.0): the question's spans
     * ({@link TermSpans}: 1 to {@value TermSpans#MAX_WORDS} contiguous words in their unique form) matched against what
     * the campaign's system's ontology holds ({@link TermMatches#ontology}: the core types' names in pt-BR and en, the
     * approved types' names, the terms' names and aliases) and against the names of the scope's records
     * ({@link TermMatches#records}), each an equality on a keyed fingerprint. The record type keys are those of the
     * campaign's system ({@link ResolvedScope#recordTypes}); the records are those of an allowed source still
     * {@code ready}, of one of those keys, placed as {@link #records} places them, campaign tier first, then system, then
     * other, each by id, at most {@value #MAX_TERM_RECORDS}; the names are the matched terms' names and aliases, then the
     * records' names, each once. A record the GM deleted is never named. Its line {@code scope.terms_resolved} carries
     * counts only.
     */
    public ResolvedTerms resolveTerms(ResolvedScope scope, String question) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(question, "question");
        requireOwner(scope);
        List<String> spans = TermSpans.of(question);
        ResolvedTerms resolved = spans.isEmpty() ? ResolvedTerms.NONE : reading(() -> {
            Library.Campaign campaign = campaignOf(scope.campaignId());
            TermMatches.Ontological ontology = termMatches.ontology(campaign.system().id(), spans);
            Set<String> keys = scope.recordTypes();
            List<String> types = ontology.recordTypes().stream().filter(keys::contains).sorted().toList();
            List<ScopedRecord> named = scope.allowedSources().isEmpty() || keys.isEmpty() ? List.of()
                    : termRecords(scope, termMatches.records(scope.allowedSources(), keys, spans, TERM_RECORDS_READ));
            Set<String> names = new LinkedHashSet<>(ontology.names());
            named.forEach(record -> names.add(record.name()));
            return new ResolvedTerms(types, List.copyOf(names), named.stream().map(ScopedRecord::recordId).toList());
        });
        log.atInfo().addKeyValue("campaignId", scope.campaignId()).addKeyValue("spans", spans.size())
                .addKeyValue("recordTypes", resolved.recordTypes().size())
                .addKeyValue("names", resolved.names().size()).addKeyValue("records", resolved.records().size())
                .log("scope.terms_resolved");
        return resolved;
    }

    /**
     * The first {@value #MAX_TERM_RECORDS} of these records placed within {@code scope} ({@link #located}), campaign tier
     * first, then by id, with their names. Runs in the caller's read transaction.
     */
    private List<ScopedRecord> termRecords(ResolvedScope scope, List<ExtractedRecords.Located> named) {
        List<Placed> kept = located(scope, named).stream()
                .sorted(Comparator.comparing(Placed::tier).thenComparing(one -> one.record().id()))
                .limit(MAX_TERM_RECORDS).toList();
        if (kept.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> names = records.names(kept.stream().map(one -> one.record().id()).toList());
        return kept.stream().filter(one -> names.containsKey(one.record().id()))
                .map(one -> one.scoped(names.get(one.record().id()))).toList();
    }

    /**
     * The records with these distinct ids placed within {@code scope} and decrypted, in the order given; with
     * {@code refuse}, empty as soon as any id is not one (before anything is decrypted), else those ids are left out.
     * Runs in the caller's read transaction.
     */
    private Optional<List<ScopedRecordContent>> contents(ResolvedScope scope, List<UUID> wanted, boolean refuse) {
        // Story 5.1 part B: an id of the campaign's artifacts is read through the port, never placed as a record.
        Set<UUID> ofCampaign = scope.artifacts();
        List<UUID> artifactIds = wanted.stream().filter(ofCampaign::contains).toList();
        List<UUID> extracted = wanted.stream().filter(id -> !ofCampaign.contains(id)).toList();
        ExtractedRecords.Reach reach = refuse ? ExtractedRecords.Reach.ALL : ExtractedRecords.Reach.LIVE;
        List<Placed> placed = extracted.isEmpty() ? List.of() : located(scope, records.located(extracted, reach));
        if (refuse && placed.size() != extracted.size()) {
            return Optional.empty();
        }
        Map<UUID, ScopedRecordContent> found = new HashMap<>();
        if (!artifactIds.isEmpty()) {
            Set<String> keys = scope.recordTypes();
            artifacts.read(scope.campaignId(), artifactIds).stream()
                    .filter(artifact -> keys.contains(artifact.recordType()))
                    .forEach(artifact -> found.put(artifact.id(), new ScopedRecordContent(ScopedRecord.artifact(
                            artifact.id(), artifact.recordType(), artifact.name(), artifact.version()),
                            artifact.fields(), artifact.content())));
            if (refuse && found.size() != artifactIds.size()) {
                return Optional.empty();
            }
        }
        Map<UUID, ExtractedRecords.Content> contents = new HashMap<>();
        if (!placed.isEmpty()) {
            records.read(placed.stream().map(one -> one.record().id()).toList(), reach)
                    .forEach(content -> contents.put(content.id(), content));
        }
        for (Placed one : placed) {
            ExtractedRecords.Content content = contents.get(one.record().id());
            if (content == null && refuse) {
                return Optional.empty();
            }
            if (content != null) {
                found.put(one.record().id(), new ScopedRecordContent(one.scoped(content.name()), content.fields()
                        .stream().map(field -> new ScopedRecordContent.Field(field.name(), field.value())).toList()));
            }
        }
        return Optional.of(wanted.stream().map(found::get).filter(Objects::nonNull).toList());
    }

    /**
     * The records whose version is an allowed source of {@code scope} (still {@code ready}), in the order given, each
     * with the place of its first passage on its first page, the ids of its passages there, its name and its tier; a
     * record with no such passage in the scope is left out. Runs in the caller's read transaction.
     */
    private List<ScopedRecord> placed(ResolvedScope scope, Collection<ExtractedRecords.Located> located) {
        List<Placed> placed = located(scope, located);
        if (placed.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> names = records.names(placed.stream().map(one -> one.record().id()).toList());
        return placed.stream().filter(one -> names.containsKey(one.record().id()))
                .map(one -> one.scoped(names.get(one.record().id()))).toList();
    }

    /** A record placed within a scope before its name is read: its first passage on its first page, those passages. */
    private record Placed(ExtractedRecords.Located record, ScopeReads.Place first, List<UUID> passageIds, Tier tier) {
        ScopedRecord scoped(String name) {
            return new ScopedRecord(record.id(), record.versionId(), first.materialId(), first.materialName(),
                    first.sectionPath(), record.firstPage(), passageIds, record.type(), name, tier);
        }
    }

    /**
     * {@link #placed} without the names: nothing is decrypted but the places. From Story 4.4 part B a record whose key
     * is not a record type of the campaign's system ({@link ResolvedScope#recordTypes}) is not in the scope: in a
     * system that never approved its type, it stays passages.
     */
    private List<Placed> located(ResolvedScope scope, Collection<ExtractedRecords.Located> located) {
        Set<UUID> allowed = scope.allowedSources();
        Set<String> keys = scope.recordTypes();
        List<ExtractedRecords.Located> inScope = located.stream()
                .filter(record -> allowed.contains(record.versionId()) && keys.contains(record.type())).toList();
        if (inScope.isEmpty()) {
            return List.of();
        }
        Map<UUID, ScopeReads.Place> places = new HashMap<>();
        reads.places(inScope.stream().flatMap(record -> record.passageIds().stream()).distinct().toList(), allowed)
                .forEach(place -> places.put(place.passageId(), place));
        List<Placed> found = new ArrayList<>();
        for (ExtractedRecords.Located record : inScope) {
            List<ScopeReads.Place> onFirstPage = record.passageIds().stream().map(places::get)
                    .filter(place -> place != null && place.page() == record.firstPage()).toList();
            Optional<Tier> tier = scope.tierOf(record.versionId());
            if (onFirstPage.isEmpty() || tier.isEmpty()) {
                continue;
            }
            found.add(new Placed(record, onFirstPage.getFirst(), onFirstPage.stream()
                    .map(ScopeReads.Place::passageId).toList(), tier.get()));
        }
        return List.copyOf(found);
    }

    /** Which of these versions are removed for the owner, as the class says. */
    public Set<UUID> removedVersions(Set<UUID> versionIds) {
        Objects.requireNonNull(versionIds, "versionIds");
        OwnerContext.require();
        return versionIds.isEmpty() ? Set.of() : reading(() -> reads.removedVersions(versionIds));
    }

    /** Page {@code page} of a cited version of the owner's campaign, as the class says. */
    public Optional<SourcePage> referencePage(UUID campaignId, UUID materialId, UUID versionId, int page) {
        OwnerContext.require();
        Optional<SourcePage> served = reading(() -> ofCampaign(campaignId)
                ? reads.versionPage(materialId, versionId, page).map(Scopes::page) : Optional.<SourcePage>empty());
        log.atInfo().addKeyValue("campaignId", campaignId).addKeyValue("versionId", versionId)
                .addKeyValue("page", page).addKeyValue("served", served.isPresent()).log("scope.reference_page");
        return served;
    }

    /** The picture of that page, as {@link #referencePage} finds it; empty also for a page with no picture. */
    public Optional<SourcePicture> referencePicture(UUID campaignId, UUID materialId, UUID versionId, int page) {
        OwnerContext.require();
        return reading(() -> ofCampaign(campaignId)
                ? reads.versionPicture(materialId, versionId, page).map(Scopes::picture)
                : Optional.<SourcePicture>empty());
    }

    private boolean ofCampaign(UUID campaignId) {
        return campaignId != null && library.campaign(campaignId).isPresent();
    }

    private static SourcePage page(ScopeReads.VersionPage page) {
        return new SourcePage(page.page(), page.pageTotal(), page.pictureWidth(), page.pictureHeight(),
                page.lines().stream().map(line -> new SourcePage.Line(line.text(), line.box())).toList(), page.text(),
                page.passages().stream().map(place -> new SourcePage.Place(place.id(), place.boxes(), place.range()))
                        .toList(), page.outcome());
    }

    private static SourcePicture picture(ScopeReads.PagePicture picture) {
        return new SourcePicture() {
            @Override
            public long size() {
                return picture.size();
            }

            @Override
            public void writeTo(OutputStream out) throws IOException {
                picture.writeTo(out);
            }

            @Override
            public String toString() {
                return "SourcePicture[size=" + picture.size() + "]";
            }
        };
    }

    private List<UUID> pendingOf(List<UUID> sources) {
        return sources.isEmpty() ? List.of() : reads.pendingPassages(sources);
    }

    /** The input of a job that carries {@code question} within {@code scope}, as the class says. */
    public ObjectNode questionInput(ResolvedScope scope, String question) {
        Objects.requireNonNull(scope, "scope");
        if (question == null || question.isEmpty() || question.codePointCount(0, question.length()) > MAX_QUESTION) {
            throw new IllegalArgumentException("scope.question_invalid");
        }
        return withRecordTypes(textInput(scope, "question", question, "scope.question_input", true), scope);
    }

    /**
     * Whether {@code key} is a record type key's text (Story 4.4 part B, V40): a lower-case letter, then up to 31
     * lower-case letters, digits or underscores; false for null.
     */
    public static boolean recordTypeKey(String key) {
        return RecordTypeKeys.valid(key);
    }

    /** A record type key as a log line may carry it: a core key as it is, any other as {@code approved}. */
    public static String loggedType(String key) {
        return RecordTypeKeys.logged(key);
    }

    /**
     * {@code recordTypes} (Story 4.4 part B, internal contract 1.21.0): the record type keys of the campaign's system,
     * sorted, to which the worker limits the records it reads; the records of another key stay out of the scope.
     */
    private static ObjectNode withRecordTypes(ObjectNode input, ResolvedScope scope) {
        ArrayNode types = input.putArray("recordTypes");
        scope.recordTypes().stream().sorted().forEach(types::add);
        return input;
    }

    /**
     * The input of a {@code library.search} job (Story 3.9): {@link #questionInput}'s shape with {@code query} in place
     * of {@code question}, within {@code scope} (narrowed by the search's filters). The query is 1 to
     * {@value #MAX_QUERY} characters (code points), else {@code IllegalArgumentException}.
     */
    public ObjectNode searchInput(ResolvedScope scope, String query) {
        return searchInput(scope, query, null);
    }

    /**
     * {@link #searchInput(ResolvedScope, String)} with a record-type filter (Story 4.3 part B, internal contract
     * 1.18.0): when {@code recordType} (a record type key) is not null, the input also carries {@code recordType}, and
     * the worker returns only records of that type.
     */
    public ObjectNode searchInput(ResolvedScope scope, String query, String recordType) {
        Objects.requireNonNull(scope, "scope");
        if (query == null || query.isEmpty() || query.codePointCount(0, query.length()) > MAX_QUERY) {
            throw new IllegalArgumentException("scope.query_invalid");
        }
        // Story 5.1 part B: never the campaign's artifacts (a search places every result as a material's).
        ObjectNode input = withRecordTypes(textInput(scope, "query", query, "scope.search_input", false), scope);
        if (recordType != null) {
            input.put("recordType", recordType);
        }
        return input;
    }

    /**
     * {@code {<field>: text, owner, modelKnowledge, tiers: [{tier, sources, pending}], tokenKey}} within {@code scope},
     * as {@link #questionInput} says; {@code line} names the log line, which carries counts only. With
     * {@code withArtifacts} (a question's input only, Story 5.1 part B), the campaign tier's sources end with the ids of
     * the campaign's artifacts.
     */
    private ObjectNode textInput(ResolvedScope scope, String field, String text, String line, boolean withArtifacts) {
        Objects.requireNonNull(scope, "scope");
        if (!OwnerContext.require().equals(scope.ownerId())) {
            throw new IllegalStateException("scope.owner_mismatch");
        }
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put(field, text);
        input.put("owner", scope.ownerId().toString());
        input.put("modelKnowledge", scope.settings().modelKnowledge());
        ArrayNode tiers = input.putArray("tiers");
        for (Tier tier : Tier.values()) {
            ObjectNode entry = tiers.addObject();
            entry.put("tier", tier.word());
            ArrayNode sources = entry.putArray("sources");
            scope.sources(tier).forEach(source -> sources.add(source.toString()));
            if (withArtifacts && tier == Tier.CAMPAIGN) {
                scope.artifacts().stream().sorted().forEach(artifact -> sources.add(artifact.toString()));
            }
            ArrayNode pending = entry.putArray("pending");
            scope.pending(tier).stream().limit(MAX_PENDING).forEach(id -> pending.add(id.toString()));
        }
        byte[] key = vault.ownerTokenKey(scope.ownerId());
        try {
            input.put("tokenKey", Base64.getEncoder().encodeToString(key));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        int listed = 0;
        for (Tier tier : Tier.values()) {
            listed += Math.min(scope.pending(tier).size(), MAX_PENDING);
        }
        log.atInfo().addKeyValue("campaignId", scope.campaignId())
                .addKeyValue("campaignSources", scope.sources(Tier.CAMPAIGN).size())
                .addKeyValue("systemSources", scope.sources(Tier.SYSTEM).size())
                .addKeyValue("otherSources", scope.sources(Tier.OTHER).size())
                .addKeyValue("artifacts", withArtifacts ? scope.artifacts().size() : 0)
                .addKeyValue("pending", scope.pending().size())
                .addKeyValue("pendingListed", listed)
                .addKeyValue("pendingLeftOut", scope.pending().size() - listed)
                .addKeyValue("modelKnowledge", scope.settings().modelKnowledge())
                .log(line);
        return input;
    }

    private static ApiException refused(ResolvedScope scope, int requested) {
        log.atInfo().addKeyValue("campaignId", scope.campaignId()).addKeyValue("requested", requested)
                .addKeyValue("reason", REFUSED_CODE).log("request.refused");
        return new ApiException(PASSAGE_REFUSED);
    }

    /** One read-only transaction (or the caller's) for the reads of one answer. */
    private <T> T reading(Supplier<T> read) {
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }
}

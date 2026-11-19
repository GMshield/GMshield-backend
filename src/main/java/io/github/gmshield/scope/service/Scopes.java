package io.github.gmshield.scope.service;

import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.service.ScopeReads;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Vault;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
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
 *       is left out of it). A tier switched off has no source. {@code pending} names the in-scope passages that are not
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
 *       resolved scope only: {@code {question, owner, tiers: [{tier, sources, pending}], tokenKey}}, the tiers in
 *       order (campaign, then system, each always present, a tier with no source with empty lists), {@code pending}
 *       the first {@value #MAX_PENDING} of the tier's pending passages in order (the worker reads them within a time
 *       budget; the total and how many were left out are logged as counts), and {@code tokenKey} Base64 of the
 *       owner's {@link Vault#ownerTokenKey}, whose buffer is zeroed once encoded. The question is 1 to
 *       {@value #MAX_QUESTION} characters (code points), else {@code IllegalArgumentException}. Provider, model and
 *       tier join it with Story 2.10.</li>
 *   <li>{@link #references} (Story 2.5): where cited passages stand within a resolved scope, in the order cited, each
 *       once. A citation is model output, so an id that is not a passage of an allowed source that is still
 *       {@code ready} (unknown, another owner's, out of scope) is dropped, never refused; the line carries the
 *       dropped count only. No passage text is decrypted.</li>
 *   <li>{@link #removedVersions} (Story 2.5): which of these versions are removed for the owner (gone,
 *       {@code discarded}, pending removal, or of a material that is gone), so a stored reference shows as removed.</li>
 *   <li>{@link #referencePage} and {@link #referencePicture} (Story 2.7): one page of the version a source reference
 *       stored with a turn of the campaign names, and its picture: the ground "a source reference stored with a turn of
 *       that campaign" (AD-9), which the caller has checked against the turn. The campaign must be the owner's. The
 *       version is served even when a newer one was accepted since, never once it is removed
 *       ({@link #removedVersions}); any page from 1 to its page total, one per call. Empty for anything else.</li>
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
    /**
     * The most pending passages of a tier a question input lists, in order: the worker reads no more, and reads them a
     * page at a time within its time budget.
     */
    public static final int MAX_PENDING = 5_000;
    private static final Logger log = LoggerFactory.getLogger(Scopes.class);

    private final Library library;
    private final ScopeReads reads;
    private final Vault vault;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    Scopes(Library library, ScopeReads reads, Vault vault, ObjectProvider<PlatformTransactionManager> transactions) {
        this.library = library;
        this.reads = reads;
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
        return resolve(campaignId, settings, false);
    }

    private ResolvedScope resolve(UUID campaignId, ScopeSettings settings, boolean withPending) {
        Objects.requireNonNull(settings, "settings");
        UUID owner = OwnerContext.require();
        return reading(() -> {
            Library.Campaign campaign = campaignOf(campaignId);
            List<UUID> campaignSources = campaignSources(campaign, settings);
            List<UUID> systemSources = systemSources(campaign, settings, campaignSources);
            List<UUID> campaignPending = withPending ? pendingOf(campaignSources) : List.of();
            List<UUID> systemPending = withPending ? pendingOf(systemSources) : List.of();
            ResolvedScope scope = new ResolvedScope(owner, campaign.id(), settings, campaignSources, systemSources,
                    campaignPending, systemPending);
            log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaign.id())
                    .addKeyValue("campaignSources", campaignSources.size())
                    .addKeyValue("systemSources", systemSources.size())
                    .addKeyValue("pending", campaignPending.size() + systemPending.size())
                    .addKeyValue("pendingRead", withPending).log("scope.resolved");
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
            List<UUID> campaignSources = campaignSources(campaign, settings);
            List<UUID> sources = new ArrayList<>(campaignSources);
            sources.addAll(systemSources(campaign, settings, campaignSources));
            Indexing indexing = sources.isEmpty() ? null : reads.indexing(sources)
                    .map(count -> new Indexing(count.remaining())).orElse(null);
            IndexingFailure failed = sources.isEmpty() ? null : reads.failed(sources)
                    .map(count -> new IndexingFailure(count.passages(), count.retrying())).orElse(null);
            log.atDebug().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaign.id())
                    .addKeyValue("sources", sources.size())
                    .addKeyValue("remaining", indexing == null ? 0 : indexing.remaining())
                    .addKeyValue("failed", failed == null ? 0 : failed.passages())
                    .addKeyValue("retrying", failed != null && failed.retrying()).log("scope.indexing");
            return new ScopeStatus(!sources.isEmpty(), indexing, failed);
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

    private List<UUID> campaignSources(Library.Campaign campaign, ScopeSettings settings) {
        return settings.campaignTier() ? reads.readyOfCampaign(campaign.id()) : List.of();
    }

    /** The system tier's sources: a version already in the campaign tier is left out of it. */
    private List<UUID> systemSources(Library.Campaign campaign, ScopeSettings settings, List<UUID> campaignSources) {
        return settings.systemTier()
                ? reads.readyOfSystem(campaign.system().id()).stream()
                        .filter(source -> !campaignSources.contains(source)).toList()
                : List.of();
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
                        place.materialName(), place.sectionPath(), place.page())).toList();
        log.atInfo().addKeyValue("campaignId", scope.campaignId()).addKeyValue("cited", cited.size())
                .addKeyValue("kept", places.size()).addKeyValue("dropped", cited.size() - places.size())
                .log("scope.references_read");
        return places;
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
        if (!OwnerContext.require().equals(scope.ownerId())) {
            throw new IllegalStateException("scope.owner_mismatch");
        }
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("question", question);
        input.put("owner", scope.ownerId().toString());
        ArrayNode tiers = input.putArray("tiers");
        for (Tier tier : Tier.values()) {
            ObjectNode entry = tiers.addObject();
            entry.put("tier", tier.word());
            ArrayNode sources = entry.putArray("sources");
            scope.sources(tier).forEach(source -> sources.add(source.toString()));
            ArrayNode pending = entry.putArray("pending");
            scope.pending(tier).stream().limit(MAX_PENDING).forEach(id -> pending.add(id.toString()));
        }
        byte[] key = vault.ownerTokenKey(scope.ownerId());
        try {
            input.put("tokenKey", Base64.getEncoder().encodeToString(key));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        int listed = Math.min(scope.pending(Tier.CAMPAIGN).size(), MAX_PENDING)
                + Math.min(scope.pending(Tier.SYSTEM).size(), MAX_PENDING);
        log.atInfo().addKeyValue("campaignId", scope.campaignId())
                .addKeyValue("campaignSources", scope.sources(Tier.CAMPAIGN).size())
                .addKeyValue("systemSources", scope.sources(Tier.SYSTEM).size())
                .addKeyValue("pending", scope.pending().size())
                .addKeyValue("pendingListed", listed)
                .addKeyValue("pendingLeftOut", scope.pending().size() - listed)
                .log("scope.question_input");
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

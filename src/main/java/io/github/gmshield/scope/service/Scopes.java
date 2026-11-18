package io.github.gmshield.scope.service;

import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.service.ScopeReads;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.ArrayList;
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

/**
 * The one resolver of what a campaign may use (Story 2.2, AD-9). Everything here acts for the owner of the work
 * ({@link OwnerContext}), in one read transaction (the caller's, when there is one).
 *
 * <ul>
 *   <li>{@link #resolve}: a campaign of the owner and its {@link ScopeSettings} become a {@link ResolvedScope}. The
 *       campaign tier is the {@code ready} version of each material of the campaign; the system tier the {@code ready}
 *       version of each material that belongs to the campaign's game system (a version already in the campaign tier
 *       is left out of it). A tier switched off has no source. {@code pending} names the in-scope passages whose index
 *       change has not ended. A campaign that is not found (unknown, or another owner's) is {@code request.not_found}
 *       (404).</li>
 *   <li>{@link #passages}: passages by id within a resolved scope, decrypted and labelled with their tier, in the
 *       order asked, each once. Every id is checked first: when any is not a passage of an allowed source that is
 *       still {@code ready} (unknown, another owner's, of a version no longer {@code ready}, or out of every tier), the
 *       whole request is refused with {@value #REFUSED_CODE} (403) before anything is decrypted, and the refusal names
 *       no id.</li>
 * </ul>
 *
 * Lines carry ids and counts, never a text or a section title.
 */
@Service
public class Scopes {

    public static final String CATEGORY = "scope";
    static final String REFUSED_CODE = "scope.passage_refused";
    static final ApiProblem PASSAGE_REFUSED = ApiProblem.of(403, CATEGORY, "passage_refused",
            "Passage outside the scope");
    private static final Logger log = LoggerFactory.getLogger(Scopes.class);

    private final Library library;
    private final ScopeReads reads;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    Scopes(Library library, ScopeReads reads, ObjectProvider<PlatformTransactionManager> transactions) {
        this.library = library;
        this.reads = reads;
        this.transactions = transactions;
    }

    /** The owner's campaign's scope under {@code settings}, as the class says. */
    public ResolvedScope resolve(UUID campaignId, ScopeSettings settings) {
        Objects.requireNonNull(settings, "settings");
        UUID owner = OwnerContext.require();
        return reading(() -> {
            Optional<Library.Campaign> found = campaignId == null ? Optional.empty() : library.campaign(campaignId);
            if (found.isEmpty()) {
                log.atInfo().addKeyValue("campaignId", campaignId).addKeyValue("reason", "request.not_found")
                        .log("scope.campaign_not_found");
                throw new ApiException(ApiProblem.forStatus(404));
            }
            Library.Campaign campaign = found.get();
            List<UUID> campaignSources = settings.campaignTier() ? reads.readyOfCampaign(campaign.id()) : List.of();
            List<UUID> systemSources = settings.systemTier()
                    ? reads.readyOfSystem(campaign.system().id()).stream()
                            .filter(source -> !campaignSources.contains(source)).toList()
                    : List.of();
            List<UUID> inScope = new ArrayList<>(campaignSources);
            inScope.addAll(systemSources);
            List<UUID> pending = inScope.isEmpty() ? List.of() : reads.pendingPassages(inScope);
            ResolvedScope scope = new ResolvedScope(owner, campaign.id(), settings, campaignSources, systemSources,
                    pending);
            log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaign.id())
                    .addKeyValue("campaignSources", campaignSources.size())
                    .addKeyValue("systemSources", systemSources.size()).addKeyValue("pending", pending.size())
                    .log("scope.resolved");
            return scope;
        });
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

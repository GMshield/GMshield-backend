package io.github.gmshield.scope.service;

import io.github.gmshield.library.service.Library;
import io.github.gmshield.scope.data.ScopeStore;
import io.github.gmshield.scope.data.StaleScopeVersion;
import io.github.gmshield.scope.data.StoredScope;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.github.gmshield.shared.service.OwnerContext;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A campaign's stored scope settings (Story 3.5), for the owner of the work ({@link OwnerContext}). Another owner's
 * campaign, an unknown one or one being deleted is "not found" (empty) everywhere.
 *
 * <ul>
 *   <li>{@link #settings}: the campaign's stored settings, or {@link ScopeSettings#STARTING} when it has none. A
 *       question freezes them on its turn.</li>
 *   <li>{@link #view}: the settings, their version (0 with none stored) and the materials of every tier, whatever the
 *       tier's switch ({@link Scopes#members}), each with whether it is on.</li>
 *   <li>{@link #change}: the settings replaced on the version the change names, the materials off exactly those it
 *       lists; answers the new view, its version raised by one. A version the settings have left is
 *       {@code request.stale_version} (409), told before the ids are checked; a material id that is not the owner's, or in no tier, is
 *       {@value #MATERIAL_UNKNOWN_CODE} (400); nothing changes then. An id stored off whose material has gone since is
 *       dropped by the next change, which lists only the ids it is given.</li>
 *   <li>{@link #sourcePage} and {@link #sourcePicture}: a page of a version that is an allowed source of the campaign's
 *       current stored scope ({@link Scopes#resolveSources}), the ground "the current scope"; empty for anything else,
 *       an unknown campaign included.</li>
 * </ul>
 *
 * Lines carry ids, switches and counts only.
 */
@Service
public class CampaignScopes {

    static final String MATERIAL_UNKNOWN_CODE = "scope.material_unknown";
    static final ApiProblem MATERIAL_UNKNOWN = ApiProblem.of(400, Scopes.CATEGORY, "material_unknown",
            "Material not in the scope");
    static final ApiProblem STALE_VERSION = ApiProblem.of(409, ApiProblem.CATEGORY_REQUEST, "stale_version",
            "Stale version");
    /** The most materials a change may switch off. */
    public static final int MAX_MATERIALS_OFF = 1000;
    private static final Logger log = LoggerFactory.getLogger(CampaignScopes.class);

    private final ScopeStore store;
    private final Scopes scopes;
    private final Library library;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final Clock clock;

    CampaignScopes(ScopeStore store, Scopes scopes, Library library,
                   ObjectProvider<PlatformTransactionManager> transactions, ObjectProvider<Clock> clock) {
        this.store = store;
        this.scopes = scopes;
        this.library = library;
        this.transactions = transactions;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** A material of the view: its id, name and tier, and whether it is switched on. */
    public record Material(UUID id, String name, Tier tier, boolean on) {
        @Override
        public String toString() {
            return "Material[id=" + id + ", tier=" + tier + ", on=" + on + "]";
        }
    }

    /** A campaign's settings as the GM sees them: the version, the settings and the materials of every tier. */
    public record View(long version, ScopeSettings settings, List<Material> materials) {
        public View {
            materials = List.copyOf(materials);
        }
    }

    /** A change of the settings, on the version it was made on. {@code toString} counts the ids. */
    public record Change(long version, boolean campaignTier, boolean systemTier, boolean otherTier,
                         boolean modelKnowledge, List<UUID> materialsOff) {
        public Change {
            materialsOff = List.copyOf(materialsOff);
        }

        @Override
        public String toString() {
            return "Change[version=" + version + ", materialsOff=" + materialsOff.size() + "]";
        }
    }

    /** The campaign's stored settings, or the starting ones when it has none, as the class says. */
    public ScopeSettings settings(UUID campaignId) {
        OwnerContext.require();
        return reading(() -> stored(campaignId).map(CampaignScopes::settingsOf).orElse(ScopeSettings.STARTING));
    }

    /** The owner's campaign's settings and materials, as the class says; empty when it is not found. */
    public Optional<View> view(UUID campaignId) {
        OwnerContext.require();
        return reading(() -> found(campaignId) ? Optional.of(viewOf(campaignId)) : Optional.<View>empty());
    }

    /** Changes the owner's campaign's settings, as the class says; empty when it is not found. */
    public Optional<View> change(UUID campaignId, Change change) {
        Objects.requireNonNull(change, "change");
        UUID owner = OwnerContext.require();
        Optional<View> changed = writing(() -> {
            if (!found(campaignId)) {
                return Optional.<View>empty();
            }
            // A stale version is told first: a material that left the scope since the GM read it is part of what moved.
            if (stored(campaignId).map(StoredScope::version).orElse(0L) != change.version()) {
                throw stale(campaignId, change.version());
            }
            Set<UUID> members = scopes.members(campaignId).stream().map(Scopes.Member::materialId)
                    .collect(Collectors.toSet());
            Set<UUID> off = new LinkedHashSet<>(change.materialsOff());
            if (!members.containsAll(off)) {
                log.atInfo().addKeyValue("campaignId", campaignId).addKeyValue("reason", MATERIAL_UNKNOWN_CODE)
                        .log("request.refused");
                throw new ApiException(MATERIAL_UNKNOWN);
            }
            try {
                store.change(campaignId, change.version(), change.campaignTier(), change.systemTier(),
                        change.otherTier(), change.modelKnowledge(), off, now());
            } catch (StaleScopeVersion stale) {
                throw stale(campaignId, change.version());
            }
            return Optional.of(viewOf(campaignId));
        });
        changed.ifPresent(view -> log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("version", view.version()).addKeyValue("campaignTier", view.settings().campaignTier())
                .addKeyValue("systemTier", view.settings().systemTier())
                .addKeyValue("otherTier", view.settings().otherTier())
                .addKeyValue("modelKnowledge", view.settings().modelKnowledge())
                .addKeyValue("materialsOff", view.settings().materialsOff().size()).log("scope.settings_changed"));
        return changed;
    }

    /** Page {@code page} of an allowed source of the campaign's current scope, as the class says. */
    public Optional<SourcePage> sourcePage(UUID campaignId, UUID versionId, int page) {
        OwnerContext.require();
        Optional<SourcePage> served = reading(() -> current(campaignId)
                .flatMap(scope -> scopes.sourcePage(scope, versionId, page)));
        log.atInfo().addKeyValue("campaignId", campaignId).addKeyValue("versionId", versionId)
                .addKeyValue("page", page).addKeyValue("served", served.isPresent()).log("scope.source_page");
        return served;
    }

    /** The picture of that page, as {@link #sourcePage} finds it; empty also for a page with no picture. */
    public Optional<SourcePicture> sourcePicture(UUID campaignId, UUID versionId, int page) {
        OwnerContext.require();
        Optional<SourcePicture> served = reading(() -> current(campaignId)
                .flatMap(scope -> scopes.sourcePicture(scope, versionId, page)));
        log.atInfo().addKeyValue("campaignId", campaignId).addKeyValue("versionId", versionId)
                .addKeyValue("page", page).addKeyValue("served", served.isPresent()).log("scope.source_picture");
        return served;
    }

    /** The campaign's current scope, without its pending passages; empty for a campaign that is not found. */
    private Optional<ResolvedScope> current(UUID campaignId) {
        if (!found(campaignId)) {
            return Optional.empty();
        }
        return Optional.of(scopes.resolveSources(campaignId, settings(campaignId)));
    }

    private static ApiException stale(UUID campaignId, long version) {
        log.atInfo().addKeyValue("campaignId", campaignId).addKeyValue("version", version)
                .addKeyValue("reason", "request.stale_version").log("request.refused");
        return new ApiException(STALE_VERSION);
    }

    private View viewOf(UUID campaignId) {
        Optional<StoredScope> stored = stored(campaignId);
        ScopeSettings settings = stored.map(CampaignScopes::settingsOf).orElse(ScopeSettings.STARTING);
        List<Material> materials = scopes.members(campaignId).stream().map(member -> new Material(member.materialId(),
                member.name(), member.tier(), settings.materialOn(member.materialId()))).toList();
        return new View(stored.map(StoredScope::version).orElse(0L), settings, materials);
    }

    private Optional<StoredScope> stored(UUID campaignId) {
        return campaignId == null ? Optional.empty() : store.find(campaignId);
    }

    private static ScopeSettings settingsOf(StoredScope stored) {
        return new ScopeSettings(stored.campaignTier(), stored.systemTier(), stored.otherTier(),
                stored.modelKnowledge(), stored.materialsOff());
    }

    private boolean found(UUID campaignId) {
        return campaignId != null && library.campaign(campaignId).isPresent();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private <T> T writing(Supplier<T> write) {
        return new TransactionTemplate(transactions.getObject()).execute(status -> write.get());
    }

    private <T> T reading(Supplier<T> read) {
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }
}

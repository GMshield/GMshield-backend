package io.github.gmshield.materials.service;

import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.MaterialSystemRow;
import io.github.gmshield.materials.data.StaleMaterialVersion;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Where a material belongs, changed by the GM (Story 3.3), for the owner of the work ({@link OwnerContext}). A material
 * belongs to exactly one of these: one campaign, or one to {@value Uploads#MAX_SYSTEMS} systems. This service and the
 * upload's check ({@link Uploads#place}, which both use) own that rule, since the database cannot see the campaign
 * column and the {@code material_system} rows together.
 *
 * <p>A change writes only {@code material.campaign_id}, the {@code material_system} rows and {@code material.version}:
 * never a version, a job, a passage or an index point. Every read of a campaign's scope resolves again, so the change
 * applies from the next question; a passage of the material asked for afterwards by a question already running is
 * refused ({@code scope.passage_refused}) once it leaves that question's scope, and references already stored with a
 * turn still open.
 *
 * <p>Answers: the material as {@link Materials#find} reads it after the change; empty (404) for an unknown material,
 * another owner's, or one whose every place is being deleted; {@code material.belonging_invalid} (400) for a campaign
 * and systems together, neither, an empty or over-long list of systems, or a target that is not one of the owner's
 * ready campaigns (archived ones included) and systems; {@code request.stale_version} (409) when the material has left
 * the version named. Nothing changes then. A material uploaded with no place can be given one; none can be taken back
 * to no place. Logs carry ids, counts and versions only.
 */
@Service
public class MaterialBelongings {

    private static final Logger log = LoggerFactory.getLogger(MaterialBelongings.class);

    private final MaterialStore materials;
    private final Uploads uploads;
    private final Materials reads;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final ObjectProvider<Clock> clock;

    MaterialBelongings(MaterialStore materials, Uploads uploads, Materials reads,
                       ObjectProvider<PlatformTransactionManager> transactions, ObjectProvider<Clock> clock) {
        this.materials = materials;
        this.uploads = uploads;
        this.reads = reads;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * The owner's material {@code materialId}, at {@code version}, now belongs to the campaign {@code campaign} or to the
     * systems {@code systems} (exactly one of them given); see the class.
     */
    public Optional<Materials.Material> change(UUID materialId, long version, String campaign, List<String> systems) {
        UUID owner = OwnerContext.require();
        if ((campaign == null) == (systems == null) || (systems != null && systems.isEmpty())) {
            throw MaterialProblems.belongingInvalid();
        }
        Instant now = now();
        Optional<MaterialRow> changed;
        try {
            changed = new TransactionTemplate(transactions.getObject()).execute(status -> {
                Optional<MaterialRow> found = materials.findMaterial(materialId)
                        .filter(row -> reads.belongings(List.of(row)).containsKey(row.id()));
                if (found.isEmpty()) {
                    return Optional.<MaterialRow>empty();
                }
                Uploads.Place place = uploads.place(campaign, systems == null ? List.of() : systems,
                        MaterialProblems::belongingInvalid);
                List<MaterialSystemRow> rows = place.systems().stream()
                        .map(system -> new MaterialSystemRow(Ids.newId(), owner, materialId, system, now)).toList();
                return materials.changeBelonging(materialId, version, place.campaign(), rows, now);
            });
        } catch (StaleMaterialVersion stale) {
            throw new ApiException(MaterialProblems.STALE_VERSION);
        }
        if (changed.isEmpty()) {
            return Optional.empty();
        }
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("materialId", materialId)
                .addKeyValue("version", changed.get().version()).addKeyValue("campaign", campaign != null)
                .addKeyValue("systems", systems == null ? 0 : systems.size()).log("material.belonging_changed");
        return reads.find(materialId);
    }

    private Instant now() {
        return clock.getIfAvailable(Clock::systemUTC).instant().truncatedTo(ChronoUnit.MICROS);
    }
}

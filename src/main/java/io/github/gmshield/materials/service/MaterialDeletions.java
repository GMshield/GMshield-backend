package io.github.gmshield.materials.service;

import io.github.gmshield.library.service.Library;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deleting one of the owner's materials, and removing one superseded version of one (Story 3.4 part B, AD-22), for the
 * owner of the work ({@link OwnerContext}). This service checks the target; the deletion itself is the recorded,
 * resumable process of {@link SystemDeletion} ({@link SystemDeletion#deleteMaterial},
 * {@link SystemDeletion#removeVersion}), whose materials part ({@link SystemMaterialsDeletion}) discards what goes in
 * the GM's transaction, so its passages leave every scope at once, and removes its rows, files and index points later.
 *
 * <ul>
 *   <li>{@link #count}: what the confirmation dialog names, the material's versions that are not {@code discarded} and
 *       the sum of their page totals.</li>
 *   <li>{@link #delete}: the material goes with every version. A material whose deletion is recorded already is
 *       answered the same, and nothing changes.</li>
 *   <li>{@link #removeVersion}: a {@code superseded} version of the material goes; the material and its other versions
 *       stay. A version whose removal is recorded already is answered the same; any other state is
 *       {@code material.version_not_removable} (409): a ready or in-progress version is discarded instead, never
 *       removed here.</li>
 * </ul>
 *
 * Another owner's material, an unknown one, or one whose every place is being deleted is "not found" (empty or false)
 * on all three; so is a version of another material. Once a material's deletion is recorded, its count and its versions
 * are not found either, while a second delete of it is answered as the first. Logs carry ids only.
 */
@Service
public class MaterialDeletions {

    private static final Logger log = LoggerFactory.getLogger(MaterialDeletions.class);

    private final MaterialStore materials;
    private final Library library;
    private final SystemDeletion deletion;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    MaterialDeletions(MaterialStore materials, Library library, SystemDeletion deletion,
                      ObjectProvider<PlatformTransactionManager> transactions) {
        this.materials = materials;
        this.library = library;
        this.deletion = deletion;
        this.transactions = transactions;
    }

    /** What goes with a material: its versions that are not {@code discarded}, and the sum of their page totals. */
    public record Count(long versions, long pages) {
    }

    /**
     * What goes with the owner's material {@code materialId}, as the class says; empty when it is not found, or its
     * deletion is recorded already.
     */
    public Optional<Count> count(UUID materialId) {
        if (deletion.materialDeletionRecorded(materialId)) {
            return Optional.empty();
        }
        return reading(() -> found(materialId).map(material -> {
            List<VersionRow> showing = materials.versionsOf(material.id()).stream()
                    .filter(version -> !Uploads.DISCARDED.equals(version.state())).toList();
            long pages = showing.stream().map(VersionRow::pageTotal).filter(java.util.Objects::nonNull)
                    .mapToLong(Integer::longValue).sum();
            return new Count(showing.size(), pages);
        }));
    }

    /**
     * Deletes the owner's material {@code materialId}, as the class says; true when its deletion is recorded (now or
     * before), false (nothing changes) when it is not found.
     */
    public boolean delete(UUID materialId) {
        if (reading(() -> found(materialId)).isEmpty()) {
            return false;
        }
        boolean recorded = deletion.deleteMaterial(materialId);
        log.atInfo().addKeyValue("materialId", materialId).addKeyValue("again", !recorded).log("material.delete");
        return true;
    }

    /**
     * Removes the owner's version {@code versionId} of material {@code materialId}, as the class says; true when its
     * removal is recorded (now or before), false (nothing changes) when the material or the version is not found.
     * Throws {@code material.version_not_removable} for a version that is not superseded and whose removal is not
     * recorded. False also once the material's own deletion is recorded.
     */
    public boolean removeVersion(UUID materialId, UUID versionId) {
        if (deletion.materialDeletionRecorded(materialId)) {
            // The material goes, every version with it: none of them is found on its own any more.
            return false;
        }
        Optional<VersionRow> version = reading(() -> found(materialId)
                .flatMap(material -> materials.findVersion(versionId))
                .filter(row -> materialId.equals(row.materialId())));
        if (version.isEmpty()) {
            return false;
        }
        if (Materials.SUPERSEDED.equals(version.get().state())) {
            boolean recorded = deletion.removeVersion(versionId);
            log.atInfo().addKeyValue("materialId", materialId).addKeyValue("versionId", versionId)
                    .addKeyValue("again", !recorded).log("material.version_remove");
            return true;
        }
        if (deletion.versionRemovalRecorded(versionId)) {
            return true;
        }
        throw MaterialProblems.versionNotRemovable();
    }

    /** The owner's material, unless it belongs somewhere and every one of its places is being deleted. */
    private Optional<MaterialRow> found(UUID materialId) {
        return materials.findMaterial(materialId)
                .filter(row -> Materials.belongings(materials, library, List.of(row)).containsKey(row.id()));
    }

    /** One read-only transaction for the reads of one answer. */
    private <T> T reading(Supplier<T> read) {
        OwnerContext.require();
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }
}

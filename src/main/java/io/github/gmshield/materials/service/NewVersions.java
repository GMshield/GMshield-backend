package io.github.gmshield.materials.service;

import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.VersionRow;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The one check before a material gets a new version (Story 3.4): a replacement upload ({@link Uploads},
 * {@link ImageSets}) or a reprocess of its accepted version ({@link MaterialActions}). It runs in the caller's
 * transaction, which then inserts the new version: the material is locked first ({@link MaterialStore#lockMaterial}),
 * so two requests for one material take turns and the second sees the first's version.
 *
 * <p>Answers, in this order: another owner's material, an unknown one, or one whose every place is being deleted is
 * {@code request.not_found} (404); a version of it that is {@code uploading}, {@code waiting}, {@code processing},
 * {@code needs_review} or {@code failed} is {@code material.version_in_progress} (409); no {@code ready} version (every
 * version discarded) is {@code material.not_ready} (409). Otherwise the current version is the {@code ready} one, which
 * keeps serving every scope until the new version is accepted.
 */
final class NewVersions {

    /** The states of a version still on its way: while one exists, no other version is started. */
    static final Set<String> IN_PROGRESS = Set.of(Uploads.UPLOADING, Uploads.WAITING, IngestionSteps.PROCESSING,
            IngestionSteps.NEEDS_REVIEW, Materials.FAILED);

    /** The locked material and its {@code ready} version. */
    record Ready(MaterialRow material, VersionRow version) {
    }

    private NewVersions() {
    }

    /** The owner's material {@code materialId}, locked, with its {@code ready} version; see the class. */
    static Ready lockReady(MaterialStore materials, Library library, UUID materialId) {
        MaterialRow found = materials.findMaterial(materialId).orElseThrow(UploadProblems::notFound);
        if (!Materials.belongings(materials, library, List.of(found)).containsKey(found.id())) {
            throw UploadProblems.notFound();
        }
        MaterialRow locked = materials.lockMaterial(materialId).orElseThrow(UploadProblems::notFound);
        List<VersionRow> versions = materials.versionsOf(materialId);
        if (versions.stream().anyMatch(version -> IN_PROGRESS.contains(version.state()))) {
            throw MaterialProblems.versionInProgress();
        }
        VersionRow ready = versions.stream().filter(version -> Materials.READY.equals(version.state()))
                .reduce((first, second) -> second).orElseThrow(MaterialProblems::notReady);
        return new Ready(locked, ready);
    }
}

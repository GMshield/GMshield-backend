package io.github.gmshield.materials.service;

import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.vault.service.Vault;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Where a corrected record's passages of a replaced version stand in the version reprocessing made from it (Story 4.5),
 * for a correction that matches no new record ({@link ExtractedRecords#carryCorrections}): for each old passage, the
 * new version's passages on the same page under the same section path, or else every new passage on that page. The
 * places are decrypted for this only (the {@code knowledge} module does not read passages), and the new version's
 * passages are read once, at the first call. Answers the passages in reading order with their lowest page, or empty when
 * none of the old pages has a passage in the new version. At most {@value IngestionSteps#MAX_RECORD_PASSAGES} passages
 * are kept, as an extracted record links, the first in reading order. Nothing here logs.
 */
final class PassageRemap implements Function<List<UUID>, Optional<ExtractedRecords.Remapped>> {

    private final MaterialStore materials;
    private final Vault vault;
    private final UUID owner;
    private final UUID toVersionId;
    private Map<Spot, List<PassageRow>> bySpot;
    private Map<Integer, List<PassageRow>> byPage;

    PassageRemap(MaterialStore materials, Vault vault, UUID owner, UUID toVersionId) {
        this.materials = materials;
        this.vault = vault;
        this.owner = owner;
        this.toVersionId = toVersionId;
    }

    /** A page and a section path, as a reference groups passages. */
    private record Spot(int page, List<String> sectionPath) {
    }

    @Override
    public Optional<ExtractedRecords.Remapped> apply(List<UUID> oldPassageIds) {
        load();
        Map<UUID, PassageRow> found = new LinkedHashMap<>();
        for (PassageRow old : materials.findPassages(oldPassageIds)) {
            Spot spot = new Spot(old.page(), sectionPath(old));
            List<PassageRow> here = bySpot.getOrDefault(spot, byPage.getOrDefault(old.page(), List.of()));
            here.forEach(passage -> found.putIfAbsent(passage.id(), passage));
        }
        if (found.isEmpty()) {
            return Optional.empty();
        }
        List<PassageRow> all = new ArrayList<>(found.values());
        all.sort(Comparator.comparingInt(PassageRow::ordinal));
        // No more than an extracted record may link (the per-page fallback can find many), the first in reading order.
        List<PassageRow> ordered = all.subList(0, Math.min(all.size(), IngestionSteps.MAX_RECORD_PASSAGES));
        int firstPage = ordered.stream().mapToInt(PassageRow::page).min().orElseThrow();
        return Optional.of(new ExtractedRecords.Remapped(ordered.stream().map(PassageRow::id).toList(), firstPage));
    }

    private void load() {
        if (bySpot != null) {
            return;
        }
        bySpot = new HashMap<>();
        byPage = new HashMap<>();
        for (PassageRow passage : materials.passages(toVersionId)) {
            bySpot.computeIfAbsent(new Spot(passage.page(), sectionPath(passage)), ignored -> new ArrayList<>())
                    .add(passage);
            byPage.computeIfAbsent(passage.page(), ignored -> new ArrayList<>()).add(passage);
        }
    }

    private List<String> sectionPath(PassageRow passage) {
        return Passages.place(vault.decrypt(Passages.place(passage.id(), owner), passage.place())).sectionPath();
    }
}

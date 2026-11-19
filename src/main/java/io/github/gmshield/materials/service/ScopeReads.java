package io.github.gmshield.materials.service;

import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.materials.data.IndexChangeRow;
import io.github.gmshield.materials.data.IndexStore;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The library reads a campaign's scope is made of (Story 2.2, AD-9): outside {@code materials}, the one way to read
 * library items for a campaign, and only the {@code scope} module may use it ({@code ModuleBoundaryTest}). Every read
 * acts for the owner of the work ({@link OwnerContext}), in a read transaction (the caller's, when there is one), so
 * another owner's item reads exactly like an unknown one.
 *
 * <ul>
 *   <li>{@link #readyOfCampaign} and {@link #readyOfSystem}: the ids of the {@code ready} versions of the materials of a
 *       campaign ({@code material.campaign_id}), or that belong to a game system ({@code material_system}).</li>
 *   <li>{@link #pendingPassages}: the ids of the passages of these versions whose index change has not ended
 *       ({@link IndexChanges#unended}).</li>
 *   <li>{@link #indexing}: how many passages of these versions are still waiting for search, that is the distinct
 *       passages in the runs of their index changes that have not ended. Counted with one count query per run's
 *       ordinal range (overlapping runs merged first), so no passage id or text is read; nothing pending is empty.</li>
 *   <li>{@link #passages}: passages by id, checked before anything is decrypted: every id must be a passage of the
 *       owner, of one of the given versions, and that version {@code ready}; otherwise nothing is read.</li>
 *   <li>{@link #places} (Story 2.5): where cited passages stand, filtered rather than refused: a passage that is not
 *       the owner's, not of one of the given versions, or of a version that is not {@code ready} is left out. Only
 *       the place (section path) and the material's name are decrypted, never a passage's text.</li>
 *   <li>{@link #removedVersions} (Story 2.5): which of these versions are gone, {@code discarded}, pending removal, or
 *       of a material that is gone.</li>
 *   <li>{@link #versionPage} and {@link #versionPicture} (Story 2.7): one page of a given version of a given material,
 *       and its picture, as the review ground serves a page ({@link Materials#versionPage}), for a version that is not
 *       removed ({@link #removedVersions}) and is {@code ready} or {@code superseded}: the version a stored reference
 *       names, even when it is no longer the newest. A page outside 1 to the page total is not served.</li>
 * </ul>
 *
 * Nothing here logs; a text or a section title never leaves but in {@link Passage}, whose {@code toString} names ids.
 */
@Service
@NamedInterface("service")
public class ScopeReads {

    private final MaterialStore materials;
    private final IndexStore index;
    private final Vault vault;
    private final Function<Collection<UUID>, Set<UUID>> unendedJobs;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final Materials pages;
    /** The states of a version a stored reference may still open (Story 2.7). */
    private static final Set<String> CITED = Set.of(Materials.READY, Materials.SUPERSEDED);

    /** A passage read for a scope: its id, its version (its source), section path (outermost first), page and text. */
    @NamedInterface("service")
    public record Passage(UUID id, UUID versionId, List<String> sectionPath, int page, String text) {
        @Override
        public String toString() {
            return "Passage[id=" + id + ", versionId=" + versionId + ", page=" + page + "]";
        }
    }

    /**
     * Where a cited passage stands (Story 2.5): its id, its version and material, the material's name, its section path
     * (outermost first) and its page. {@code toString} names ids and the page only.
     */
    @NamedInterface("service")
    public record Place(UUID passageId, UUID versionId, UUID materialId, String materialName, List<String> sectionPath,
                        int page) {
        public Place {
            sectionPath = List.copyOf(sectionPath);
        }

        @Override
        public String toString() {
            return "Place[passageId=" + passageId + ", versionId=" + versionId + ", page=" + page + "]";
        }
    }

    /**
     * One page of a version (Story 2.7), as {@link Materials.VersionPage} has it: its number and the version's page
     * total, its picture's size (both null for a page with no picture), its lines or its text, its passages' places in
     * reading order, and its outcome. {@code toString} names numbers only.
     */
    @NamedInterface("service")
    public record VersionPage(int page, int pageTotal, Integer pictureWidth, Integer pictureHeight, List<PageLine> lines,
                              String text, List<PagePlace> passages, String outcome) {
        public VersionPage {
            lines = List.copyOf(lines);
            passages = List.copyOf(passages);
        }

        @Override
        public String toString() {
            return "VersionPage[page=" + page + ", pageTotal=" + pageTotal + "]";
        }
    }

    /** How many passages of some versions are still waiting for search: {@code remaining >= 1}. */
    @NamedInterface("service")
    public record Indexing(int remaining) {
        public Indexing {
            if (remaining < 1) {
                throw new IllegalArgumentException("materials.indexing_invalid");
            }
        }
    }

    /** A text line of a picture page and its box, {@code [left, top, right, bottom]} as fractions of the picture. */
    @NamedInterface("service")
    public record PageLine(String text, List<Double> box) {
        @Override
        public String toString() {
            return "PageLine[box=" + box + "]";
        }
    }

    /** A passage's place on its page: its boxes (a picture page), or its code-point range (a text page). */
    @NamedInterface("service")
    public record PagePlace(UUID id, List<List<Double>> boxes, List<Integer> range) {
    }

    /** A page's picture: WebP, streamed one decrypted piece at a time. */
    @NamedInterface("service")
    public static final class PagePicture {
        private final Materials.Picture picture;

        PagePicture(Materials.Picture picture) {
            this.picture = picture;
        }

        public long size() {
            return picture.size();
        }

        public void writeTo(OutputStream out) throws IOException {
            picture.writeTo(out);
        }

        @Override
        public String toString() {
            return "PagePicture[size=" + picture.size() + "]";
        }
    }

    @Autowired
    ScopeReads(MaterialStore materials, IndexStore index, Vault vault, Jobs jobs,
               ObjectProvider<PlatformTransactionManager> transactions, Materials pages) {
        this(materials, index, vault, jobs::unended, transactions, pages);
    }

    /** {@code pages} may be null where no page is read (it then answers no page). */
    ScopeReads(MaterialStore materials, IndexStore index, Vault vault, Function<Collection<UUID>, Set<UUID>> unendedJobs,
               ObjectProvider<PlatformTransactionManager> transactions, Materials pages) {
        this.materials = materials;
        this.index = index;
        this.vault = vault;
        this.unendedJobs = unendedJobs;
        this.transactions = transactions;
        this.pages = pages;
    }

    /** The ids of the owner's {@code ready} versions of the campaign's materials, oldest first. */
    public List<UUID> readyOfCampaign(UUID campaignId) {
        return reading(() -> ids(materials.readyOfCampaign(campaignId)));
    }

    /** The ids of the owner's {@code ready} versions of the materials that belong to the game system, oldest first. */
    public List<UUID> readyOfSystem(UUID systemId) {
        return reading(() -> ids(materials.readyOfSystem(systemId)));
    }

    /**
     * The ids of the owner's passages of these versions whose ordinal falls in the run of an index change that has not
     * ended, by version as given, then by ordinal.
     */
    public List<UUID> pendingPassages(Collection<UUID> versionIds) {
        return reading(() -> {
            List<UUID> pending = new ArrayList<>();
            for (UUID versionId : new LinkedHashSet<>(versionIds)) {
                for (IndexChangeRow run : IndexChanges.unended(index, unendedJobs, versionId)) {
                    pending.addAll(materials.passageIds(versionId, run.firstOrdinal(), run.lastOrdinal()));
                }
            }
            return List.copyOf(new LinkedHashSet<>(pending));
        });
    }

    /**
     * How many of the owner's passages of these versions are still waiting for search, as the class says; empty when
     * none is.
     */
    public Optional<Indexing> indexing(Collection<UUID> versionIds) {
        if (versionIds.isEmpty()) {
            return Optional.empty();
        }
        return reading(() -> {
            long remaining = 0;
            for (UUID versionId : new LinkedHashSet<>(versionIds)) {
                for (int[] range : merged(IndexChanges.unended(index, unendedJobs, versionId))) {
                    remaining += materials.countPassages(versionId, range[0], range[1]);
                }
            }
            return remaining == 0 ? Optional.<Indexing>empty()
                    : Optional.of(new Indexing(Math.toIntExact(remaining)));
        });
    }

    /**
     * The ordinal ranges of these runs, overlapping or touching ones merged, by first ordinal: a passage is counted
     * once even if two runs were ever to cover it ({@code IndexChanges} cuts a version's runs apart).
     */
    private static List<int[]> merged(List<IndexChangeRow> runs) {
        List<IndexChangeRow> sorted = runs.stream()
                .sorted(Comparator.comparingInt(IndexChangeRow::firstOrdinal)).toList();
        List<int[]> ranges = new ArrayList<>();
        for (IndexChangeRow run : sorted) {
            int[] last = ranges.isEmpty() ? null : ranges.getLast();
            if (last != null && run.firstOrdinal() <= last[1] + 1) {
                last[1] = Math.max(last[1], run.lastOrdinal());
            } else {
                ranges.add(new int[] {run.firstOrdinal(), run.lastOrdinal()});
            }
        }
        return ranges;
    }

    /**
     * The owner's passages with these ids, decrypted, in the order given, each once; empty, with nothing decrypted, when
     * any id is not a passage of the owner, not of one of {@code versionIds}, or of a version that is not {@code ready}.
     */
    public Optional<List<Passage>> passages(List<UUID> ids, Set<UUID> versionIds) {
        List<UUID> wanted = List.copyOf(new LinkedHashSet<>(ids));
        if (wanted.isEmpty()) {
            return Optional.of(List.of());
        }
        return reading(() -> {
            Map<UUID, PassageRow> found = materials.findPassages(wanted).stream()
                    .collect(Collectors.toMap(PassageRow::id, row -> row, (first, second) -> first));
            if (found.size() != wanted.size() || !found.keySet().containsAll(wanted)) {
                return Optional.empty();
            }
            Set<UUID> versions = found.values().stream().map(PassageRow::versionId).collect(Collectors.toSet());
            if (!versionIds.containsAll(versions) || !versions.stream().allMatch(this::ready)) {
                return Optional.empty();
            }
            List<Passage> read = new ArrayList<>(wanted.size());
            for (UUID id : wanted) {
                read.add(decrypt(found.get(id)));
            }
            return Optional.of(List.copyOf(read));
        });
    }

    /**
     * Where the owner's passages with these ids stand, in the order given, each once; an id that is not a passage of
     * the owner, not of one of {@code versionIds}, or of a version that is not {@code ready} is left out. No passage
     * text is decrypted.
     */
    public List<Place> places(List<UUID> ids, Set<UUID> versionIds) {
        List<UUID> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty() || versionIds.isEmpty()) {
            return List.of();
        }
        return reading(() -> {
            Map<UUID, PassageRow> found = materials.findPassages(wanted).stream()
                    .collect(Collectors.toMap(PassageRow::id, row -> row, (first, second) -> first));
            Map<UUID, Optional<MaterialRow>> materialOfVersion = new HashMap<>();
            Map<UUID, String> names = new HashMap<>();
            List<Place> places = new ArrayList<>();
            for (UUID id : wanted) {
                PassageRow row = found.get(id);
                if (row == null || !versionIds.contains(row.versionId())) {
                    continue;
                }
                Optional<MaterialRow> material = materialOfVersion.computeIfAbsent(row.versionId(), version ->
                        materials.findVersion(version).filter(candidate -> Materials.READY.equals(candidate.state()))
                                .flatMap(ready -> materials.findMaterial(ready.materialId())));
                if (material.isEmpty()) {
                    continue;
                }
                MaterialRow source = material.get();
                String name = names.computeIfAbsent(source.id(), key -> vault.decryptText(new Binding(Uploads.MATERIAL,
                        Uploads.FIELD_NAME, source.id(), source.ownerId()), source.name()));
                Passages.Place place = Passages.place(vault.decrypt(Passages.place(row.id(), row.ownerId()),
                        row.place()));
                places.add(new Place(row.id(), row.versionId(), source.id(), name, place.sectionPath(), row.page()));
            }
            return List.copyOf(places);
        });
    }

    /**
     * Which of these versions are removed for the owner: gone (or another owner's), {@code discarded}, with their
     * removal pending, or of a material that is gone.
     */
    public Set<UUID> removedVersions(Collection<UUID> versionIds) {
        if (versionIds.isEmpty()) {
            return Set.of();
        }
        return reading(() -> {
            Set<UUID> removed = new HashSet<>();
            for (UUID versionId : new LinkedHashSet<>(versionIds)) {
                if (removed(materials.findVersion(versionId).orElse(null))) {
                    removed.add(versionId);
                }
            }
            return Set.copyOf(removed);
        });
    }

    /**
     * Page {@code page} of the owner's version {@code versionId} of material {@code materialId}, as the class says;
     * empty when the version is not that material's, is removed or not {@code ready} or {@code superseded}, the page
     * is outside 1 to the page total, or it has no view (unless it is unreadable).
     */
    public Optional<VersionPage> versionPage(UUID materialId, UUID versionId, int page) {
        return reading(() -> cited(materialId, versionId, page).flatMap(version -> pages.versionPage(version, page))
                .map(ScopeReads::page));
    }

    /** The picture of that page, as {@link #versionPage} finds it; empty also for a page with no picture. */
    public Optional<PagePicture> versionPicture(UUID materialId, UUID versionId, int page) {
        return reading(() -> cited(materialId, versionId, page)
                .flatMap(version -> pages.versionPicture(version, page)).map(PagePicture::new));
    }

    private Optional<VersionRow> cited(UUID materialId, UUID versionId, int page) {
        if (pages == null || materialId == null || versionId == null) {
            return Optional.empty();
        }
        return materials.findVersion(versionId).filter(row -> !removed(row))
                .filter(row -> materialId.equals(row.materialId())).filter(row -> CITED.contains(row.state()))
                .filter(row -> Materials.hasPage(row, page));
    }

    /**
     * The removed rule (Story 2.5): gone (null: unknown or another owner's), discarded, pending removal, or of a
     * material that is gone.
     */
    private boolean removed(VersionRow version) {
        return version == null || version.removalPending() || Uploads.DISCARDED.equals(version.state())
                || materials.findMaterial(version.materialId()).isEmpty();
    }

    private static VersionPage page(Materials.VersionPage page) {
        return new VersionPage(page.page(), page.pageTotal(),
                page.picture() == null ? null : page.picture().width(),
                page.picture() == null ? null : page.picture().height(),
                page.lines().stream().map(line -> new PageLine(line.text(), line.box())).toList(), page.text(),
                page.passages().stream().map(place -> new PagePlace(place.id(), place.boxes(), place.range())).toList(),
                page.outcome());
    }

    private boolean ready(UUID versionId) {
        return materials.findVersion(versionId).map(VersionRow::state).filter(Materials.READY::equals).isPresent();
    }

    private Passage decrypt(PassageRow row) {
        Passages.Place place = Passages.place(vault.decrypt(Passages.place(row.id(), row.ownerId()), row.place()));
        return new Passage(row.id(), row.versionId(), place.sectionPath(), row.page(),
                vault.decryptText(Passages.text(row.id(), row.ownerId()), row.text()));
    }

    private static List<UUID> ids(List<VersionRow> versions) {
        return List.copyOf(new LinkedHashSet<>(versions.stream().map(VersionRow::id).toList()));
    }

    /** One read-only transaction (or the caller's) for the reads of one answer, as the bound owner. */
    private <T> T reading(Supplier<T> read) {
        OwnerContext.require();
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }
}

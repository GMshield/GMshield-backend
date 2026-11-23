package io.github.gmshield.materials.service;

import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The library reads a system's records list and record page need (Story 4.3), for the {@code knowledge} module, which
 * may not use {@link ScopeReads} (AD-9, {@code ModuleBoundaryTest}): the names of the materials of some {@code ready}
 * versions, where a record's passages stand, and a page of a record's version with its picture. Every read acts for the
 * owner of the work ({@link OwnerContext}), in a read transaction (the caller's, when there is one), so another owner's
 * version or passage reads exactly like an unknown one. Which versions a system may show is the caller's
 * ({@link ReadyVersions#ofSystem}); each read here checks again that a version is {@code ready}. Nothing here logs; a
 * name or a section title never leaves but in these records, whose {@code toString} names ids and numbers only.
 */
@Service
@NamedInterface("service")
public class RecordSources {

    private final MaterialStore materials;
    private final ScopeReads reads;
    private final Vault vault;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    RecordSources(MaterialStore materials, ScopeReads reads, Vault vault,
                  ObjectProvider<PlatformTransactionManager> transactions) {
        this.materials = materials;
        this.reads = reads;
        this.vault = vault;
        this.transactions = transactions;
    }

    /**
     * Where a record's passage stands: its id, its version, the version's material's name, its section path
     * (outermost first) and its page. {@code toString} names ids and the page only.
     */
    @NamedInterface("service")
    public record Place(UUID passageId, UUID versionId, String materialName, List<String> sectionPath, int page) {
        public Place {
            sectionPath = List.copyOf(sectionPath);
        }

        @Override
        public String toString() {
            return "Place[passageId=" + passageId + ", versionId=" + versionId + ", page=" + page + "]";
        }
    }

    /**
     * One page of a record's version, as {@link ScopeReads.VersionPage} has it: its number and the version's page total,
     * its picture's size (both null for a page with no picture), its lines or its text, its passages' places in reading
     * order, and its outcome. {@code toString} names numbers only.
     */
    @NamedInterface("service")
    public record Page(int page, int pageTotal, Integer pictureWidth, Integer pictureHeight, List<Line> lines,
                       String text, List<Spot> passages, String outcome) {
        public Page {
            lines = List.copyOf(lines);
            passages = List.copyOf(passages);
        }

        @Override
        public String toString() {
            return "Page[page=" + page + ", pageTotal=" + pageTotal + "]";
        }
    }

    /** A text line of a picture page and its box, {@code [left, top, right, bottom]} as fractions of the picture. */
    @NamedInterface("service")
    public record Line(String text, List<Double> box) {
        @Override
        public String toString() {
            return "Line[box=" + box + "]";
        }
    }

    /** A passage's place on its page: its boxes (a picture page), or its code-point range (a text page). */
    @NamedInterface("service")
    public record Spot(UUID id, List<List<Double>> boxes, List<Integer> range) {
    }

    /** A page's picture: WebP, streamed one decrypted piece at a time. */
    @NamedInterface("service")
    public static final class Picture {
        private final ScopeReads.PagePicture picture;

        Picture(ScopeReads.PagePicture picture) {
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
            return "Picture[size=" + picture.size() + "]";
        }
    }

    /**
     * The name of the material of each of the owner's {@code ready} versions with these ids, decrypted once per material,
     * by version id; a version that is not the owner's, not {@code ready} or whose material is gone is left out.
     */
    public Map<UUID, String> materialNames(Collection<UUID> versionIds) {
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        return reading(() -> {
            Map<UUID, String> byMaterial = new HashMap<>();
            Map<UUID, String> names = new LinkedHashMap<>();
            for (UUID versionId : new LinkedHashSet<>(versionIds)) {
                ready(versionId).flatMap(version -> materials.findMaterial(version.materialId())).ifPresent(material ->
                        names.put(versionId, byMaterial.computeIfAbsent(material.id(), id -> name(material))));
            }
            return Map.copyOf(names);
        });
    }

    /**
     * Where the owner's passages with these ids stand, in the order given, each once; an id that is not a passage of the
     * owner, not of one of {@code versionIds}, or of a version that is not {@code ready} is left out. No passage text is
     * decrypted.
     */
    public List<Place> places(List<UUID> passageIds, Set<UUID> versionIds) {
        return reading(() -> reads.places(passageIds, versionIds).stream().map(place -> new Place(place.passageId(),
                place.versionId(), place.materialName(), place.sectionPath(), place.page())).toList());
    }

    /**
     * Page {@code page} of the owner's {@code ready} version {@code versionId}; empty when the version is not the
     * owner's, is removed or not {@code ready}, the page is outside 1 to the page total, or it has no view (unless it is
     * unreadable).
     */
    public Optional<Page> page(UUID versionId, int page) {
        return reading(() -> ready(versionId).flatMap(version -> reads.versionPage(version.materialId(), versionId, page))
                .map(RecordSources::page));
    }

    /** The picture of that page, as {@link #page} finds it; empty also for a page with no picture. */
    public Optional<Picture> picture(UUID versionId, int page) {
        return reading(() -> ready(versionId)
                .flatMap(version -> reads.versionPicture(version.materialId(), versionId, page)).map(Picture::new));
    }

    private Optional<VersionRow> ready(UUID versionId) {
        return materials.findVersion(versionId).filter(version -> Materials.READY.equals(version.state()));
    }

    private String name(MaterialRow material) {
        return vault.decryptText(new Binding(Uploads.MATERIAL, Uploads.FIELD_NAME, material.id(), material.ownerId()),
                material.name());
    }

    private static Page page(ScopeReads.VersionPage page) {
        return new Page(page.page(), page.pageTotal(), page.pictureWidth(), page.pictureHeight(),
                page.lines().stream().map(line -> new Line(line.text(), line.box())).toList(), page.text(),
                page.passages().stream().map(spot -> new Spot(spot.id(), spot.boxes(), spot.range())).toList(),
                page.outcome());
    }

    /** One read-only transaction (or the caller's) for the reads of one answer, as the bound owner. */
    private <T> T reading(Supplier<T> read) {
        OwnerContext.require();
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }
}

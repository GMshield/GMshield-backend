package io.github.gmshield.materials.service;

import io.github.gmshield.materials.data.IndexStore;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.PageRangeRow;
import io.github.gmshield.materials.data.PageViewRow;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.data.VersionFileRow;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.vault.service.FileStore;
import io.github.gmshield.vault.service.Vault;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

/** What other packages' tests need of {@code materials.service} that is not public. */
public final class TestMaterials {

    private TestMaterials() {
    }

    /** Records that are never stored (Story 4.2), for the tests that build the services by hand. */
    public static final ExtractedRecords NO_RECORDS = new ExtractedRecords() {
        @Override
        public int replaceRange(UUID versionId, int firstPage, int lastPage, List<Extracted> records) {
            return 0;
        }

        @Override
        public int removeVersion(UUID versionId) {
            return 0;
        }

        @Override
        public java.util.Map<String, Long> countsByType(Collection<UUID> versionIds) {
            return java.util.Map.of();
        }

        @Override
        public List<Integer> firstPages(UUID versionId) {
            return List.of();
        }

        @Override
        public List<Stored> readRange(UUID versionId, int firstPage, int lastPage) {
            return List.of();
        }

        @Override
        public List<Located> located(Collection<UUID> ids) {
            return List.of();
        }

        @Override
        public List<Located> named(Collection<UUID> versionIds, String name) {
            return List.of();
        }

        @Override
        public java.util.Map<UUID, String> names(Collection<UUID> ids) {
            return java.util.Map.of();
        }
    };

    /** {@link Materials#passage}, package-private since Story 2.2, for the HTTP flow tests of {@code materials.web}. */
    public static Optional<Materials.Passage> passage(Materials materials, UUID id) {
        return materials.passage(id);
    }

    /** Working scope reads over given stores; {@code unendedJobs} answers which jobs of the owner have not ended. */
    public static ScopeReads scopeReads(MaterialStore materials, IndexStore index, Vault vault,
                                        Function<Collection<UUID>, Set<UUID>> unendedJobs,
                                        ObjectProvider<PlatformTransactionManager> transactions) {
        return scopeReads(materials, index, vault, unendedJobs, ids -> Set.of(), transactions);
    }

    /** {@link #scopeReads(MaterialStore, IndexStore, Vault, Function, ObjectProvider)}, with which jobs failed. */
    public static ScopeReads scopeReads(MaterialStore materials, IndexStore index, Vault vault,
                                        Function<Collection<UUID>, Set<UUID>> unendedJobs,
                                        Function<Collection<UUID>, Set<UUID>> failedJobs,
                                        ObjectProvider<PlatformTransactionManager> transactions) {
        return new ScopeReads(materials, index, vault, unendedJobs, failedJobs, transactions, null);
    }

    /**
     * A passage row with its place on its page (Story 2.7): its boxes on a picture page, or, when {@code boxes} is null,
     * its code-point {@code range} in a text page's text.
     */
    public static PassageRow passage(Vault vault, UUID id, UUID owner, UUID versionId, int ordinal, int page,
                                     String text, List<String> sectionPath, List<List<Double>> boxes,
                                     List<Integer> range, Instant now) {
        return new PassageRow(id, owner, versionId, ordinal, page, vault.encryptText(Passages.text(id, owner), text),
                vault.encrypt(Passages.place(id, owner), Passages.placeJson(new Passages.Place(sectionPath, boxes,
                        boxes == null ? range : null))), now);
    }

    /** A text line of a picture page, for {@link #picturePage}: its text and its box. */
    public record Line(String text, List<Double> box) {
    }

    /**
     * A picture page as ingestion stores it (Story 1.19), within the caller's owner and transaction: its WebP picture,
     * one piece in the file store, a {@code picture} file of the version, and its view with its lines encrypted.
     */
    public static void picturePage(MaterialStore materials, Vault vault, FileStore files, UUID owner, UUID versionId,
                                   int page, byte[] picture, int width, int height, List<Line> lines, Instant now) {
        UUID fileId = Ids.newId();
        files.writePiece(owner, fileId, 0, picture);
        materials.insertVersionFile(new VersionFileRow(Ids.newId(), owner, versionId, fileId, VersionFileRow.PICTURE,
                page, page, picture.length, 1, now));
        UUID viewId = Ids.newId();
        materials.insertPageView(new PageViewRow(viewId, owner, versionId, page, fileId, width, height,
                vault.encrypt(PageViews.content(viewId, owner), PageViews.contentJson(new PageViews.Content(
                        lines.stream().map(line -> new PageViews.Line(line.text(), line.box())).toList(), null))),
                now));
    }

    /** A text page as ingestion stores it (a TXT or Markdown section): no picture, its text encrypted. */
    public static void textPage(MaterialStore materials, Vault vault, UUID owner, UUID versionId, int page, String text,
                                Instant now) {
        UUID viewId = Ids.newId();
        materials.insertPageView(new PageViewRow(viewId, owner, versionId, page, null, null, null,
                vault.encrypt(PageViews.content(viewId, owner), PageViews.contentJson(new PageViews.Content(List.of(),
                        text))), now));
    }

    /** The outcome of pages {@code first} to {@code last} of a version. */
    public static void outcome(MaterialStore materials, UUID owner, UUID versionId, int first, int last,
                               String outcome, Instant now) {
        materials.insertPageOutcome(new PageRangeRow(Ids.newId(), owner, versionId, first, last, outcome, now));
    }

    /** A material's name encrypted as an upload stores it (binding {@code material}/{@code name}). */
    public static byte[] materialName(Vault vault, UUID materialId, UUID owner, String name) {
        return vault.encryptText(new io.github.gmshield.vault.service.Binding(Uploads.MATERIAL, Uploads.FIELD_NAME,
                materialId, owner), name);
    }

    /** A passage row as ingestion stores it: its text and its place (the section path, no box) encrypted. */
    public static PassageRow passage(Vault vault, UUID id, UUID owner, UUID versionId, int ordinal, int page,
                                     String text, List<String> sectionPath, Instant now) {
        return new PassageRow(id, owner, versionId, ordinal, page, vault.encryptText(Passages.text(id, owner), text),
                vault.encrypt(Passages.place(id, owner), Passages.placeJson(new Passages.Place(sectionPath,
                        null, List.of(0, text.codePointCount(0, text.length()))))), now);
    }
}

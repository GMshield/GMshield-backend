package io.github.gmshield.materials.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.jobs.data.MutableClock;
import io.github.gmshield.materials.data.InMemoryMaterialStore;
import io.github.gmshield.materials.data.InMemoryUploadStorage;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.UploadRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.data.InMemoryTransactionManager;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.FileStore;
import io.github.gmshield.vault.service.TestVaults;
import io.github.gmshield.vault.service.Vault;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The resumable upload without a database (Story 1.13): every row of the I/O matrix that needs none, against the
 * in-memory stores, a real vault and a real file store in a temporary folder. The same rows against PostgreSQL are in
 * the integration tests, and the HTTP edge in {@code UploadFlowTest}.
 */
class UploadsTest {

    private static final int MIB = 1024 * 1024;

    @TempDir
    Path root;

    private final InMemoryMaterialStore materials = new InMemoryMaterialStore();
    private final InMemoryUploadStorage uploads = new InMemoryUploadStorage();
    private final InMemoryTransactionManager transactions = new InMemoryTransactionManager();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T12:00:00Z"));
    private final Vault vault = TestVaults.vault();
    private final UUID owner = Ids.newId();
    private FileStore files;
    private Uploads service;
    private UploadSweep sweep;

    @BeforeEach
    void wire() {
        files = TestVaults.files(vault, root);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("transactions", transactions);
        beans.addBean("clock", clock);
        service = new Uploads(materials, uploads, vault, files, beans.getBeanProvider(PlatformTransactionManager.class),
                beans.getBeanProvider(Clock.class));
        sweep = new UploadSweep(uploads, materials, service, beans.getBeanProvider(PlatformTransactionManager.class),
                beans.getBeanProvider(Clock.class));
    }

    // --- creation ---

    @Test
    void creationMakesAMaterialAVersionInUploadingAndAnUploadThatExpiresAfterSixHours() {
        Uploads.Created created = as(owner, () -> service.create(Uploads.MAX_LENGTH, "  Livro do Jogador.pdf "));

        UploadRow row = uploads.row(created.uploadId());
        assertThat(row.ownerId()).isEqualTo(owner);
        assertThat(row.length()).isEqualTo(2147483648L);
        assertThat(row.offset()).isZero();
        assertThat(row.state()).isEqualTo(UploadRow.OPEN);
        assertThat(row.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(6)));
        assertThat(created.expiresAt()).isEqualTo(row.expiresAt());
        VersionRow version = materials.versions.get(row.versionId());
        assertThat(version.state()).isEqualTo("uploading");
        assertThat(version.format()).isNull();
        MaterialRow material = materials.materials.get(version.materialId());
        assertThat(material.ownerId()).isEqualTo(owner);
        assertThat(new String(material.name(), StandardCharsets.ISO_8859_1)).doesNotContain("Livro");
        assertThat(vault.decryptText(new Binding("material", "name", material.id(), owner), material.name()))
                .isEqualTo("Livro do Jogador.pdf");
    }

    @Test
    void aLengthOutsideOneByteToTwoGibibytesIsTooLarge() {
        for (long length : new long[] {0, -1, Uploads.MAX_LENGTH + 1}) {
            assertThatThrownBy(() -> as(owner, () -> service.create(length, "a.pdf")))
                    .satisfies(problem("upload.too_large", 413));
        }
        assertThat(uploads.rows).isEmpty();
        assertThat(materials.materials).isEmpty();
    }

    @Test
    void aMissingBlankLongOrControlledNameIsInvalidMetadata() {
        for (String name : new String[] {null, "", "   ", "a\u0000b.txt", "line\nbreak.txt", "x".repeat(256)}) {
            assertThatThrownBy(() -> as(owner, () -> service.create(10, name)))
                    .satisfies(problem("upload.metadata_invalid", 400));
        }
        assertThat(as(owner, () -> service.create(10, "é".repeat(255)))).isNotNull();
    }

    // --- the format ---

    @Test
    void aZipIsRefusedOnItsFirstPartAndNoRowAndNoPieceRemains() {
        byte[] zip = concat(new byte[] {'P', 'K', 3, 4}, "[Content_Types].xml".getBytes(StandardCharsets.US_ASCII),
                new byte[5000]);
        UUID id = create(zip.length, "rules.pdf");
        UploadRow row = uploads.row(id);

        assertThatThrownBy(() -> patch(id, 0, zip)).satisfies(problem("upload.format_refused", 415))
                .satisfies(thrown -> assertThat(((ApiException) thrown).problem().params())
                        .isEqualTo(Map.of("accepted", List.of("pdf", "txt", "markdown", "jpeg", "png", "webp", "tiff", "bmp",
                                "heic"))));

        assertThat(uploads.rows).isEmpty();
        assertThat(materials.versions).doesNotContainKey(row.versionId());
        assertThat(materials.materials).isEmpty();
        assertThat(files.fileExists(owner, id)).isFalse();
        assertThat(storedFiles()).isEmpty();
    }

    @Test
    void textIsMarkdownOrPlainTextByItsNameAndBinaryNamedMarkdownIsRefused() {
        assertThat(uploadWhole("# Rules\nRoll 2d6.\n".getBytes(StandardCharsets.UTF_8), "rules.md").format())
                .isEqualTo("markdown");
        assertThat(uploadWhole("Rules: roll 2d6.\n".getBytes(StandardCharsets.UTF_8), "rules.txt").format())
                .isEqualTo("txt");
        byte[] binary = concat("# Rules".getBytes(StandardCharsets.US_ASCII), new byte[] {0, 1, 2, 3});
        UUID id = create(binary.length, "x.md");
        assertThatThrownBy(() -> patch(id, 0, binary)).satisfies(problem("upload.format_refused", 415));
        assertThat(uploads.rows).doesNotContainKey(id);
    }

    @Test
    void theFormatIsDecidedOnceFromTheFirstPartAndAnEmptyFirstPartDecidesNothing() {
        byte[] pdf = pdf(10_000);
        UUID id = create(pdf.length, "book.pdf");

        assertThat(patch(id, 0, new byte[0]).offset()).isZero();
        assertThat(uploads.row(id).format()).isNull();
        patch(id, 0, java.util.Arrays.copyOf(pdf, 6000));
        assertThat(uploads.row(id).format()).isEqualTo("pdf");
        // The second part is not sniffed: binary bytes in the middle of a PDF are fine.
        patch(id, 6000, java.util.Arrays.copyOfRange(pdf, 6000, pdf.length));
        assertThat(complete(id, pdf).state()).isEqualTo("waiting");
    }

    // --- parts ---

    @Test
    void aVerifiedPartIsCountedAndStoredEncryptedInFourMebibytePieces() throws IOException {
        byte[] content = pdf(9 * MIB + 123);
        UUID id = create(content.length + 10L, "book.pdf");

        Uploads.Progress progress = patch(id, 0, content);

        assertThat(progress.offset()).isEqualTo(content.length);
        UploadRow row = uploads.row(id);
        assertThat(row.offset()).isEqualTo(content.length);
        assertThat(row.pieceCount()).isEqualTo(3);
        assertThat(row.busyToken()).isNull();
        assertThat(row.format()).isEqualTo("pdf");
        assertThat(as(owner, () -> files.readPiece(owner, id, 0))).hasSize(4 * MIB);
        assertThat(as(owner, () -> files.readPiece(owner, id, 2))).hasSize(MIB + 123);
        assertThat(readBack(id, 3)).isEqualTo(content);
        for (Path piece : storedFiles()) {
            assertThat(new String(Files.readAllBytes(piece), StandardCharsets.ISO_8859_1)).doesNotContain("PLAINTEXT-MARK");
        }
    }

    @Test
    void aWrongChecksumLeavesTheOffsetAndTheNextAttemptOverwritesThePieces() {
        byte[] content = pdf(5 * MIB);
        UUID id = create(content.length, "book.pdf");

        assertThatThrownBy(() -> as(owner, () -> service.patch(id, 0, content.length, sha256(new byte[] {1}),
                new ByteArrayInputStream(content)))).satisfies(problem("upload.part_checksum_mismatch", 460));
        assertThat(uploads.row(id).offset()).isZero();
        assertThat(uploads.row(id).pieceCount()).isZero();
        assertThat(uploads.row(id).busyToken()).isNull();

        assertThat(patch(id, 0, content).offset()).isEqualTo(content.length);
        assertThat(readBack(id, 2)).isEqualTo(content);
    }

    @Test
    void anOffsetOtherThanTheStoredOneWritesNothing() {
        byte[] content = pdf(1000);
        UUID id = create(content.length, "book.pdf");

        assertThatThrownBy(() -> patch(id, 10, content)).satisfies(problem("upload.offset_conflict", 409));
        assertThat(files.fileExists(owner, id)).isFalse();
        patch(id, 0, java.util.Arrays.copyOf(content, 500));
        assertThatThrownBy(() -> patch(id, 0, content)).satisfies(problem("upload.offset_conflict", 409));
        assertThat(uploads.row(id).offset()).isEqualTo(500);
    }

    @Test
    void aBodyCutHalfWayLeavesTheOffsetAndTheResentPartSucceeds() {
        byte[] content = pdf(6 * MIB);
        UUID id = create(content.length, "book.pdf");
        patch(id, 0, java.util.Arrays.copyOf(content, 2 * MIB));
        byte[] second = java.util.Arrays.copyOfRange(content, 2 * MIB, content.length);

        assertThatThrownBy(() -> as(owner, () -> service.patch(id, 2 * MIB, second.length, sha256(second),
                cutAfter(second, 3 * MIB)))).satisfies(problem("request.invalid", 400));

        assertThat(as(owner, () -> service.head(id)).offset()).isEqualTo(2 * MIB);
        assertThat(uploads.row(id).busyToken()).isNull();
        assertThat(patch(id, 2 * MIB, second).offset()).isEqualTo(content.length);
        assertThat(readBack(id, uploads.row(id).pieceCount())).isEqualTo(content);
        assertThat(complete(id, content).state()).isEqualTo("waiting");
    }

    @Test
    void aSecondPartDuringALiveLeaseIsRefusedUntilTheLeaseLapses() {
        byte[] content = pdf(1000);
        UUID id = create(content.length, "book.pdf");
        assertThat(uploads.joined().takeLease(id, owner, 0, UUID.randomUUID(), clock.instant().plus(Duration.ofMinutes(2)),
                clock.instant())).isTrue();

        assertThatThrownBy(() -> patch(id, 0, content)).satisfies(problem("upload.busy", 423));
        assertThatThrownBy(() -> complete(id, content)).satisfies(problem("upload.incomplete", 409));
        assertThat(files.fileExists(owner, id)).isFalse();

        clock.advance(Duration.ofMinutes(2));
        assertThat(patch(id, 0, content).offset()).isEqualTo(content.length);
    }

    @Test
    void aPartOverNinetyMebibytesOrPastTheLengthIsTooLargeBeforeAnythingIsWritten() {
        byte[] content = pdf(1000);
        UUID id = create(Uploads.MAX_LENGTH, "book.pdf");
        assertThatThrownBy(() -> as(owner, () -> service.patch(id, 0, 90L * MIB + 1, sha256(content),
                new ByteArrayInputStream(content)))).satisfies(problem("upload.too_large", 413));
        UUID small = create(500, "small.pdf");
        assertThatThrownBy(() -> patch(small, 0, content)).satisfies(problem("upload.too_large", 413));
        assertThat(files.fileExists(owner, id)).isFalse();
        assertThat(files.fileExists(owner, small)).isFalse();
        // Without a declared length, the body itself is held to the limit, and nothing of it counts.
        assertThatThrownBy(() -> as(owner, () -> service.patch(small, 0, -1, sha256(content),
                new ByteArrayInputStream(content)))).satisfies(problem("upload.too_large", 413));
        assertThat(uploads.row(small).offset()).isZero();
        assertThat(uploads.row(small).busyToken()).isNull();
        assertThat(as(owner, () -> service.patch(id, 0, -1, sha256(content), new ByteArrayInputStream(content))).offset())
                .isEqualTo(1000);
    }

    @Test
    void anUploadResumesFromItsLastVerifiedPartWithoutResendingEarlierParts() {
        byte[] content = pdf(10 * MIB + 7);
        UUID id = create(content.length, "book.pdf");
        int part = 4 * MIB + 5;
        patch(id, 0, java.util.Arrays.copyOf(content, part));

        Uploads.Progress progress = as(owner, () -> service.head(id));
        assertThat(progress.offset()).isEqualTo(part);
        assertThat(progress.length()).isEqualTo(content.length);
        patch(id, part, java.util.Arrays.copyOfRange(content, part, 2 * part));
        patch(id, 2 * part, java.util.Arrays.copyOfRange(content, 2 * part, content.length));

        assertThat(uploads.row(id).pieceCount()).isEqualTo(5);
        assertThat(readBack(id, 5)).isEqualTo(content);
    }

    // --- completion ---

    @Test
    void completionChecksTheWholeFileAndTheVersionWaitsWithItsFormatAndSize() {
        byte[] content = pdf(5 * MIB);
        UUID id = create(content.length, "book.pdf");
        patch(id, 0, java.util.Arrays.copyOf(content, MIB));
        assertThatThrownBy(() -> complete(id, content)).satisfies(problem("upload.incomplete", 409));
        patch(id, MIB, java.util.Arrays.copyOfRange(content, MIB, content.length));

        Uploads.Completed completed = complete(id, content);

        VersionRow version = materials.versions.get(completed.versionId());
        assertThat(completed.state()).isEqualTo("waiting");
        assertThat(completed.materialId()).isEqualTo(version.materialId());
        assertThat(version.state()).isEqualTo("waiting");
        assertThat(version.format()).isEqualTo("pdf");
        assertThat(version.sizeBytes()).isEqualTo(content.length);
        assertThat(uploads.row(id).state()).isEqualTo(UploadRow.COMPLETE);
        // The pieces stay as the raw upload for the first ingestion step.
        assertThat(readBack(id, uploads.row(id).pieceCount())).isEqualTo(content);
        // The same call again (its answer was lost) gives the same answer; no further part is taken.
        assertThat(complete(id, content)).isEqualTo(completed);
        assertThat(as(owner, () -> service.head(id)).offset()).isEqualTo(content.length);
        assertThatThrownBy(() -> patch(id, 0, content)).satisfies(problem("upload.offset_conflict", 409));
    }

    @Test
    void aWrongWholeFileChecksumDiscardsTheVersionAndRemovesThePieces() {
        byte[] content = pdf(3 * MIB);
        UUID id = create(content.length, "book.pdf");
        patch(id, 0, content);
        UUID versionId = uploads.row(id).versionId();

        assertThatThrownBy(() -> as(owner, () -> service.complete(id, sha256("other".getBytes(StandardCharsets.UTF_8)))))
                .satisfies(problem("upload.file_checksum_mismatch", 422));

        assertThat(materials.versions.get(versionId).state()).isEqualTo("discarded");
        assertThat(uploads.rows).doesNotContainKey(id);
        assertThat(files.fileExists(owner, id)).isFalse();
        assertThatThrownBy(() -> as(owner, () -> service.head(id))).satisfies(problem("request.not_found", 404));
    }

    // --- expiry and removal ---

    @Test
    void afterSixHoursEveryRequestIsExpiredAndTheSweepDiscardsTheVersionWithItsFilesAndRow() {
        byte[] content = pdf(2 * MIB);
        UUID id = create(content.length, "book.pdf");
        patch(id, 0, java.util.Arrays.copyOf(content, MIB));
        UUID versionId = uploads.row(id).versionId();
        clock.advance(Duration.ofHours(6).minusSeconds(1));
        assertThat(sweep.sweep()).isZero();
        clock.advance(Duration.ofSeconds(1));

        assertThatThrownBy(() -> as(owner, () -> service.head(id))).satisfies(problem("upload.expired", 410));
        assertThatThrownBy(() -> patch(id, MIB, java.util.Arrays.copyOfRange(content, MIB, content.length)))
                .satisfies(problem("upload.expired", 410));
        assertThatThrownBy(() -> complete(id, content)).satisfies(problem("upload.expired", 410));

        assertThat(sweep.sweep()).isEqualTo(1);
        assertThat(materials.versions.get(versionId).state()).isEqualTo("discarded");
        assertThat(files.fileExists(owner, id)).isFalse();
        assertThat(uploads.rows).doesNotContainKey(id);
        assertThatThrownBy(() -> as(owner, () -> service.head(id))).satisfies(problem("request.not_found", 404));
    }

    @Test
    void theSweepWaitsForALiveLeaseAndFinishesARemovalThatStoppedAfterAnyStep() {
        UUID leased = create(100, "a.txt");
        patch(leased, 0, "half".getBytes(StandardCharsets.UTF_8));
        UUID token = UUID.randomUUID();
        clock.advance(Duration.ofHours(6).minusMinutes(1));
        assertThat(uploads.joined().takeLease(leased, owner, 4, token, clock.instant().plus(Duration.ofMinutes(2)),
                clock.instant())).isTrue();
        clock.advance(Duration.ofMinutes(1));
        assertThat(sweep.sweep()).isZero();
        assertThat(uploads.row(leased).state()).isEqualTo(UploadRow.OPEN);
        clock.advance(Duration.ofMinutes(1));
        assertThat(sweep.sweep()).isEqualTo(1);
        assertThat(uploads.rows).doesNotContainKey(leased);

        // Stopped after step 1: the version is discarded, the upload removing, the pieces still there.
        UUID afterOne = create(100, "b.txt");
        patch(afterOne, 0, "half".getBytes(StandardCharsets.UTF_8));
        clock.advance(Duration.ofHours(7));
        assertThat(sweep.start(uploads.row(afterOne), clock.instant())).isTrue();
        assertThat(files.fileExists(owner, afterOne)).isTrue();
        // Stopped after step 2: the pieces are gone, the row is still there.
        UUID afterTwo = create(100, "c.txt");
        patch(afterTwo, 0, "half".getBytes(StandardCharsets.UTF_8));
        clock.advance(Duration.ofHours(7));
        assertThat(sweep.start(uploads.row(afterTwo), clock.instant())).isTrue();
        files.deleteFile(owner, afterTwo);

        assertThat(sweep.sweep()).isEqualTo(2);
        assertThat(uploads.rows).isEmpty();
        assertThat(storedFiles()).isEmpty();
        assertThat(materials.versions.values()).allMatch(version -> version.state().equals("discarded"));
    }

    // --- owners ---

    @Test
    void anotherOwnersUploadIsTheSameNotFoundAsAnUnknownOne() {
        byte[] content = pdf(1000);
        UUID id = create(content.length, "book.pdf");
        UUID other = Ids.newId();

        for (UUID upload : List.of(id, Ids.newId())) {
            assertThatThrownBy(() -> as(other, () -> service.head(upload))).satisfies(problem("request.not_found", 404));
            assertThatThrownBy(() -> as(other, () -> service.patch(upload, 0, content.length, sha256(content),
                    new ByteArrayInputStream(content)))).satisfies(problem("request.not_found", 404));
            assertThatThrownBy(() -> as(other, () -> service.complete(upload, sha256(content))))
                    .satisfies(problem("request.not_found", 404));
        }
        assertThat(uploads.row(id).offset()).isZero();
        assertThat(files.fileExists(other, id)).isFalse();
        assertThat(files.fileExists(owner, id)).isFalse();
        assertThatThrownBy(() -> service.head(id)).isInstanceOf(OwnerContext.NoOwnerException.class);
    }

    // --- helpers ---

    private UUID create(long length, String name) {
        return as(owner, () -> service.create(length, name)).uploadId();
    }

    private Uploads.Progress patch(UUID id, long offset, byte[] part) {
        return as(owner, () -> service.patch(id, offset, part.length, sha256(part), new ByteArrayInputStream(part)));
    }

    private Uploads.Completed complete(UUID id, byte[] content) {
        return as(owner, () -> service.complete(id, sha256(content)));
    }

    private VersionRow uploadWhole(byte[] content, String name) {
        UUID id = create(content.length, name);
        patch(id, 0, content);
        return materials.versions.get(complete(id, content).versionId());
    }

    private byte[] readBack(UUID id, int pieces) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < pieces; i++) {
            out.writeBytes(files.readPiece(owner, id, i));
        }
        return out.toByteArray();
    }

    private List<Path> storedFiles() {
        try (Stream<Path> all = Files.walk(root)) {
            return all.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static <T> T as(UUID owner, Callable<T> work) {
        try {
            return OwnerContext.callAs(owner, work::call);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.util.function.Consumer<Throwable> problem(String code, int status) {
        return thrown -> {
            assertThat(thrown).isInstanceOf(ApiException.class);
            assertThat(((ApiException) thrown).problem().code()).isEqualTo(code);
            assertThat(((ApiException) thrown).problem().status()).isEqualTo(status);
        };
    }

    /** A PDF of {@code size} bytes: the signature, a marker, then bytes of every value. */
    static byte[] pdf(int size) {
        byte[] content = new byte[size];
        byte[] head = "%PDF-1.7\nPLAINTEXT-MARK\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(head, 0, content, 0, Math.min(head.length, size));
        for (int i = head.length; i < size; i++) {
            content[i] = (byte) (i * 31 + (i >>> 11));
        }
        return content;
    }

    static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** A body whose connection breaks after {@code limit} bytes. */
    private static InputStream cutAfter(byte[] content, int limit) {
        return new InputStream() {
            private int position;

            @Override
            public int read() throws IOException {
                if (position >= limit) {
                    throw new IOException("connection reset");
                }
                return content[position++] & 0xFF;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (position >= limit) {
                    throw new IOException("connection reset");
                }
                int count = Math.min(length, limit - position);
                System.arraycopy(content, position, buffer, offset, count);
                position += count;
                return count;
            }
        };
    }
}

package io.github.gmshield.vault.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.vault.data.InMemoryDataKeyStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The encrypted file store: pieces bound to owner, file and index (I/O matrix of Story 1.7). */
class FileStoreTest {

    private static final byte[] PAGE = "Page 12: the dragon's lair, map and treasure".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    private final Vault vault = VaultFixtures.vault(new InMemoryDataKeyStore(), VaultFixtures.keys());
    private final UUID owner = Ids.newId();
    private final UUID file = Ids.newId();

    @Test
    void pieceRoundTripsAndIsStoredEncryptedWithoutATemporaryLeftBehind() throws IOException {
        FileStore files = VaultFixtures.files(vault, root.resolve("files"));

        files.writePiece(owner, file, 0, PAGE);
        files.writePiece(owner, file, 1, new byte[0]);

        assertThat(files.readPiece(owner, file, 0)).isEqualTo(PAGE);
        assertThat(files.readPiece(owner, file, 1)).isEmpty();
        Path piece = files.piece(owner, file, 0);
        assertThat(piece).isEqualTo(root.resolve("files").resolve(owner.toString()).resolve(file.toString()).resolve("0.piece"));
        assertThat(new String(Files.readAllBytes(piece), StandardCharsets.ISO_8859_1)).doesNotContain("dragon");
        try (Stream<Path> all = Files.walk(root)) {
            assertThat(all.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder("0.piece", "1.piece");
        }
        if (root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(piece))).isEqualTo("rw-------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(piece.getParent()))).isEqualTo("rwx------");
        }
    }

    @Test
    void rewritingAPieceReplacesIt() {
        FileStore files = VaultFixtures.files(vault, root);
        files.writePiece(owner, file, 0, "first".getBytes(StandardCharsets.UTF_8));
        files.writePiece(owner, file, 0, PAGE);

        assertThat(files.readPiece(owner, file, 0)).isEqualTo(PAGE);
    }

    @Test
    void pieceMovedToAnotherIndexFileOrOwnerGivesNoBytes() throws IOException {
        FileStore files = VaultFixtures.files(vault, root);
        files.writePiece(owner, file, 0, PAGE);
        UUID otherOwner = Ids.newId();
        UUID otherFile = Ids.newId();
        files.writePiece(otherOwner, Ids.newId(), 0, PAGE);     // the other owner has a data key too
        byte[] sealed = Files.readAllBytes(files.piece(owner, file, 0));

        for (Path moved : List.of(files.piece(owner, file, 1), files.piece(owner, otherFile, 0),
                files.piece(otherOwner, file, 0))) {
            Files.createDirectories(moved.getParent());
            Files.write(moved, sealed);
        }

        assertThatThrownBy(() -> files.readPiece(owner, file, 1)).isInstanceOf(IntegrityException.class);
        assertThatThrownBy(() -> files.readPiece(owner, otherFile, 0)).isInstanceOf(IntegrityException.class);
        assertThatThrownBy(() -> files.readPiece(otherOwner, file, 0)).isInstanceOf(IntegrityException.class);
        assertThat(files.readPiece(owner, file, 0)).isEqualTo(PAGE);
    }

    @Test
    void changedOrTruncatedPieceGivesNoBytes() throws IOException {
        FileStore files = VaultFixtures.files(vault, root);
        files.writePiece(owner, file, 0, PAGE);
        Path piece = files.piece(owner, file, 0);
        byte[] sealed = Files.readAllBytes(piece);

        byte[] changed = sealed.clone();
        changed[sealed.length / 2] ^= 0x40;
        Files.write(piece, changed);
        assertThatThrownBy(() -> files.readPiece(owner, file, 0)).isInstanceOf(IntegrityException.class);

        Files.write(piece, java.util.Arrays.copyOf(sealed, sealed.length - 1));
        assertThatThrownBy(() -> files.readPiece(owner, file, 0)).isInstanceOf(IntegrityException.class);
    }

    @Test
    void missingPieceOrNegativeIndexIsRefused() {
        FileStore files = VaultFixtures.files(vault, root);

        assertThatThrownBy(() -> files.readPiece(owner, file, 0)).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> files.writePiece(owner, file, -1, PAGE)).isInstanceOf(IllegalArgumentException.class);
    }
}

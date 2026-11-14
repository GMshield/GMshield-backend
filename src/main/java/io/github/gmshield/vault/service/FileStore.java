package io.github.gmshield.vault.service;

import io.github.gmshield.shared.service.Ids;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/**
 * The encrypted file store under {@code GMSHIELD_FILES_DIR}: each piece of a file is one file,
 * {@code <owner>/<file>/<index>.piece}, encrypted by the {@link Vault} and bound to the owner, the
 * file id and the piece index. A piece is written under a temporary name and then renamed, so a
 * reader never sees half a piece.
 */
public class FileStore {

    static final String ENTITY = "file_piece";
    private static final String SUFFIX = ".piece";

    private final Vault vault;
    private final Path root;

    FileStore(Vault vault, Path root) {
        this.vault = vault;
        this.root = root.toAbsolutePath().normalize();
    }

    public void writePiece(UUID ownerId, UUID fileId, int index, byte[] content) {
        byte[] sealed = vault.encrypt(binding(ownerId, fileId, index), content);
        Path target = piece(ownerId, fileId, index);
        Path temporary = target.resolveSibling("." + index + "." + Ids.newId() + ".tmp");
        try {
            createPrivateDirectories(target.getParent());
            try (FileChannel channel = FileChannel.open(temporary, java.util.Set.of(StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE), ownerOnly(false))) {
                ByteBuffer buffer = ByteBuffer.wrap(sealed);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            deleteQuietly(temporary);
            throw new UncheckedIOException("file piece not written", e);
        }
    }

    /** The piece's bytes; a piece changed, truncated or moved from another index, file or owner fails. */
    public byte[] readPiece(UUID ownerId, UUID fileId, int index) {
        byte[] sealed;
        try {
            sealed = Files.readAllBytes(piece(ownerId, fileId, index));
        } catch (NoSuchFileException missing) {
            throw new UncheckedIOException("file piece missing", missing);
        } catch (IOException e) {
            throw new UncheckedIOException("file piece not read", e);
        }
        return vault.decrypt(binding(ownerId, fileId, index), sealed);
    }

    Path piece(UUID ownerId, UUID fileId, int index) {
        if (index < 0) {
            throw new IllegalArgumentException("a piece index is 0 or more");
        }
        return root.resolve(ownerId.toString()).resolve(fileId.toString()).resolve(index + SUFFIX);
    }

    private static Binding binding(UUID ownerId, UUID fileId, int index) {
        return new Binding(ENTITY, "piece/" + index, fileId, ownerId);
    }

    private void createPrivateDirectories(Path directory) throws IOException {
        if (Files.isDirectory(directory)) {
            return;
        }
        Path parent = directory.getParent();
        if (parent != null) {
            createPrivateDirectories(parent);
        }
        try {
            if (posix(directory)) {
                Files.createDirectory(directory, ownerOnly(true));
            } else {
                Files.createDirectory(directory);
            }
        } catch (FileAlreadyExistsException raced) {
            // Created meanwhile by another write: fine.
        }
    }

    private static boolean posix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private java.nio.file.attribute.FileAttribute<?>[] ownerOnly(boolean directory) {
        if (!posix(root)) {
            return new java.nio.file.attribute.FileAttribute<?>[0];
        }
        return new java.nio.file.attribute.FileAttribute<?>[] {
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"))};
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Nothing more to do: a leftover temporary name is never read.
        }
    }
}

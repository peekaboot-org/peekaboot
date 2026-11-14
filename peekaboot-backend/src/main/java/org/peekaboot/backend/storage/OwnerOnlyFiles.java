package org.peekaboot.backend.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Creates Peekaboot's state directories and files readable by the owning user alone.
 *
 * <p>The snapshot and the lifecycle log describe the host application's runtime, and
 * {@code peekaboot.storage.dir} may point anywhere, so the process umask is not left to
 * decide who else can read them. On a file system without POSIX permissions (Windows) the
 * platform defaults apply. A write never follows a symlink planted at its path. A state file
 * opened for writing replaces whatever sat there, a temporary is created under a name nothing
 * else has, and a lock file is refused while a link sits at its path.
 */
public final class OwnerOnlyFiles {

    /** What a {@link #replaceAtomically} call writes into the file. */
    @FunctionalInterface
    public interface Content {
        void writeTo(OutputStream out) throws IOException;
    }

    private static final String TEMP_SUFFIX = ".tmp";
    // longer than any write takes, so a temporary this much older than a finished write has no writer
    private static final Duration LEFT_BEHIND_AFTER = Duration.ofHours(1);

    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_DIRECTORY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_FILE =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    private static final Set<OpenOption> CREATE_FRESH = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    private static final Set<OpenOption> CREATE_MISSING =
            Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);

    private OwnerOnlyFiles() {}

    /**
     * Writes {@code content} to a sibling temporary and moves it over {@code file} in one
     * step, so a reader sees either the previous file or the whole new one. Creates the
     * parent directory as needed; a temporary a failed write leaves behind is removed, and
     * the failure rethrown.
     *
     * <p>Each call writes a temporary of its own, so two processes that replace one file never
     * move each other's unfinished write into place. A temporary a process died on is removed
     * by a write of the same file more than an hour later.
     */
    public static void replaceAtomically(Path file, Content content) throws IOException {
        Path directory = Objects.requireNonNull(file.toAbsolutePath().getParent(), "file has no directory");
        createDirectories(directory);
        String temporaryPrefix = file.getFileName() + ".";
        Path temp = createTemporary(directory, temporaryPrefix);
        try {
            // a channel, since Files.newOutputStream ignores the interrupt a stopping store ends its write with
            try (OutputStream out = Channels.newOutputStream(
                    Files.newByteChannel(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                content.writeTo(out);
            }
            move(temp, file);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        removeTemporariesLeftBehind(directory, temporaryPrefix, file);
    }

    private static Path createTemporary(Path directory, String prefix) throws IOException {
        try {
            return Files.createTempFile(directory, prefix, TEMP_SUFFIX, OWNER_ONLY_FILE);
        } catch (UnsupportedOperationException e) {
            return Files.createTempFile(directory, prefix, TEMP_SUFFIX);
        }
    }

    private static void removeTemporariesLeftBehind(Path directory, String prefix, Path written) {
        try (DirectoryStream<Path> temporaries =
                Files.newDirectoryStream(directory, entry -> isTemporary(entry, prefix))) {
            FileTime leftBehindBefore =
                    FileTime.from(Files.getLastModifiedTime(written).toInstant().minus(LEFT_BEHIND_AFTER));
            for (Path temporary : temporaries) {
                if (Files.getLastModifiedTime(temporary).compareTo(leftBehindBefore) < 0) {
                    Files.deleteIfExists(temporary);
                }
            }
        } catch (IOException | DirectoryIteratorException ignored) {
            // the file is written; the next write sweeps again
        }
    }

    private static boolean isTemporary(Path entry, String prefix) {
        String name = String.valueOf(entry.getFileName());
        return name.startsWith(prefix) && name.substring(prefix.length()).endsWith(TEMP_SUFFIX);
    }

    private static void move(Path temp, Path file) throws IOException {
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Creates {@code directory} and any missing parent; one that already exists keeps its permissions. */
    public static void createDirectories(Path directory) throws IOException {
        try {
            Files.createDirectories(directory, OWNER_ONLY_DIRECTORY);
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(directory);
        }
    }

    /** Opens a fresh {@code file} for writing, replacing whatever was at that path. */
    public static OutputStream newOutputStream(Path file) throws IOException {
        Files.deleteIfExists(file);
        try {
            return Channels.newOutputStream(Files.newByteChannel(file, CREATE_FRESH, OWNER_ONLY_FILE));
        } catch (UnsupportedOperationException e) {
            return Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
    }

    /**
     * Opens {@code file} to take a lock on it, creating it and its directory as needed. A file
     * that is already there is kept as it is, since a lock holds on the file, not on its path.
     */
    public static FileChannel openLockFile(Path file) throws IOException {
        createDirectories(Objects.requireNonNull(file.toAbsolutePath().getParent(), "file has no directory"));
        try {
            return FileChannel.open(file, CREATE_MISSING, OWNER_ONLY_FILE);
        } catch (UnsupportedOperationException e) {
            return FileChannel.open(file, CREATE_MISSING);
        }
    }
}

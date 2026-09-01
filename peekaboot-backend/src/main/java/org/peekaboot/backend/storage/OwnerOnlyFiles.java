package org.peekaboot.backend.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Creates Peekaboot's state directories and files readable by the owning user alone.
 *
 * <p>The snapshot and the lifecycle log describe the host application's runtime, and
 * {@code peekaboot.storage.dir} may point anywhere, so the process umask is not left to
 * decide who else can read them. On a file system without POSIX permissions (Windows) the
 * platform defaults apply. A file is always created fresh: whatever sits at its path - a
 * symlink planted there included - is removed first, never followed or written through,
 * so a temporary that is later moved into place cannot become a write somewhere else.
 */
public final class OwnerOnlyFiles {

    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_DIRECTORY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_FILE =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    private static final Set<OpenOption> CREATE_FRESH = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

    private OwnerOnlyFiles() {}

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
}

package org.peekaboot.backend.testsupport;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** The POSIX mode of a path as {@code ls} prints it, for the tests that pin owner-only storage. */
public final class PosixPermissions {

    private PosixPermissions() {}

    /** Skips the calling test where the file system has no POSIX modes (Windows). */
    public static void assumeSupported() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    }

    /** The mode of {@code path} as nine characters, {@code rwx------} say. */
    public static String of(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }
}

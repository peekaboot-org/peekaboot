package org.peekaboot.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnerOnlyFilesTest {

    @TempDir
    Path directory;

    @BeforeEach
    void requiresPosixPermissions() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    }

    @Test
    void createsEveryMissingDirectoryReadableByTheOwnerAlone() throws IOException {
        Path nested = directory.resolve("outer").resolve("inner");

        OwnerOnlyFiles.createDirectories(nested);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(nested)))
                .isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(nested.getParent())))
                .isEqualTo("rwx------");
    }

    @Test
    void createsAFileReadableByTheOwnerAlone() throws IOException {
        Path file = directory.resolve("state.tmp");

        write(file, "content");

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-------");
        assertThat(Files.readString(file)).isEqualTo("content");
    }

    @Test
    void replacesALeftoverFileAtThePath() throws IOException {
        Path file = directory.resolve("state.tmp");
        write(file, "first");

        write(file, "second");

        assertThat(Files.readString(file)).isEqualTo("second");
    }

    /** A symlink planted at the temporary path must not turn a state write into a write somewhere else. */
    @Test
    void neverWritesThroughASymlinkPlantedAtThePath() throws IOException {
        Path victim = directory.resolve("victim");
        Files.writeString(victim, "untouched");
        Path file = directory.resolve("state.tmp");
        Files.createSymbolicLink(file, victim);

        write(file, "content");

        assertThat(Files.readString(victim)).isEqualTo("untouched");
        assertThat(Files.isSymbolicLink(file)).isFalse();
        assertThat(Files.readString(file)).isEqualTo("content");
    }

    private static void write(Path file, String content) throws IOException {
        try (OutputStream out = OwnerOnlyFiles.newOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}

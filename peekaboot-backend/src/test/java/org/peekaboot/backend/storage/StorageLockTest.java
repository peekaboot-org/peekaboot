package org.peekaboot.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageLockTest {

    private static final Duration HOLDER_WAIT = Duration.ofSeconds(30);

    @TempDir
    Path directory;

    @Test
    void aFreeDirectoryIsTakenAndStaysTakenForThisJvm() {
        assertThat(StorageLock.acquire(directory)).isTrue();
        assertThat(StorageLock.acquire(directory)).isTrue();
    }

    @Test
    void aDirectoryAnotherProcessHoldsIsRefused() throws Exception {
        Path storage = Files.createDirectory(directory.resolve("storage"));
        Path holderOutput = directory.resolve("holder.out");
        Process holder = holdLock(storage.resolve(StorageLock.FILE_NAME), holderOutput);
        try {
            await().atMost(HOLDER_WAIT).until(() -> hasLocked(holderOutput) || !holder.isAlive());
            assertThat(Files.readAllLines(holderOutput)).contains("locked");

            assertThat(StorageLock.acquire(storage)).isFalse();
        } finally {
            holder.getOutputStream().close();
            if (!holder.waitFor(HOLDER_WAIT.toSeconds(), TimeUnit.SECONDS)) {
                holder.destroyForcibly();
            }
        }
    }

    /** The key is a path, not a file: a symlink to a directory this JVM already holds finds the lock taken. */
    @Test
    void aDirectoryThisJvmHoldsUnderAnotherPathCountsAsHeld() throws IOException {
        Path storage = directory.resolve("storage");
        Path alias = Files.createSymbolicLink(directory.resolve("alias"), storage);

        assertThat(StorageLock.acquire(storage)).isTrue();
        assertThat(StorageLock.acquire(alias)).isTrue();
    }

    /** A second JVM, started from the single source file, so the lock is another process's. */
    private static Process holdLock(Path lockFile, Path output) throws IOException, URISyntaxException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path program = Path.of(
                StorageLockTest.class.getResource("/storage/HoldLock.java").toURI());
        return new ProcessBuilder(java.toString(), program.toString(), lockFile.toString())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
    }

    private static boolean hasLocked(Path holderOutput) throws IOException {
        return Files.readAllLines(holderOutput).contains("locked");
    }
}

package org.peekaboot.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.testsupport.LogCapture;

class StorageDirectoryTest {

    @TempDir
    Path directory;

    private static PeekabootProperties.Storage storage(boolean enabled, String dir) {
        PeekabootProperties.Storage storage = new PeekabootProperties.Storage();
        storage.setEnabled(enabled);
        storage.setDir(dir);
        return storage;
    }

    @Test
    void aDisabledDirectoryHandsOutNoFiles() {
        StorageDirectory directory = StorageDirectory.resolve(storage(false, null), "orders");

        assertThat(directory.isEnabled()).isFalse();
        assertThat(directory.file("insights.snapshot")).isEmpty();
    }

    @Test
    void anExplicitDirectoryIsUsedVerbatim() {
        StorageDirectory explicit = StorageDirectory.resolve(storage(true, directory.toString()), "orders");

        assertThat(explicit.root()).isEqualTo(directory);
        assertThat(explicit.file("insights.snapshot")).contains(directory.resolve("insights.snapshot"));
    }

    @Test
    void theDefaultDirectoryIsNamedAfterTheApplicationId() {
        StorageDirectory directory = StorageDirectory.resolve(storage(true, null), "orders");

        assertThat(directory.root()).isEqualTo(Path.of(System.getProperty("user.home"), ".peekaboot", "orders"));
    }

    @Test
    void anApplicationWithoutAnIdFallsBackToAFixedFolder() {
        StorageDirectory directory = StorageDirectory.resolve(storage(true, null), "  ");

        assertThat(directory.root().getFileName()).isEqualTo(Path.of("application"));
    }

    @Test
    void anIdThatCouldEscapeTheDirectoryIsSanitized() {
        StorageDirectory directory = StorageDirectory.resolve(storage(true, null), "../orders svc");

        assertThat(directory.root().getFileName()).isEqualTo(Path.of("..-orders-svc"));
    }

    @Test
    void anIdThatIsNothingButParentDirectoryFallsBackToTheFixedFolder() {
        StorageDirectory directory = StorageDirectory.resolve(storage(true, null), "..");

        assertThat(directory.root().getFileName()).isEqualTo(Path.of("application"));
    }

    @Test
    void buildCoordinatesSurviveIntact() {
        StorageDirectory directory = StorageDirectory.resolve(storage(true, null), "com.example.orders-service");

        assertThat(directory.root().getFileName()).isEqualTo(Path.of("com.example.orders-service"));
    }

    @Test
    void theFirstInstanceGetsItsFiles() {
        StorageDirectory first = new StorageDirectory(true, directory, path -> true);

        assertThat(first.file("a")).contains(directory.resolve("a"));
    }

    @Test
    void aSecondInstanceGetsNoFilesAndSaysSoOnce() {
        StorageDirectory second = new StorageDirectory(true, directory, path -> false);

        try (LogCapture capture = LogCapture.attach(StorageDirectory.class)) {
            assertThat(second.file("a")).isEmpty();
            assertThat(second.file("b")).isEmpty();

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Peekaboot: another running instance writes to " + directory
                                + "; this instance keeps its history in memory."
                                + " Give each instance its own peekaboot.storage.dir.");
            });
        }
    }

    @Test
    void aSwitchedOffDirectoryNeverAsksForTheLock() {
        StorageDirectory switchedOff = new StorageDirectory(false, directory, path -> {
            throw new AssertionError("asked for the lock of " + path);
        });

        assertThat(switchedOff.file("a")).isEmpty();
    }

    /** Two Spring contexts of one JVM share a directory: a DevTools restart, the test suites. */
    @Test
    void twoDirectoriesOfOneJvmOnTheSameRootBothWrite() {
        StorageDirectory first = new StorageDirectory(true, directory);
        StorageDirectory second = new StorageDirectory(true, directory);

        assertThat(first.file("a")).contains(directory.resolve("a"));
        assertThat(second.file("a")).contains(directory.resolve("a"));
        assertThat(directory.resolve(StorageLock.FILE_NAME)).exists();
    }

    @Test
    void aDirectoryThatCannotBeLockedIsLeftToTheStoresToReport() throws IOException {
        Path blocked = Files.createFile(directory.resolve("blocked"));
        StorageDirectory unlockable = new StorageDirectory(true, blocked.resolve("storage"));

        try (LogCapture capture = LogCapture.attach(StorageDirectory.class)) {
            assertThat(unlockable.file("a")).contains(blocked.resolve("storage").resolve("a"));

            assertThat(capture.appender().list).isEmpty();
        }
    }
}

package org.peekaboot.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootProperties;

class StorageDirectoryTest {

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
        StorageDirectory directory = StorageDirectory.resolve(storage(true, "/var/tmp/pk"), "orders");

        assertThat(directory.root()).isEqualTo(Path.of("/var/tmp/pk"));
        assertThat(directory.file("insights.snapshot")).contains(Path.of("/var/tmp/pk/insights.snapshot"));
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
}

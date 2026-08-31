package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.storage.StorageDirectory;
import org.springframework.core.io.DefaultResourceLoader;

class InsightsServicePersistenceTest {

    @TempDir
    Path directory;

    private static InsightsProperties properties() {
        InsightsProperties properties = new InsightsProperties();
        InsightsProperties.Level level = new InsightsProperties.Level();
        level.setInterval(Duration.ofMillis(100));
        level.setSize(50);
        properties.setLevels(List.of(level));
        return properties;
    }

    private StorageDirectory storage(boolean enabled) {
        PeekabootProperties.Storage storage = new PeekabootProperties.Storage();
        storage.setEnabled(enabled);
        storage.setDir(directory.toString());
        return StorageDirectory.resolve(storage, "orders");
    }

    private InsightsService service(boolean enabled) {
        return new InsightsService(
                new SimpleMeterRegistry(),
                properties(),
                new DefaultResourceLoader(),
                InsightsCollector.Listener.NO_OP,
                storage(enabled));
    }

    @Test
    void historyOutlivesTheRunThatCollectedIt() throws Exception {
        InsightsService first = service(true);
        first.start();
        Thread.sleep(500);
        first.stop();
        int collected = first.data(0).count();

        assertThat(collected).isPositive();
        assertThat(directory.resolve(InsightsSnapshotStore.FILE_NAME)).exists();

        InsightsService second = service(true);
        second.start();
        try {
            Thread.sleep(500);
            assertThat(second.data(0).count()).isGreaterThanOrEqualTo(collected);
        } finally {
            second.stop();
        }
    }

    @Test
    void nothingIsWrittenWhileStorageIsSwitchedOff() throws Exception {
        InsightsService service = service(false);
        service.start();
        Thread.sleep(300);
        service.stop();

        assertThat(Files.list(directory).toList()).isEmpty();
    }
}

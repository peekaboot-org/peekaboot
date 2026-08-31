package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.domain.insights.LevelDataResponse;
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
        return service(enabled, new SimpleMeterRegistry());
    }

    private InsightsService service(boolean enabled, MeterRegistry registry) {
        return new InsightsService(
                registry,
                properties(),
                new DefaultResourceLoader(),
                InsightsCollector.Listener.NO_OP,
                storage(enabled));
    }

    /**
     * {@code heap.used} reads {@code jvm.memory.used} tagged {@code area=heap} - the
     * bundled {@code heap} panel's first series - so a gauge registered under that name
     * and tag is what the collector actually samples.
     */
    private static MeterRegistry registryReading(long heapUsed) {
        MeterRegistry registry = new SimpleMeterRegistry();
        registry.gauge("jvm.memory.used", Tags.of("area", "heap"), new AtomicLong(heapUsed));
        return registry;
    }

    @Test
    void historyOutlivesTheRunThatCollectedIt() throws Exception {
        InsightsService first = service(true, registryReading(7));
        first.start();
        Thread.sleep(500);
        first.stop();
        int collected = first.data(0).count();

        assertThat(collected).isPositive();
        assertThat(directory.resolve(InsightsSnapshotStore.FILE_NAME)).exists();

        InsightsService second = service(true, registryReading(1));
        second.start();
        try {
            Thread.sleep(500);
            LevelDataResponse data = second.data(0);
            assertThat(data.count()).isGreaterThanOrEqualTo(collected);

            List<Double> heapUsed = data.series().get("heap.used").values();
            // 7.0 can only be here if the restored file supplied it - this run's own
            // registry reads 1.
            assertThat(heapUsed).contains(7.0);
            List<Double> sampled = heapUsed.stream().filter(Objects::nonNull).toList();
            assertThat(sampled.get(sampled.size() - 1))
                    .as("live sampling continues on top of the restored history")
                    .isEqualTo(1.0);
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

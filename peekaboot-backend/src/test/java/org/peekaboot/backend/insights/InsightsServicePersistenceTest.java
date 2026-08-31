package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
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
     * and tag is what the collector actually samples. Built from a supplier rather than
     * {@code MeterRegistry.gauge(name, tags, stateObject)}, whose state object is held
     * only weakly: nothing else in this test keeps it alive, so a GC between ticks would
     * silently turn every later sample into NaN.
     */
    private static MeterRegistry registryReading(long heapUsed) {
        MeterRegistry registry = new SimpleMeterRegistry();
        Gauge.builder("jvm.memory.used", () -> heapUsed)
                .tags(Tags.of("area", "heap"))
                .register(registry);
        return registry;
    }

    @Test
    void historyOutlivesTheRunThatCollectedIt() {
        InsightsService first = service(true, registryReading(7));
        first.start();
        // A few real ticks, not a fixed sleep and a hope: this is what the first run
        // actually has to persist. Polling heap.used itself, rather than the level's
        // count, sidesteps snapshot() reporting count from whichever series its own
        // iteration lands on last.
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> heapUsedValues(first).size() >= 3);
        first.stop();
        int collected = heapUsedValues(first).size();

        assertThat(collected).isPositive();
        assertThat(directory.resolve(InsightsSnapshotStore.FILE_NAME)).exists();

        InsightsService second = service(true, registryReading(1));
        second.start();
        try {
            // Restore lands before the second run's first tick; wait for heap.used's own
            // newest sample to become this run's value - proof that live sampling has
            // actually resumed on top of the restored history, not just that a tick
            // landed somewhere.
            await().atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(20))
                    .until(() -> Double.valueOf(1.0).equals(lastNonNull(heapUsedValues(second))));

            List<Double> heapUsed = heapUsedValues(second);
            assertThat(heapUsed.size()).isGreaterThanOrEqualTo(collected);
            // 7.0 can only be here if the restored file supplied it - this run's own
            // registry reads 1.
            assertThat(heapUsed).contains(7.0);
            assertThat(lastNonNull(heapUsed))
                    .as("live sampling continues on top of the restored history")
                    .isEqualTo(1.0);
        } finally {
            second.stop();
        }
    }

    private static List<Double> heapUsedValues(InsightsService service) {
        return service.data(0).series().get("heap.used").values();
    }

    private static Double lastNonNull(List<Double> values) {
        List<Double> nonNull = values.stream().filter(Objects::nonNull).toList();
        return nonNull.isEmpty() ? null : nonNull.get(nonNull.size() - 1);
    }

    @Test
    void nothingIsWrittenWhileStorageIsSwitchedOff() throws Exception {
        InsightsService service = service(false);
        service.start();
        Thread.sleep(300);
        service.stop();

        try (var files = Files.list(directory)) {
            assertThat(files.toList()).isEmpty();
        }
    }
}

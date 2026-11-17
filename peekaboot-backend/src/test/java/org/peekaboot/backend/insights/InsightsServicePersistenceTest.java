package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.storage.StorageDirectory;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.core.io.DefaultResourceLoader;

class InsightsServicePersistenceTest {

    @TempDir
    Path directory;

    @TempDir
    Path images;

    private static InsightsProperties properties() {
        return properties(new InsightsProperties.Persistence().getInterval());
    }

    private static InsightsProperties properties(Duration persistenceInterval) {
        InsightsProperties properties = new InsightsProperties();
        properties.getPersistence().setInterval(persistenceInterval);
        properties.setLevels(List.of(InsightsProperties.Level.of(Duration.ofMillis(100), 6000)));
        // The ring spans 10 minutes, so no stall between two runs evicts restored samples.
        // The default max-age derives from that span and is raised so the file is never
        // stale between the runs; these tests exercise restoration, not the cutoff.
        properties.getPersistence().setMaxAge(Duration.ofHours(1));
        return properties;
    }

    private StorageDirectory storage(boolean enabled) {
        return storage(enabled, directory);
    }

    private static StorageDirectory storage(boolean enabled, Path dir) {
        PeekabootProperties.Storage storage = new PeekabootProperties.Storage();
        storage.setEnabled(enabled);
        storage.setDir(dir.toString());
        return StorageDirectory.resolve(storage, "orders");
    }

    private InsightsService service(boolean enabled) {
        return service(enabled, bareRegistry());
    }

    private static MeterRegistry bareRegistry() {
        return new SimpleMeterRegistry();
    }

    private InsightsService service(boolean enabled, MeterRegistry registry) {
        return service(properties(), storage(enabled), registry);
    }

    private static InsightsService service(
            InsightsProperties properties, StorageDirectory storage, MeterRegistry registry) {
        return new InsightsService(
                registry, properties, new DefaultResourceLoader(), InsightsCollectors.noOpListener(), storage);
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
        MeterRegistry registry = bareRegistry();
        Gauge.builder("jvm.memory.used", () -> heapUsed)
                .tags(Tags.of("area", "heap"))
                .register(registry);
        return registry;
    }

    /** start()'s one INFO summary line is deliberate; pin it here rather than let it reach the console. */
    private static String start(InsightsService service) {
        try (LogCapture capture = LogCapture.attach(InsightsService.class, Level.INFO)) {
            service.start();
            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage()).startsWith("Peekaboot insights:");
            });
            return capture.appender().list.get(0).getFormattedMessage();
        }
    }

    private static List<Double> heapUsedValues(InsightsService service) {
        return new InsightsDataReader(service, properties(), 1000)
                .read(new InsightsDataParameters("0", "full", null, null))
                .series()
                .get("heap.used")
                .values();
    }

    private static Double lastNonNull(List<Double> values) {
        List<Double> nonNull = values.stream().filter(Objects::nonNull).toList();
        return nonNull.isEmpty() ? null : nonNull.get(nonNull.size() - 1);
    }

    @Test
    void historyOutlivesTheRunThatCollectedIt() {
        InsightsService first = service(true, registryReading(7));
        start(first);
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
        start(second);
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

    /**
     * Two contexts in one JVM share the storage lock, so the kill is an image of the files taken
     * at one instant and the first run is stopped afterwards on its own directory.
     */
    @Test
    void historyWrittenSinceTheLastFoldSurvivesAKill() throws Exception {
        // A persistence interval equal to the tick interval lets the journal get ahead of the snapshot.
        InsightsService first = service(properties(Duration.ofMillis(100)), storage(true), registryReading(7));
        start(first);
        Path image;
        long snapshotEnd;
        try {
            Path[] taken = new Path[1];
            await("an image whose journal holds entries its snapshot lacks")
                    .atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(20))
                    .until(() -> {
                        Path attempt = Files.createTempDirectory(images, "image");
                        taken[0] = attempt;
                        return journalOutrunsSnapshot(attempt);
                    });
            image = taken[0];
            snapshotEnd = level0End(image.resolve(InsightsSnapshotStore.FILE_NAME));
        } finally {
            first.stop();
        }

        InsightsService second = service(properties(Duration.ofMillis(100)), storage(true, image), registryReading(1));
        start(second);
        try {
            await("the second run's own sample on top of the restored history")
                    .atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(20))
                    .until(() -> Double.valueOf(1.0).equals(lastNonNull(heapUsedValues(second))));

            TickLevelData level = (TickLevelData) second.collector().levelData(0, null);
            double[] heapUsed = level.values().get("heap.used");
            List<Long> timestamps = level.timestamps();
            assertThat(IntStream.range(0, heapUsed.length)
                            .filter(i -> heapUsed[i] == 7.0 && timestamps.get(i) > snapshotEnd))
                    .as("samples of the first run dated after the snapshot, which only the journal holds")
                    .isNotEmpty();
        } finally {
            second.stop();
        }
    }

    /**
     * Journal first: a fold between the two copies leaves a newer snapshot beside an older
     * journal, which replay skips by date.
     */
    private boolean journalOutrunsSnapshot(Path image) {
        try {
            Files.copy(directory.resolve(InsightsJournal.FILE_NAME), image.resolve(InsightsJournal.FILE_NAME));
            Files.copy(
                    directory.resolve(InsightsSnapshotStore.FILE_NAME), image.resolve(InsightsSnapshotStore.FILE_NAME));
            return newestJournalLevel0End(image.resolve(InsightsJournal.FILE_NAME))
                    > level0End(image.resolve(InsightsSnapshotStore.FILE_NAME));
        } catch (IOException e) {
            return false;
        }
    }

    private static long level0End(Path snapshotFile) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(snapshotFile)))) {
            return InsightsSnapshotCodec.readHeader(in).levels().get(0).endEpochMs();
        }
    }

    /** The copy may end inside a record the first run was still appending; the records before it count. */
    private static long newestJournalLevel0End(Path journalFile) throws IOException {
        long newest = Long.MIN_VALUE;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(journalFile)))) {
            InsightsJournalCodec.Header header = InsightsJournalCodec.readHeader(in);
            for (Optional<InsightsSnapshot> record = InsightsJournalCodec.readRecord(in, header);
                    record.isPresent();
                    record = InsightsJournalCodec.readRecord(in, header)) {
                newest = Math.max(newest, record.get().levels().get(0).endEpochMs());
            }
        } catch (IOException cutByTheCopy) {
            return newest;
        }
        return newest;
    }

    /** The store says only whether it persists; the words in the start-up line are the service's. */
    @Test
    void theStartUpLineNamesPersistenceOnlyWhileStorageIsOn() {
        InsightsService persisting = service(true);
        String withStorage = start(persisting);
        persisting.stop();
        InsightsService inMemory = service(false);
        String withoutStorage = start(inMemory);
        inMemory.stop();

        assertThat(withStorage).endsWith(", persisted across restarts");
        assertThat(withoutStorage).doesNotContain("persisted");
    }

    @Test
    void nothingIsWrittenWhileStorageIsSwitchedOff() throws Exception {
        InsightsService service = service(false);
        start(service);
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(20))
                // a tick has landed, so there was something to write
                .until(() -> InsightsCollectors.ticks(service.collector()).count() >= 1);
        service.stop();

        try (var files = Files.list(directory)) {
            assertThat(files.toList()).isEmpty();
        }
    }
}

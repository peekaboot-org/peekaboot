package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.peekaboot.backend.testsupport.SeriesDefs.value;

import ch.qos.logback.classic.Level;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.insights.config.SeriesDef;
import org.peekaboot.backend.testsupport.InsightsCollectors;
import org.peekaboot.testsupport.LogCapture;

class InsightsCollectorRestoreTest {

    private static InsightsCollector collector(String... seriesIds) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Gauge.builder("g", () -> 7).register(registry);
        List<SeriesDef> series =
                List.of(seriesIds).stream().map(id -> value(id, "g")).toList();
        return InsightsCollectorBuilder.collector(registry)
                .levels(
                        InsightsProperties.Level.of(Duration.ofSeconds(10), 90),
                        InsightsProperties.Level.of(Duration.ofMinutes(1), 60))
                .series(series)
                .build();
    }

    @Test
    void capturedRingsComeBackWithTheirGapsAndTheirSampleCounts() {
        InsightsCollector source = collector("cpu.process");
        source.tick(10_000);
        source.tick(20_000);
        source.rollUp(1, 60_000);

        InsightsSnapshot snapshot = source.capture();
        InsightsCollector restored = collector("cpu.process");
        restored.restore(snapshot);

        LevelData level0 = restored.levelData(0);
        assertThat(level0.count()).isEqualTo(2);
        assertThat(level0.endEpochMs()).isEqualTo(20_000);
        assertThat(level0.tickValues().get("cpu.process")).containsExactly(7.0, 7.0);
        assertThat(restored.levelData(1).endEpochMs()).isEqualTo(60_000);
        assertThat(restored.levelData(1).statValues().get("cpu.process").get("avg"))
                .containsExactly(7.0);
    }

    @Test
    void theSamplesColumnSurvivesSoTheNextRollUpCanWeightItsAverage() {
        InsightsCollector source = collector("cpu.process");
        source.tick(10_000);
        source.tick(20_000);
        source.rollUp(1, 60_000);

        double[][] level1 = source.capture().series().get("cpu.process").get(1);

        assertThat(level1[InsightsSnapshot.STAT_COLUMNS.indexOf("samples")]).containsExactly(2.0);
    }

    @Test
    void aSeriesTheConfigNoLongerKnowsIsDropped() {
        InsightsCollector source = collector("cpu.process", "gone.series");
        source.tick(10_000);

        InsightsCollector restored = collector("cpu.process");
        restored.restore(source.capture());

        assertThat(restored.levelData(0).tickValues()).containsOnlyKeys("cpu.process");
        assertThat(restored.levelData(0).tickValues().get("cpu.process")).hasSize(1);
    }

    @Test
    void aSeriesTheFileNeverCarriedComesBackAsGapsBesideTheOneItDid() {
        InsightsCollector source = collector("cpu.process");
        source.tick(10_000);

        InsightsCollector restored = collector("cpu.process", "brand.new");
        restored.restore(source.capture());

        assertThat(restored.levelData(0).tickValues().get("cpu.process")).containsExactly(7.0);
        assertThat(restored.levelData(0).tickValues().get("brand.new")).containsExactly(Double.NaN);
    }

    /**
     * The file declares one sample count per level for every series it holds, so a series
     * restored shorter than the rest makes the next capture write fewer values than its own
     * header promises - and the run after that reads a double where a series id should be.
     */
    @Test
    void aConfigThatGainedASeriesStillWritesAFileTheNextRunCanRead() throws IOException {
        InsightsCollector source = collector("cpu.process", "heap.used");
        source.tick(10_000);
        source.tick(20_000);
        source.rollUp(1, 60_000);

        InsightsCollector restored = collector("cpu.process", "heap.used", "brand.new");
        restored.restore(source.capture());
        InsightsSnapshot captured = restored.capture();

        InsightsSnapshot decoded = roundTrip(captured);

        assertThat(decoded.levels()).isEqualTo(captured.levels());
        assertThat(decoded.series()).containsOnlyKeys("cpu.process", "heap.used", "brand.new");
        assertThat(decoded.series().get("cpu.process").get(0)[0]).containsExactly(7.0, 7.0);
        assertThat(decoded.series().get("brand.new").get(0)[0]).containsExactly(Double.NaN, Double.NaN);
        assertThat(decoded.series().get("brand.new").get(1)[7]).containsExactly(0.0); // no samples behind the gap
    }

    private static InsightsSnapshot roundTrip(InsightsSnapshot snapshot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            InsightsSnapshotCodec.write(out, snapshot);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return InsightsSnapshotCodec.readBody(in, InsightsSnapshotCodec.readHeader(in));
        }
    }

    @Test
    void theFirstTickAfterARestoreFillsTheGapTheOutageLeft() {
        InsightsCollector source = collector("cpu.process");
        source.tick(10_000);

        InsightsCollector restored = collector("cpu.process");
        restored.restore(source.capture());
        restored.tick(60_000); // four 10s boundaries missed

        assertThat(restored.levelData(0).tickValues().get("cpu.process"))
                .containsExactly(7.0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 7.0);
    }

    /**
     * One level, one series on gauge "g" fixed at {@code gaugeValue} - the collector's live reading.
     * {@code clock} is what the level thread schedules its boundaries from.
     */
    private static InsightsCollector collector(
            long gaugeValue, InsightsCollector.SnapshotSource source, LongSupplier clock) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Gauge.builder("g", () -> gaugeValue).register(registry);
        return InsightsCollectorBuilder.collector(registry)
                .levels(InsightsProperties.Level.of(Duration.ofMillis(100), 20))
                .series(value("cpu.process", "g"))
                .source(source)
                .clock(clock)
                .build();
    }

    @Test
    void theFirstTickAppliesWhateverThePersistedSnapshotHeld() throws Exception {
        InsightsCollector source = collector(7, InsightsCollectors.noSnapshot(), steppingClock(0));
        source.tick(100_000);
        InsightsSnapshot persisted = source.capture();

        // the first live tick lands on the boundary right after the persisted one and every
        // later tick on the next: no gap for the collector to pad with NaN
        InsightsCollector collector = collector(1, timeout -> Optional.of(persisted), steppingClock(99_900));
        collector.start();
        try {
            awaitLevel0Samples(collector, 2); // the restored 7 and at least one live tick
        } finally {
            collector.stop();
        }

        double[] ticks = collector.levelData(0).tickValues().get("cpu.process");
        assertThat(ticks[0]).isEqualTo(7.0);
        assertThat(Arrays.copyOfRange(ticks, 1, ticks.length)).isNotEmpty().containsOnly(1.0);
    }

    @Test
    void aSourceWithNothingToRestoreLeavesLiveSamplesAlone() throws Exception {
        InsightsCollector collector = collector(1, timeout -> Optional.empty(), steppingClock(0));
        collector.start();
        try {
            awaitLevel0Samples(collector, 2);
        } finally {
            collector.stop();
        }

        // the clock leaves no boundary unticked, so a gap or placeholder could only come from the restore
        double[] ticks = collector.levelData(0).tickValues().get("cpu.process");
        assertThat(collector.levelData(0).endEpochMs()).isGreaterThan(0);
        assertThat(ticks).isNotEmpty();
        assertThat(ticks).containsOnly(1.0);
    }

    /**
     * Every level thread passes the barrier before its first write; the two arriving together
     * is {@link SnapshotRestoreBarrierTest}'s subject, this pins that later writes never ask again.
     */
    @Test
    void theSourceIsAskedOnlyOnceHoweverManyTicksFollow() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Gauge.builder("g", () -> 1).register(registry);
        InsightsCollector collector = InsightsCollectorBuilder.collector(registry)
                .levels(
                        InsightsProperties.Level.of(Duration.ofMillis(100), 20),
                        InsightsProperties.Level.of(Duration.ofMillis(500), 20))
                .series(value("cpu.process", "g"))
                .source(timeout -> {
                    asked.incrementAndGet();
                    return Optional.empty();
                })
                .build();
        collector.start();
        try {
            // both level threads have been through the barrier once each has written
            await().atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(20))
                    .until(() -> collector.levelData(0).count() >= 3
                            && collector.levelData(1).count() >= 1);
        } finally {
            collector.stop();
        }

        assertThat(asked).hasValue(1);
    }

    @Test
    void aSourceThatThrowsIsAbandonedForGoodAndAskedOnlyOnce() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        InsightsCollector collector = collector(
                1,
                timeout -> {
                    asked.incrementAndGet();
                    throw new RuntimeException("boom");
                },
                steppingClock(0));
        try (LogCapture logs = LogCapture.attach(InsightsCollector.class)) {
            collector.start();
            try {
                awaitLevel0Samples(collector, 2);
            } finally {
                collector.stop();
            }

            assertThat(asked).hasValue(1);
            // the failed attempt cost one tick, not the collector's ability to keep ticking
            assertThat(collector.levelData(0).tickValues().get("cpu.process")).hasSizeGreaterThan(1);
            assertThat(logs.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo("Insights tick failed for level 0");
            });
        }
    }

    /** Starts at {@code startEpochMs} and advances one 100ms interval per read, so no boundary is ever missed. */
    private static LongSupplier steppingClock(long startEpochMs) {
        AtomicLong clock = new AtomicLong(startEpochMs);
        return () -> clock.addAndGet(100);
    }

    /** Level 0 holds at least {@code count} samples, restored or ticked live. */
    private static void awaitLevel0Samples(InsightsCollector collector, int count) {
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> collector.levelData(0).count() >= count);
    }
}

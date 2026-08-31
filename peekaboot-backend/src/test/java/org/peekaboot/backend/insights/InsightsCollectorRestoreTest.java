package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.insights.config.SeriesDef;

class InsightsCollectorRestoreTest {

    private static InsightsProperties.Level level(Duration interval, int size) {
        InsightsProperties.Level level = new InsightsProperties.Level();
        level.setInterval(interval);
        level.setSize(size);
        return level;
    }

    private static InsightsCollector collector(String... seriesIds) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.gauge("g", new AtomicLong(7));
        List<SeriesDef> series = List.of(seriesIds).stream()
                .map(id -> new SeriesDef(id, id, "g", Map.<String, String>of(), "value", null, null))
                .toList();
        return new InsightsCollector(
                List.of(level(Duration.ofSeconds(10), 90), level(Duration.ofMinutes(1), 60)),
                series,
                List.of(),
                registry,
                InsightsCollector.Listener.NO_OP);
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

        LevelSnapshot level0 = restored.snapshot(0);
        assertThat(level0.count()).isEqualTo(2);
        assertThat(level0.endEpochMs()).isEqualTo(20_000);
        assertThat(level0.tickValues().get("cpu.process")).containsExactly(7.0, 7.0);
        assertThat(restored.snapshot(1).endEpochMs()).isEqualTo(60_000);
        assertThat(restored.snapshot(1).statValues().get("cpu.process").get("avg"))
                .containsExactly(7.0);
    }

    @Test
    void theSamplesColumnSurvivesSoTheNextRollUpCanWeightItsAverage() {
        InsightsCollector source = collector("cpu.process");
        source.tick(10_000);
        source.tick(20_000);
        source.rollUp(1, 60_000);

        double[][] level1 = source.capture().series().get("cpu.process").get(1);

        assertThat(InsightsSnapshot.STAT_COLUMNS.indexOf("samples")).isEqualTo(7);
        assertThat(level1[7]).containsExactly(2.0);
    }

    @Test
    void aSeriesTheConfigNoLongerKnowsIsDropped() {
        InsightsCollector source = collector("cpu.process", "gone.series");
        source.tick(10_000);

        InsightsCollector restored = collector("cpu.process");
        restored.restore(source.capture());

        assertThat(restored.snapshot(0).tickValues()).containsOnlyKeys("cpu.process");
        assertThat(restored.snapshot(0).tickValues().get("cpu.process")).hasSize(1);
    }

    @Test
    void aSeriesTheFileNeverCarriedStartsEmpty() {
        InsightsCollector source = collector("cpu.process");
        source.tick(10_000);

        InsightsCollector restored = collector("cpu.process", "brand.new");
        restored.restore(source.capture());

        assertThat(restored.snapshot(0).tickValues().get("brand.new")).isEmpty();
    }

    @Test
    void theFirstTickAfterARestoreFillsTheGapTheOutageLeft() {
        InsightsCollector source = collector("cpu.process");
        source.tick(10_000);

        InsightsCollector restored = collector("cpu.process");
        restored.restore(source.capture());
        restored.tick(60_000); // four 10s boundaries missed

        assertThat(restored.snapshot(0).tickValues().get("cpu.process"))
                .containsExactly(7.0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 7.0);
    }

    /** One level, one series on gauge "g" fixed at {@code gaugeValue} - the collector's live reading. */
    private static InsightsCollector collector(long gaugeValue, InsightsCollector.SnapshotSource source) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.gauge("g", new AtomicLong(gaugeValue));
        return new InsightsCollector(
                List.of(level(Duration.ofMillis(100), 20)),
                List.of(new SeriesDef("cpu.process", "cpu", "g", Map.of(), "value", null, null)),
                List.of(),
                registry,
                InsightsCollector.Listener.NO_OP,
                source);
    }

    @Test
    void theFirstTickAppliesWhateverThePersistedSnapshotHeld() throws Exception {
        InsightsCollector source = collector(7, InsightsCollector.SnapshotSource.NONE);
        // real wall-clock epoch: restore() also restores endEpochMs, and the live collector's
        // own first tick fills any gap between it and "now" - keep that gap tiny so it stays a
        // no-op rather than flooding the restored ring with NaNs.
        source.tick(System.currentTimeMillis());
        InsightsSnapshot persisted = source.capture();

        InsightsCollector collector = collector(1, timeout -> Optional.of(persisted));
        collector.start();
        try {
            Thread.sleep(500);
        } finally {
            collector.stop();
        }

        // oldest entry is the persisted 7, everything ticked live afterwards is the gauge's 1
        double[] ticks = collector.snapshot(0).tickValues().get("cpu.process");
        assertThat(ticks).hasSizeGreaterThan(1);
        assertThat(ticks[0]).isEqualTo(7.0);
        assertThat(ticks[ticks.length - 1]).isEqualTo(1.0);
    }

    @Test
    void aSnapshotThatMissesTheBarrierIsNeverAppliedOverLiveSamples() throws Exception {
        InsightsCollector collector = collector(1, timeout -> Optional.empty());
        collector.start();
        try {
            Thread.sleep(500);
        } finally {
            collector.stop();
        }

        // only its own ticks; the persisted 7 that a wrongly-applied restore would carry never shows up
        double[] ticks = collector.snapshot(0).tickValues().get("cpu.process");
        assertThat(collector.snapshot(0).endEpochMs()).isGreaterThan(0);
        assertThat(ticks).isNotEmpty();
        assertThat(ticks).containsOnly(1.0);
    }

    @Test
    void theSourceIsAskedOnlyOnceHoweverManyTicksFollow() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.gauge("g", new AtomicLong(1));
        InsightsCollector collector = new InsightsCollector(
                List.of(level(Duration.ofMillis(100), 20), level(Duration.ofMillis(500), 20)),
                List.of(new SeriesDef("cpu.process", "cpu", "g", Map.of(), "value", null, null)),
                List.of(),
                registry,
                InsightsCollector.Listener.NO_OP,
                timeout -> {
                    asked.incrementAndGet();
                    try {
                        Thread.sleep(80); // widens the window the other level thread can race into
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Optional.empty();
                });
        collector.start();
        try {
            Thread.sleep(700); // long enough for both the 100ms and the 500ms level thread to arrive
        } finally {
            collector.stop();
        }

        assertThat(asked).hasValue(1);
    }

    @Test
    void aSourceThatThrowsIsAbandonedForGoodAndAskedOnlyOnce() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        InsightsCollector collector = collector(1, timeout -> {
            asked.incrementAndGet();
            throw new RuntimeException("boom");
        });
        collector.start();
        try {
            Thread.sleep(500);
        } finally {
            collector.stop();
        }

        assertThat(asked).hasValue(1);
        // the failed attempt cost one tick, not the collector's ability to keep ticking
        assertThat(collector.snapshot(0).tickValues().get("cpu.process")).hasSizeGreaterThan(1);
    }
}

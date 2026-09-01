package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.insights.config.SeriesDef;
import org.peekaboot.backend.insights.config.TileDef;

class InsightsCollectorTest {

    private final List<InsightsProperties.Level> levels = List.of(
            InsightsProperties.Level.of(Duration.ofSeconds(10), 6),
            InsightsProperties.Level.of(Duration.ofMinutes(1), 10),
            InsightsProperties.Level.of(Duration.ofMinutes(2), 5));

    private SimpleMeterRegistry registry;
    private AtomicLong gaugeValue;
    private List<String> events;
    private InsightsCollector collector;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        gaugeValue = registry.gauge("test.gauge", new AtomicLong(0));
        SeriesDef series = new SeriesDef("g", "G", "test.gauge", Map.of(), "value", null, null);
        TileDef staticTile = new TileDef("startup", "Startup", "app.start", Map.of(), "duration", false);
        TileDef liveTile = new TileDef("uptime", "Uptime", "app.uptime", Map.of(), "duration", true);
        events = new ArrayList<>();
        InsightsCollector.Listener listener = new InsightsCollector.Listener() {
            @Override
            public void onTick(long epochMs, Map<String, Double> values, Map<String, Double> tiles) {
                events.add("tick:" + values.get("g"));
            }

            @Override
            public void onRollUp(int level, long epochMs, Map<String, AggregateStats> entries) {
                events.add("rollup:" + level + ":" + entries.get("g").avg());
            }
        };
        collector = new InsightsCollector(levels, List.of(series), List.of(staticTile, liveTile), registry, listener);
    }

    @Test
    void tickAppendsToLevelZeroAndNotifies() {
        gaugeValue.set(5);
        collector.tick(10_000);
        gaugeValue.set(7);
        collector.tick(20_000);
        LevelSnapshot snapshot = collector.snapshot(0);
        assertThat(snapshot.count()).isEqualTo(2);
        assertThat(snapshot.endEpochMs()).isEqualTo(20_000);
        assertThat(snapshot.tickValues().get("g")).containsExactly(5.0, 7.0);
        assertThat(events).containsExactly("tick:5.0", "tick:7.0");
    }

    @Test
    void rollUpAggregatesTicksIntoLevelOne() {
        for (int i = 1; i <= 6; i++) {
            gaugeValue.set(i * 10);
            collector.tick(i * 10_000L);
        }
        collector.rollUp(1, 60_000);
        LevelSnapshot snapshot = collector.snapshot(1);
        assertThat(snapshot.count()).isEqualTo(1);
        assertThat(snapshot.statValues().get("g").get("min")).containsExactly(10.0);
        assertThat(snapshot.statValues().get("g").get("max")).containsExactly(60.0);
        assertThat(snapshot.statValues().get("g").get("avg")).containsExactly(35.0);
        assertThat(events).contains("rollup:1:35.0");
    }

    @Test
    void levelTwoAggregatesLevelOneEntries() {
        // two full level-1 windows with different values
        for (int window = 0; window < 2; window++) {
            for (int i = 1; i <= 6; i++) {
                gaugeValue.set((window + 1) * 100);
                collector.tick((window * 6 + i) * 10_000L);
            }
            collector.rollUp(1, (window + 1) * 60_000L);
        }
        collector.rollUp(2, 120_000);
        LevelSnapshot snapshot = collector.snapshot(2);
        assertThat(snapshot.count()).isEqualTo(1);
        assertThat(snapshot.statValues().get("g").get("min")).containsExactly(100.0);
        assertThat(snapshot.statValues().get("g").get("max")).containsExactly(200.0);
        assertThat(snapshot.statValues().get("g").get("avg")).containsExactly(150.0);
    }

    @Test
    void tickAfterMissedBoundariesFillsTheGapWithNaN() {
        gaugeValue.set(5);
        collector.tick(10_000);
        gaugeValue.set(9);
        collector.tick(40_000); // three intervals later: two boundaries were missed

        LevelSnapshot snapshot = collector.snapshot(0);
        assertThat(snapshot.count()).isEqualTo(4);
        assertThat(snapshot.endEpochMs()).isEqualTo(40_000);
        double[] values = snapshot.tickValues().get("g");
        assertThat(values[0]).isEqualTo(5.0);
        assertThat(values[1]).isNaN();
        assertThat(values[2]).isNaN();
        assertThat(values[3]).isEqualTo(9.0);
        // gaps are ring geometry only - no synthetic events reach the listener
        assertThat(events).containsExactly("tick:5.0", "tick:9.0");
    }

    @Test
    void gapLongerThanTheRingFillsAtMostTheRing() {
        collector.tick(10_000);
        collector.tick(10_000 + 100 * 10_000L); // a suspend far longer than the 6-slot ring

        LevelSnapshot snapshot = collector.snapshot(0);
        assertThat(snapshot.count()).isEqualTo(6);
        assertThat(snapshot.tickValues().get("g")[4]).isNaN();
        assertThat(snapshot.tickValues().get("g")[5]).isEqualTo(0.0);
    }

    @Test
    void rateSampledAfterAGapSpansTheRealElapsedTime() {
        SeriesDef rate = new SeriesDef("r", "R", "test.counter", Map.of(), "rate", null, null);
        InsightsCollector rateCollector =
                new InsightsCollector(levels, List.of(rate), List.of(), registry, InsightsCollector.Listener.NO_OP);
        Counter counter = registry.counter("test.counter");

        rateCollector.tick(10_000); // baseline
        counter.increment(30);
        rateCollector.tick(40_000); // 30 counts over the real 30s, not over one nominal interval

        double[] values = rateCollector.snapshot(0).tickValues().get("r");
        assertThat(values[3]).isEqualTo(1.0);
    }

    @Test
    void rollUpAfterMissedBoundariesFillsTheGapWithEmptyStats() {
        for (int i = 1; i <= 6; i++) {
            gaugeValue.set(10);
            collector.tick(i * 10_000L);
        }
        collector.rollUp(1, 60_000);
        collector.rollUp(1, 240_000); // three intervals later: two boundaries were missed

        LevelSnapshot snapshot = collector.snapshot(1);
        assertThat(snapshot.count()).isEqualTo(4);
        assertThat(snapshot.endEpochMs()).isEqualTo(240_000);
        double[] avgs = snapshot.statValues().get("g").get("avg");
        assertThat(avgs[0]).isEqualTo(10.0);
        assertThat(avgs[1]).isNaN();
        assertThat(avgs[2]).isNaN();
    }

    @Test
    void staticTileFreezesAfterFirstResolution() {
        collector.tick(10_000); // app.start not registered yet -> NaN
        assertThat(collector.tileValues().get("startup")).isNaN();
        AtomicLong start = registry.gauge("app.start", new AtomicLong(42));
        collector.tick(20_000);
        assertThat(collector.tileValues().get("startup")).isEqualTo(42.0);
        start.set(99); // static tile must NOT follow further changes
        collector.tick(30_000);
        assertThat(collector.tileValues().get("startup")).isEqualTo(42.0);
    }

    @Test
    void liveTileFollowsChanges() {
        AtomicLong uptime = registry.gauge("app.uptime", new AtomicLong(1));
        collector.tick(10_000);
        uptime.set(11);
        collector.tick(20_000);
        assertThat(collector.tileValues().get("uptime")).isEqualTo(11.0);
    }

    /**
     * The Overview tab fetches tile values off /api/insights/config the moment the
     * dashboard opens - typically well before the first boundary-aligned level-0 tick
     * (up to a full interval after startup). Tiles are plain value samples, so a read
     * must resolve them itself rather than serve NaN until the collector's cadence
     * catches up.
     */
    @Test
    void tileValuesResolveOnReadBeforeTheFirstTick() {
        AtomicLong start = registry.gauge("app.start", new AtomicLong(42));
        registry.gauge("app.uptime", new AtomicLong(7));

        assertThat(collector.tileValues().get("startup")).isEqualTo(42.0);
        assertThat(collector.tileValues().get("uptime")).isEqualTo(7.0);

        // a static tile freezes at its first resolved read
        start.set(99);
        assertThat(collector.tileValues().get("startup")).isEqualTo(42.0);
    }

    @Test
    void memoryEstimateMatchesFormula() {
        // 2 series x (90 + 1440*8 + 720*8) x 8 bytes
        List<InsightsProperties.Level> spec = List.of(
                InsightsProperties.Level.of(Duration.ofSeconds(10), 90),
                InsightsProperties.Level.of(Duration.ofMinutes(1), 1440),
                InsightsProperties.Level.of(Duration.ofHours(1), 720));
        assertThat(InsightsCollector.estimateMemoryBytes(2, spec)).isEqualTo(2L * (90 + 1440 * 8 + 720 * 8) * 8);
    }

    /**
     * The snapshot writer wakes on the persistence-interval boundary, which is a level-0
     * boundary too, so a capture can land while a tick is still appending series one at a
     * time. The codec refuses a column longer than its level's count, so a snapshot taken
     * mid-tick has to trim itself to what every series has rather than fail the write and
     * burn the store's one-shot failure log. Reproduced by parking the tick inside the
     * second series' gauge, once the first series' ring already holds the new sample.
     */
    @Test
    void captureTakenMidTickTrimsEverySeriesToTheSampleCountTheyAllHave() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicLong first = new AtomicLong(1);
        AtomicBoolean parkSecond = new AtomicBoolean();
        CountDownLatch tickParked = new CountDownLatch(1);
        CountDownLatch captureDone = new CountDownLatch(1);
        Gauge.builder("first", first::get).register(registry);
        Gauge.builder("second", () -> {
                    if (parkSecond.get()) {
                        tickParked.countDown();
                        awaitQuietly(captureDone);
                    }
                    return 2;
                })
                .register(registry);
        InsightsCollector midTick = new InsightsCollector(
                levels,
                List.of(seriesOf("first"), seriesOf("second")),
                List.of(),
                registry,
                InsightsCollector.Listener.NO_OP);
        midTick.tick(10_000);
        first.set(5);
        parkSecond.set(true);
        Thread tick = new Thread(() -> midTick.tick(20_000), "tick");
        tick.start();
        tickParked.await();

        InsightsSnapshot snapshot = midTick.capture();
        captureDone.countDown();
        tick.join();

        assertThat(snapshot.levels().get(0).count()).isEqualTo(1);
        // the first series had the newer sample already; it is the one dropped
        assertThat(snapshot.series().get("first").get(0)[0]).containsExactly(1.0);
        assertThat(snapshot.series().get("second").get(0)[0]).containsExactly(2.0);
        InsightsSnapshotCodec.write(OutputStream.nullOutputStream(), snapshot);
    }

    private static SeriesDef seriesOf(String meter) {
        return new SeriesDef(meter, meter, meter, Map.of(), "value", null, null);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

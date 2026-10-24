package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.insights.InsightsCollectorBuilder.collector;
import static org.peekaboot.backend.testsupport.Latches.awaitQuietly;
import static org.peekaboot.backend.testsupport.SeriesDefs.value;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.insights.config.SeriesDef;
import org.peekaboot.backend.insights.config.Stat;
import org.peekaboot.backend.insights.config.TileDef;
import org.peekaboot.backend.insights.config.TileFormat;

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
        gaugeValue = new AtomicLong(0);
        Gauge.builder("test.gauge", gaugeValue::get).register(registry);
        SeriesDef series = value("g", "test.gauge");
        TileDef staticTile = new TileDef("startup", "Startup", "app.start", Map.of(), TileFormat.DURATION, false);
        TileDef liveTile = new TileDef("uptime", "Uptime", "app.uptime", Map.of(), TileFormat.DURATION, true);
        events = new ArrayList<>();
        InsightsCollector.Listener listener = new InsightsCollector.Listener() {
            @Override
            public void onTick(long epochMs, Map<String, Double> values) {
                events.add("tick:" + values.get("g"));
            }

            @Override
            public void onRollUp(int level, long epochMs, Map<String, AggregateStats> entries) {
                events.add("rollup:" + level + ":" + entries.get("g").avg());
            }
        };
        collector = collector(registry)
                .levels(levels)
                .series(series)
                .tiles(staticTile, liveTile)
                .listener(listener)
                .build();
    }

    @Test
    void tickAppendsToLevelZeroAndNotifies() {
        gaugeValue.set(5);
        collector.tick(10_000);
        gaugeValue.set(7);
        collector.tick(20_000);
        LevelData data = collector.levelData(0);
        assertThat(data.count()).isEqualTo(2);
        assertThat(data.endEpochMs()).isEqualTo(20_000);
        assertThat(data.tickValues().get("g")).containsExactly(5.0, 7.0);
        assertThat(events).containsExactly("tick:5.0", "tick:7.0");
    }

    @Test
    void rollUpAggregatesTicksIntoLevelOne() {
        for (int i = 1; i <= 6; i++) {
            gaugeValue.set(i * 10L);
            collector.tick(i * 10_000L);
        }
        collector.rollUp(1, 60_000);
        LevelData data = collector.levelData(1);
        assertThat(data.count()).isEqualTo(1);
        assertThat(data.statValues().get("g").get("min")).containsExactly(10.0);
        assertThat(data.statValues().get("g").get("max")).containsExactly(60.0);
        assertThat(data.statValues().get("g").get("avg")).containsExactly(35.0);
        assertThat(events).contains("rollup:1:35.0");
    }

    @Test
    void levelTwoAggregatesLevelOneEntries() {
        // two full level-1 windows with different values
        for (int window = 0; window < 2; window++) {
            for (int i = 1; i <= 6; i++) {
                gaugeValue.set((window + 1) * 100L);
                collector.tick((window * 6 + i) * 10_000L);
            }
            collector.rollUp(1, (window + 1) * 60_000L);
        }
        collector.rollUp(2, 120_000);
        LevelData data = collector.levelData(2);
        assertThat(data.count()).isEqualTo(1);
        assertThat(data.statValues().get("g").get("min")).containsExactly(100.0);
        assertThat(data.statValues().get("g").get("max")).containsExactly(200.0);
        assertThat(data.statValues().get("g").get("avg")).containsExactly(150.0);
    }

    @Test
    void tickAfterMissedBoundariesFillsTheGapWithNaN() {
        gaugeValue.set(5);
        collector.tick(10_000);
        gaugeValue.set(9);
        collector.tick(40_000); // three intervals later: two boundaries were missed

        LevelData data = collector.levelData(0);
        assertThat(data.count()).isEqualTo(4);
        assertThat(data.endEpochMs()).isEqualTo(40_000);
        double[] values = data.tickValues().get("g");
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

        LevelData data = collector.levelData(0);
        assertThat(data.count()).isEqualTo(6);
        assertThat(data.tickValues().get("g")[4]).isNaN();
        assertThat(data.tickValues().get("g")[5]).isEqualTo(0.0);
    }

    @Test
    void rateSampledAfterAGapSpansTheRealElapsedTime() {
        InsightsCollector rateCollector = rateCollector();
        Counter counter = registry.counter("test.counter");

        rateCollector.tick(10_000); // baseline
        counter.increment(30);
        rateCollector.tick(40_000); // 30 counts over the real 30s, not over one nominal interval

        double[] values = rateCollector.levelData(0).tickValues().get("r");
        assertThat(values[3]).isEqualTo(1.0);
    }

    /**
     * A clock stepped back hands the tick a boundary older than the last stamp. There is no
     * gap to pad, and a rate must divide by the nominal interval rather than by the negative
     * time that "passed".
     */
    @Test
    void aBoundaryOlderThanTheLastStampPadsNoGapAndRatesOverTheNominalInterval() {
        InsightsCollector rateCollector = rateCollector();
        Counter counter = registry.counter("test.counter");
        rateCollector.tick(10_000); // baseline
        rateCollector.tick(20_000);

        counter.increment(10);
        rateCollector.tick(15_000);

        LevelData data = rateCollector.levelData(0);
        assertThat(data.count()).as("no gap for a boundary in the past").isEqualTo(3);
        assertThat(data.tickValues().get("r")[2])
                .as("10 counts over the nominal 10s, not over -5s")
                .isEqualTo(1.0);
    }

    @Test
    void rollUpAfterMissedBoundariesFillsTheGapWithEmptyStats() {
        for (int i = 1; i <= 6; i++) {
            gaugeValue.set(10);
            collector.tick(i * 10_000L);
        }
        collector.rollUp(1, 60_000);
        collector.rollUp(1, 240_000); // three intervals later: two boundaries were missed

        LevelData data = collector.levelData(1);
        assertThat(data.count()).isEqualTo(4);
        assertThat(data.endEpochMs()).isEqualTo(240_000);
        double[] avgs = data.statValues().get("g").get("avg");
        assertThat(avgs[0]).isEqualTo(10.0);
        assertThat(avgs[1]).isNaN();
        assertThat(avgs[2]).isNaN();
    }

    @Test
    void staticTileFreezesAfterFirstResolution() {
        collector.tick(10_000); // app.start not registered yet -> NaN
        assertThat(collector.tileValues().get("startup")).isNaN();
        AtomicLong start = new AtomicLong(42);
        Gauge.builder("app.start", start::get).register(registry);
        collector.tick(20_000);
        assertThat(collector.tileValues().get("startup")).isEqualTo(42.0);
        start.set(99); // static tile must NOT follow further changes
        collector.tick(30_000);
        assertThat(collector.tileValues().get("startup")).isEqualTo(42.0);
    }

    @Test
    void liveTileFollowsChanges() {
        AtomicLong uptime = new AtomicLong(1);
        Gauge.builder("app.uptime", uptime::get).register(registry);
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
        AtomicLong start = new AtomicLong(42);
        Gauge.builder("app.start", start::get).register(registry);
        Gauge.builder("app.uptime", () -> 7).register(registry);

        assertThat(collector.tileValues().get("startup")).isEqualTo(42.0);
        assertThat(collector.tileValues().get("uptime")).isEqualTo(7.0);

        // a static tile freezes at its first resolved read
        start.set(99);
        assertThat(collector.tileValues().get("startup")).isEqualTo(42.0);
    }

    /**
     * A roll-up fires half a tick after its boundary, but a late one meets the next tick. Every
     * series must then aggregate the same ticks, or the level-1 entries offset one series
     * against the others by a sample. Each tick stamps every series with its own number, so a
     * roll-up that read its windows across a tick sees two different newest values. The test
     * races rather than parks because a window read has no seam to park in; 64 series widen
     * the read enough that an unlocked one straddles a tick several times per run. It can
     * only fail on a broken lock, never on a correct one.
     */
    @Test
    void rollUpRacingATickAggregatesTheSameTicksForEverySeries() throws Exception {
        AtomicLong tickNumber = new AtomicLong();
        AtomicLong straddling = new AtomicLong();
        InsightsCollector racing = racingCollector(tickNumber, new InsightsCollector.Listener() {
            @Override
            public void onTick(long epochMs, Map<String, Double> values) {}

            @Override
            public void onRollUp(int level, long epochMs, Map<String, AggregateStats> entries) {
                long newestTicks = entries.values().stream()
                        .map(AggregateStats::max)
                        .distinct()
                        .count();
                if (newestTicks > 1) {
                    straddling.incrementAndGet();
                }
            }
        });
        // a window read is short beside a tick's sampling, so this race needs the longest run
        Thread ticker = startTicker(racing, tickNumber, 2_000);
        int rollUpsWhileTicking = 0;
        long epochMs = 60_000;
        while (ticker.isAlive()) {
            boolean ticking = tickNumber.get() > 0;
            racing.rollUp(1, epochMs);
            epochMs += 60_000;
            if (ticking && ticker.isAlive()) {
                rollUpsWhileTicking++;
            }
        }
        assertThat(ticker.join(Duration.ofSeconds(5))).isTrue();

        assertThat(rollUpsWhileTicking).isPositive();
        assertThat(straddling)
                .as("roll-ups out of %s that read their windows across a tick", rollUpsWhileTicking)
                .hasValue(0);
    }

    /**
     * The API's level data races the level-0 thread the same way, and a read that took its
     * series across a tick would date their samples from an {@code endEpochMs} some of them do
     * not end at. Like {@link #rollUpRacingATickAggregatesTheSameTicksForEverySeries}, this
     * covers the lock {@code levelData} takes, which no parked test can reach.
     */
    @Test
    void levelDataRacingATickSeesWholeTicksOnly() throws Exception {
        AtomicLong tickNumber = new AtomicLong();
        InsightsCollector racing = racingCollector(tickNumber, InsightsCollectors.noOpListener());
        Thread ticker = startTicker(racing, tickNumber, 500);
        int snapshotsWhileTicking = 0;
        int torn = 0;
        while (ticker.isAlive()) {
            boolean ticking = tickNumber.get() > 0;
            LevelData data = racing.levelData(0);
            if (!holdsWholeTicks(
                    data.endEpochMs(), data.count(), data.tickValues().values())) {
                torn++;
            }
            if (ticking && ticker.isAlive()) {
                snapshotsWhileTicking++;
            }
        }
        assertThat(ticker.join(Duration.ofSeconds(5))).isTrue();

        assertThat(snapshotsWhileTicking).isPositive();
        assertThat(torn)
                .as("snapshots out of %s that read their series across a tick", snapshotsWhileTicking)
                .isZero();
    }

    /** The snapshot writer's capture, raced like {@link #levelDataRacingATickSeesWholeTicksOnly}. */
    @Test
    void captureRacingATickSeesWholeTicksOnly() throws Exception {
        AtomicLong tickNumber = new AtomicLong();
        InsightsCollector racing = racingCollector(tickNumber, InsightsCollectors.noOpListener());
        Thread ticker = startTicker(racing, tickNumber, 500);
        int capturesWhileTicking = 0;
        int torn = 0;
        while (ticker.isAlive()) {
            boolean ticking = tickNumber.get() > 0;
            InsightsSnapshot capture = racing.capture();
            InsightsSnapshot.Level level0 = capture.levels().get(0);
            List<double[]> level0Samples = capture.series().values().stream()
                    .map(byLevel -> byLevel.get(0)[0])
                    .toList();
            if (!holdsWholeTicks(level0.endEpochMs(), level0.count(), level0Samples)) {
                torn++;
            }
            if (ticking && ticker.isAlive()) {
                capturesWhileTicking++;
            }
        }
        assertThat(ticker.join(Duration.ofSeconds(5))).isTrue();

        assertThat(capturesWhileTicking).isPositive();
        assertThat(torn)
                .as("captures out of %s that read their series across a tick", capturesWhileTicking)
                .isZero();
    }

    /**
     * The snapshot writer wakes on the persistence-interval boundary, which is a level-0
     * boundary too, so a capture often lands while a tick is running. The codec refuses a
     * column longer than its level's count, so such a capture must carry one count for every
     * series rather than fail the write and burn the store's one-shot failure log. Like
     * {@link #levelDataReadMidTickSeesOnlyWholeTicks}, this pins the append-after-sampling
     * order only.
     */
    @Test
    void captureTakenMidTickCarriesOneSampleCountForEverySeries() throws Exception {
        MidTick midTick = parkTickInsideSampling();
        InsightsSnapshot snapshot;
        try {
            snapshot = midTick.collector().capture();
        } finally {
            midTick.finish();
        }

        assertThat(snapshot.levels().get(0).count()).isEqualTo(1);
        // the parked tick has sampled the first series but written nothing yet
        assertThat(snapshot.series().get("first").get(0)[0]).containsExactly(1.0);
        assertThat(snapshot.series().get("second").get(0)[0]).containsExactly(2.0);
        // the codec refuses a snapshot whose columns contradict its header, so a write that
        // succeeds is the assertion that the capture is self-consistent
        InsightsSnapshotCodec.write(new DataOutputStream(OutputStream.nullOutputStream()), snapshot);
    }

    /** The snapshot store ages a restored file by this stamp, so it reads the clock the boundaries come from. */
    @Test
    void aCaptureIsDatedByTheCollectorsClock() {
        InsightsCollector dated =
                collector(registry).clock(() -> 1_756_000_000_000L).build();

        assertThat(dated.capture().writtenAtEpochMs()).isEqualTo(1_756_000_000_000L);
    }

    /**
     * A tick samples every series before it appends any, so level data read while it is
     * still sampling sees only the previous whole tick. The chart dates sample i from
     * {@code endEpochMs} and {@code count}, so a partial tick would misdate every sample. This
     * pins the append-after-sampling order only: the write has no seam to park in, so the
     * lock {@code levelData} takes is not covered here.
     */
    @Test
    void levelDataReadMidTickSeesOnlyWholeTicks() throws Exception {
        MidTick midTick = parkTickInsideSampling();
        LevelData data;
        try {
            data = midTick.collector().levelData(0);
        } finally {
            midTick.finish();
        }

        assertThat(data.count()).isEqualTo(1);
        assertThat(data.endEpochMs()).isEqualTo(10_000);
        assertThat(data.tickValues())
                .allSatisfy((id, values) -> assertThat(values).as(id).hasSize(data.count()));
    }

    /**
     * A collector over 64 series that all sample {@code tickNumber}, so each series' newest
     * sample names the tick that wrote it. The width is what lets an unlocked read straddle a
     * tick often enough to fail every run.
     */
    private InsightsCollector racingCollector(AtomicLong tickNumber, InsightsCollector.Listener listener) {
        List<SeriesDef> series = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            Gauge.builder("series." + i, tickNumber::get).register(registry);
            series.add(value("s" + i, "series." + i));
        }
        return collector(registry)
                .levels(levels)
                .series(series)
                .listener(listener)
                .build();
    }

    /** Ticks {@code racing} on its own thread, tick {@code n} dated {@code n * 10_000}. */
    private static Thread startTicker(InsightsCollector racing, AtomicLong tickNumber, int ticks) {
        Thread ticker = new Thread(
                () -> {
                    for (int i = 1; i <= ticks; i++) {
                        tickNumber.set(i);
                        racing.tick(i * 10_000L);
                    }
                },
                "ticker");
        ticker.start();
        return ticker;
    }

    /**
     * Whether level-0 samples read under one {@code endEpochMs} all end at the tick it dates:
     * one count for every series, the ring's fill after that many ticks, and every series'
     * newest sample written by that tick.
     */
    private boolean holdsWholeTicks(long endEpochMs, int count, Collection<double[]> samplesBySeries) {
        long tick = endEpochMs / 10_000;
        int expectedCount = (int) Math.min(tick, levels.get(0).getSize());
        return samplesBySeries.stream()
                .allMatch(samples -> samples.length == expectedCount
                        && count == expectedCount
                        && (expectedCount == 0 || samples[samples.length - 1] == tick));
    }

    /**
     * Two value series, ticked once at 10_000, with the 20_000 tick parked inside the second
     * series' gauge after the first series sampled its newer value. The caller reads, then
     * calls {@link MidTick#finish()}.
     */
    private MidTick parkTickInsideSampling() throws InterruptedException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicLong first = new AtomicLong(1);
        AtomicBoolean parkSecond = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Gauge.builder("first", first::get).register(registry);
        Gauge.builder("second", () -> {
                    if (parkSecond.get()) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                    return 2;
                })
                .register(registry);
        InsightsCollector collector = collector(registry)
                .levels(levels)
                .series(value("first", "first"), value("second", "second"))
                .build();
        collector.tick(10_000);
        first.set(5);
        parkSecond.set(true);
        Thread tick = new Thread(() -> collector.tick(20_000), "tick");
        tick.start();
        boolean parked = entered.await(5, TimeUnit.SECONDS);
        if (!parked) {
            release.countDown();
        }
        assertThat(parked).as("tick parked inside the second series' gauge").isTrue();
        return new MidTick(collector, release, tick);
    }

    private record MidTick(InsightsCollector collector, CountDownLatch release, Thread tick) {

        void finish() throws InterruptedException {
            release.countDown();
            assertThat(tick.join(Duration.ofSeconds(5)))
                    .as("parked tick finished")
                    .isTrue();
        }
    }

    /** One rate series over {@code test.counter}, on the same registry and levels as the fixture collector. */
    private InsightsCollector rateCollector() {
        SeriesDef rate = new SeriesDef("r", "R", "test.counter", Map.of(), Stat.RATE, null, null);
        return collector(registry).levels(levels).series(rate).build();
    }
}

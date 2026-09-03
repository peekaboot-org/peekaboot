package org.peekaboot.backend.insights;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.insights.config.SeriesDef;
import org.peekaboot.backend.insights.config.TileDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Samples every configured series into level 0 of its {@link SeriesRings} on each tick and
 * rolls finer levels up into coarser ones on request. Owns one virtual thread per level
 * (see {@link #start()}) that drives {@link #tick(long)}/{@link #rollUp(int, long)}
 * on a boundary-aligned schedule; the sampling methods themselves remain
 * callable directly, which is how tests exercise them without starting threads.
 */
public final class InsightsCollector implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InsightsCollector.class);

    /** Notified after each tick and each roll-up. */
    public interface Listener {
        // UncommentedEmptyMethodBody: the constant's name is the documentation
        @SuppressWarnings("PMD.UncommentedEmptyMethodBody")
        Listener NO_OP = new Listener() {
            @Override
            public void onTick(long epochMs, Map<String, Double> values) {}

            @Override
            public void onRollUp(int level, long epochMs, Map<String, AggregateStats> entries) {}
        };

        void onTick(long epochMs, Map<String, Double> values);

        void onRollUp(int level, long epochMs, Map<String, AggregateStats> entries);
    }

    /**
     * The collector's read side of persistence. Awaited by the first level thread to
     * reach a write - never on the startup path, which is why loading a snapshot cannot
     * slow an application's boot.
     */
    public interface SnapshotSource {
        SnapshotSource NONE = timeout -> Optional.empty();

        /** The persisted rings, or empty if there are none or they did not arrive in time. */
        Optional<InsightsSnapshot> awaitSnapshot(Duration timeout);
    }

    private final long[] intervalMillis;
    private final Map<String, SeriesSampler> samplers = new LinkedHashMap<>();
    private final SeriesRings rings;

    private final TileTracker tiles;
    private final Listener listener;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;
    private final SnapshotRestoreBarrier restoreBarrier;

    public InsightsCollector(
            List<InsightsProperties.Level> levels,
            List<SeriesDef> series,
            List<TileDef> tiles,
            MeterRegistry registry,
            Listener listener) {
        this(levels, series, tiles, registry, listener, SnapshotSource.NONE);
    }

    public InsightsCollector(
            List<InsightsProperties.Level> levels,
            List<SeriesDef> series,
            List<TileDef> tiles,
            MeterRegistry registry,
            Listener listener,
            SnapshotSource snapshotSource) {
        this.listener = listener;
        this.restoreBarrier = new SnapshotRestoreBarrier(snapshotSource);
        this.intervalMillis = new long[levels.size()];
        for (int i = 0; i < levels.size(); i++) {
            intervalMillis[i] = levels.get(i).getInterval().toMillis();
        }

        for (SeriesDef def : series) {
            samplers.put(def.id(), new SeriesSampler(def, registry));
        }
        this.rings = new SeriesRings(levels, samplers.keySet());

        this.tiles = new TileTracker(tiles, registry);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        List<String> names = threadNames();
        for (int level = 0; level < intervalMillis.length; level++) {
            int boundLevel = level;
            Thread thread = Thread.ofVirtual().name(names.get(level)).unstarted(() -> runLevel(boundLevel));
            threads.add(thread);
            thread.start();
        }
    }

    /** Interrupts and joins every level thread (up to 2s each), then clears them. */
    @Override
    public synchronized void stop() {
        running = false;
        for (Thread thread : threads) {
            thread.interrupt();
        }
        for (Thread thread : threads) {
            try {
                thread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Names the level threads would run under, without starting anything. */
    List<String> threadNames() {
        List<String> names = new ArrayList<>(intervalMillis.length);
        names.add("peekaboot-insights-tick");
        for (int level = 1; level < intervalMillis.length; level++) {
            names.add("peekaboot-insights-agg-" + formatInterval(Duration.ofMillis(intervalMillis[level])));
        }
        return names;
    }

    /** Renders a duration compactly: whole hours as "Nh", whole minutes as "Nm", else "Ns"/"Nms". */
    static String formatInterval(Duration duration) {
        long millis = duration.toMillis();
        if (millis % 3_600_000 == 0) {
            return (millis / 3_600_000) + "h";
        }
        if (millis % 60_000 == 0) {
            return (millis / 60_000) + "m";
        }
        if (millis % 1_000 == 0) {
            return (millis / 1_000) + "s";
        }
        return millis + "ms";
    }

    /**
     * Sleeps to the next boundary of {@code level}'s interval (offset by half the finer
     * level's interval for aggregation levels, so its boundary write has landed), then runs
     * {@code tick}/{@code rollUp} and repeats until interrupted.
     */
    private void runLevel(int level) {
        long intervalMs = intervalMillis[level];
        long offsetMs = level == 0 ? 0 : intervalMillis[level - 1] / 2;
        while (!Thread.currentThread().isInterrupted()) {
            long boundary;
            try {
                boundary = IntervalBoundary.sleepUntilNext(intervalMs, offsetMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                restoreBarrier.arriveBefore(this::restore);
                if (level == 0) {
                    tick(boundary);
                } else {
                    rollUp(level, boundary);
                }
            } catch (RuntimeException e) {
                log.warn("Insights {} failed for level {}", level == 0 ? "tick" : "roll-up", level, e);
            }
        }
    }

    /** Samples every series into level 0 and notifies the listener. */
    void tick(long epochMs) {
        long elapsedMs = elapsedSince(0, epochMs);
        rings.fillMissed(0, epochMs);

        Map<String, Double> values = new LinkedHashMap<>();
        for (Map.Entry<String, SeriesSampler> entry : samplers.entrySet()) {
            double value = entry.getValue().sample(elapsedMs);
            rings.addTick(entry.getKey(), value);
            values.put(entry.getKey(), value);
        }
        rings.stamp(0, epochMs);

        listener.onTick(epochMs, values);
    }

    /**
     * Millis of meter activity this sample covers: the time since the level's last
     * stamped boundary, so a rate/delta derived across a gap is divided by the time
     * that really passed. Falls back to the nominal interval for the first sample
     * and for a backwards clock jump.
     */
    private long elapsedSince(int level, long epochMs) {
        long previous = rings.endEpochMs(level);
        return previous > 0 && epochMs > previous ? epochMs - previous : intervalMillis[level];
    }

    /**
     * Aggregates the last {@code levels[level].interval / levels[level-1].interval}
     * entries of the finer level into one {@link AggregateStats} per series, appends
     * it to this level's ring, and notifies the listener.
     */
    void rollUp(int level, long epochMs) {
        if (level < 1 || level >= intervalMillis.length) {
            throw new IllegalArgumentException("level must be in [1, " + (intervalMillis.length - 1) + "]");
        }
        rings.fillMissed(level, epochMs);

        int n = (int) (intervalMillis[level] / intervalMillis[level - 1]);
        Map<String, AggregateStats> entries = new LinkedHashMap<>();
        for (String id : rings.seriesIds()) {
            AggregateStats stats = level == 1
                    ? AggregateStats.of(rings.tickWindow(id, n))
                    : aggregateFromFinerLevel(rings.statsWindow(id, level - 1, n));
            rings.addStats(id, level, stats);
            entries.put(id, stats);
        }
        rings.stamp(level, epochMs);

        listener.onRollUp(level, epochMs, entries);
    }

    private static AggregateStats aggregateFromFinerLevel(List<AggregateStats> window) {
        double[] mins = window.stream().mapToDouble(AggregateStats::min).toArray();
        double[] maxes = window.stream().mapToDouble(AggregateStats::max).toArray();
        double[] avgs = window.stream().mapToDouble(AggregateStats::avg).toArray();
        double[] sampleCounts =
                window.stream().mapToDouble(AggregateStats::samples).toArray();
        return AggregateStats.ofAggregates(mins, maxes, avgs, sampleCounts);
    }

    /** One level's rings as the API reads them; see {@link SeriesRings#snapshot(int)}. */
    LevelSnapshot snapshot(int level) {
        return rings.snapshot(level);
    }

    /** The rings as persistence writes them; see {@link SeriesRings#capture()}. */
    InsightsSnapshot capture() {
        return rings.capture();
    }

    /** Applies persisted history to the rings, once, ahead of a level thread's first write. */
    void restore(InsightsSnapshot snapshot) {
        rings.restore(snapshot);
    }

    /**
     * Whether the persisted history has reached the rings. Until it has, what the rings hold
     * is only what this run sampled itself, which is less than the file it would replace.
     */
    boolean hasRestoredHistory() {
        return restoreBarrier.hasApplied();
    }

    /** Current tile values, sampled by this read (see {@link TileTracker}); NaN when a tile is not resolvable. */
    Map<String, Double> tileValues() {
        return tiles.values();
    }
}

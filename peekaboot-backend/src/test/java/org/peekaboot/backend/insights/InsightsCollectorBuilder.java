package org.peekaboot.backend.insights;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.insights.config.SeriesDef;
import org.peekaboot.backend.insights.config.TileDef;

/**
 * Builds the {@link InsightsCollector} a test needs. Unless the test says otherwise it has
 * one 10s x 90 level, no series and no tiles, a listener that ignores every event, no
 * persisted snapshot, and the wall clock. Lives beside the collector because its constructor
 * is package-private.
 */
final class InsightsCollectorBuilder {

    private final MeterRegistry registry;
    private List<InsightsProperties.Level> levels = List.of(InsightsProperties.Level.of(Duration.ofSeconds(10), 90));
    private List<SeriesDef> series = List.of();
    private List<TileDef> tiles = List.of();
    private InsightsCollector.Listener listener = InsightsCollectors.noOpListener();
    private InsightsCollector.SnapshotSource source = InsightsCollectors.noSnapshot();
    private LongSupplier clock = System::currentTimeMillis;

    private InsightsCollectorBuilder(MeterRegistry registry) {
        this.registry = registry;
    }

    static InsightsCollectorBuilder collector(MeterRegistry registry) {
        return new InsightsCollectorBuilder(registry);
    }

    InsightsCollectorBuilder levels(InsightsProperties.Level... levels) {
        return levels(List.of(levels));
    }

    InsightsCollectorBuilder levels(List<InsightsProperties.Level> levels) {
        this.levels = levels;
        return this;
    }

    InsightsCollectorBuilder series(SeriesDef... series) {
        return series(List.of(series));
    }

    InsightsCollectorBuilder series(List<SeriesDef> series) {
        this.series = series;
        return this;
    }

    InsightsCollectorBuilder tiles(TileDef... tiles) {
        this.tiles = List.of(tiles);
        return this;
    }

    InsightsCollectorBuilder listener(InsightsCollector.Listener listener) {
        this.listener = listener;
        return this;
    }

    InsightsCollectorBuilder source(InsightsCollector.SnapshotSource source) {
        this.source = source;
        return this;
    }

    InsightsCollectorBuilder clock(LongSupplier clock) {
        this.clock = clock;
        return this;
    }

    InsightsCollector build() {
        return new InsightsCollector(levels, series, Set.of(), tiles, registry, listener, source, clock);
    }
}

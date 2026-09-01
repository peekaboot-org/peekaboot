package org.peekaboot.backend.insights;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.insights.config.SeriesDef;
import org.peekaboot.backend.insights.config.TileDef;

/**
 * The single-value tiles the Overview tab renders: sampled alongside every tick but
 * never charted or rolled into a level. A tile freezes at its first non-NaN reading
 * unless declared {@code live}, in which case it keeps sampling forever.
 */
final class TileTracker {

    private final Map<String, TileState> tiles = new LinkedHashMap<>();

    TileTracker(List<TileDef> defs, MeterRegistry registry) {
        for (TileDef def : defs) {
            SeriesDef tileSeries = new SeriesDef(def.id(), def.label(), def.meter(), def.tags(), "value", null, null);
            boolean live = Boolean.TRUE.equals(def.live());
            tiles.put(def.id(), new TileState(new SeriesSampler(tileSeries, registry), live));
        }
    }

    void sample(long elapsedMs) {
        for (TileState tile : tiles.values()) {
            tile.sample(elapsedMs);
        }
    }

    /** Current tile values; NaN when a tile is not yet (or no longer) resolvable. */
    Map<String, Double> values() {
        Map<String, Double> values = new LinkedHashMap<>();
        for (Map.Entry<String, TileState> entry : tiles.entrySet()) {
            values.put(entry.getKey(), entry.getValue().value);
        }
        return values;
    }

    /** A tile's sampler plus its freeze state (static tiles stop sampling once resolved). */
    private static final class TileState {
        private final SeriesSampler sampler;
        private final boolean live;
        private volatile double value = Double.NaN;
        private volatile boolean frozen;

        private TileState(SeriesSampler sampler, boolean live) {
            this.sampler = sampler;
            this.live = live;
        }

        private void sample(long intervalMillis) {
            if (live) {
                value = sampler.sample(intervalMillis);
                return;
            }
            if (frozen) {
                return;
            }
            double sampled = sampler.sample(intervalMillis);
            if (!Double.isNaN(sampled)) {
                value = sampled;
                frozen = true;
            }
        }
    }
}

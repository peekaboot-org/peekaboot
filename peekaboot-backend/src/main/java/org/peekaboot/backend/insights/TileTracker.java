package org.peekaboot.backend.insights;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.insights.config.SeriesDef;
import org.peekaboot.backend.insights.config.TileDef;

/**
 * The single-value tiles the Overview tab renders: never charted or rolled into a
 * level. A tile freezes at its first non-NaN reading unless declared {@code live},
 * in which case it keeps sampling forever.
 *
 * <p>Sampled on read rather than on the collector's tick cadence: the Overview fetches
 * tile values off {@code /api/insights/config} the moment the dashboard opens, which is
 * routinely before the first boundary-aligned level-0 tick - waiting for that tick would
 * serve every tile as unresolved to exactly the first look at the dashboard. Every tile
 * is a plain {@code value}-stat sample, so a read needs no elapsed interval, and
 * concurrent reads race only benignly: sampling is idempotent and both fields below
 * are volatile.
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

    /** Current tile values; NaN when a tile is not yet (or no longer) resolvable. */
    Map<String, Double> values() {
        Map<String, Double> values = new LinkedHashMap<>();
        for (Map.Entry<String, TileState> entry : tiles.entrySet()) {
            values.put(entry.getKey(), entry.getValue().sample());
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

        /** Samples unless frozen and returns the tile's value; the interval is irrelevant to a value stat. */
        private double sample() {
            if (live) {
                value = sampler.sample(0);
                return value;
            }
            if (!frozen) {
                double sampled = sampler.sample(0);
                if (!Double.isNaN(sampled)) {
                    value = sampled;
                    frozen = true;
                }
            }
            return value;
        }
    }
}

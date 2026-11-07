package org.peekaboot.backend.insights;

import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.config.PeekabootJson;

/**
 * One series over a level's window, so a reader needs no samples to judge it. Above level 0,
 * {@code median} and {@code p95} come from the windows' averages, so a short spike is averaged
 * away; {@code min} and {@code max} stay exact. Each is null where the window holds no sample.
 *
 * @param latest the newest sample, or the newest window's average above level 0
 * @param samples the level-0 samples the summary covers; 0 for an empty window
 */
public record SeriesSummary(
        @Nullable Double min,
        @Nullable Double avg,
        @Nullable Double max,
        @Nullable Double median,
        @Nullable Double p95,
        @Nullable Double latest,
        int samples) {

    static SeriesSummary of(AggregateStats stats, double latest) {
        return new SeriesSummary(
                PeekabootJson.nanToNull(stats.min()),
                PeekabootJson.nanToNull(stats.avg()),
                PeekabootJson.nanToNull(stats.max()),
                PeekabootJson.nanToNull(stats.median()),
                PeekabootJson.nanToNull(stats.p95()),
                PeekabootJson.nanToNull(latest),
                stats.samples());
    }
}

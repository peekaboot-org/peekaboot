package org.peekaboot.backend.insights;

import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.config.PeekabootJson;

/**
 * One series over a level's window, so a reader needs no samples to judge it. Above level 0,
 * {@code median} and {@code p95} are taken over the windows' own median and p95, weighted by
 * samples. {@code min}, {@code max} and {@code avg} stay exact. Each is null where the window
 * holds no sample, and where it is no finite number, as a window holding an infinite sample gives.
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
                PeekabootJson.finiteOrNull(stats.min()),
                PeekabootJson.finiteOrNull(stats.avg()),
                PeekabootJson.finiteOrNull(stats.max()),
                PeekabootJson.finiteOrNull(stats.median()),
                PeekabootJson.finiteOrNull(stats.p95()),
                PeekabootJson.finiteOrNull(latest),
                stats.samples());
    }
}

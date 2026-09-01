package org.peekaboot.backend.insights;

import java.util.List;
import java.util.Map;

/**
 * Every ring of every series, as persisted. {@code series} maps a namespaced series id
 * to one entry per level; each entry is that level's columns, oldest sample first -
 * a single {@code values} column at level 0, and the eight {@link #STAT_COLUMNS} at
 * levels &ge; 1. Unlike {@link LevelSnapshot}, which serves the API, this carries the
 * internal {@code samples} column: the next roll-up weights its average by it.
 */
public record InsightsSnapshot(long writtenAtEpochMs, List<Level> levels, Map<String, List<double[][]>> series) {

    /** The aggregated columns, in the order the file stores them. */
    public static final List<String> STAT_COLUMNS =
            List.of("min", "max", "avg", "median", "p90", "p95", "p99", "samples");

    /** One level's geometry and how much of its ring is filled. */
    public record Level(long intervalMs, int size, long endEpochMs, int count) {}
}

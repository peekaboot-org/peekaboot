package org.peekaboot.backend.insights;

import java.util.Map;

/**
 * Point-in-time copy of one level's ring contents. {@code tickValues} carries
 * raw per-tick values for level 0 (seriesId -&gt; values, oldest first);
 * {@code statValues} carries aggregated windows for levels &ge; 1
 * (seriesId -&gt; statName -&gt; values, oldest first).
 */
public record LevelSnapshot(int level, long intervalMs, long endEpochMs, int count,
                             Map<String, double[]> tickValues,
                             Map<String, Map<String, double[]>> statValues) {
}

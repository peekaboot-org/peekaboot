package org.peekaboot.backend.insights;

import org.peekaboot.backend.insights.config.InsightsProperties;

/** Reads one level of the collector's rings the way {@code GET /api/insights/data} serves it. */
public final class InsightsDataReader {

    private final InsightsCollector collector;
    private final int levelCount;
    private final InsightsProblems problems;

    /**
     * {@code slowTraceThresholdMs} is the latency {@link ProblemRule#SLOW_HTTP} shares with the
     * traces' Slow bucket.
     */
    public InsightsDataReader(InsightsService service, InsightsProperties properties, long slowTraceThresholdMs) {
        this.collector = service.collector();
        this.levelCount = properties.getLevels().size();
        this.problems = new InsightsProblems(
                properties.getProblems(),
                slowTraceThresholdMs,
                properties.getLevels().getFirst().intervalMillis());
    }

    /**
     * One level's history: every series the request names, all of them where it names none, over the
     * samples taken at or after {@code since}. The summary view serves each series' statistics; the
     * full view adds the samples and their timestamps. Both list the problems the window shows over
     * every series. Every parameter is read and checked first, so a bad one is refused with a problem
     * naming it. {@code level} is required.
     */
    public InsightsDataResponse read(InsightsDataParameters parameters) {
        InsightsDataQuery query = InsightsDataQuery.of(parameters, levelCount);
        LevelData data = collector.levelData(query.level()).since(query.since());
        return data.toResponse(query.view(), query.seriesAmong(data.seriesIds()), problems.of(data));
    }
}

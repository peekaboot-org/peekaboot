package org.peekaboot.backend.insights;

import java.util.Locale;

/** One statistic of an aggregated window, in the order the API, the SSE events and the snapshot file carry them. */
public enum WindowStatistic {
    MIN,
    MAX,
    AVG,
    MEDIAN,
    P90,
    P95,
    P99;

    /** The name the statistic travels under. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    double of(AggregateStats stats) {
        return switch (this) {
            case MIN -> stats.min();
            case MAX -> stats.max();
            case AVG -> stats.avg();
            case MEDIAN -> stats.median();
            case P90 -> stats.p90();
            case P95 -> stats.p95();
            case P99 -> stats.p99();
        };
    }
}

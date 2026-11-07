package org.peekaboot.backend.insights;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.TraceView;
import org.peekaboot.backend.problem.ApiProblemException;
import org.peekaboot.backend.problem.RequestParameters;

/**
 * An insights data request, every parameter read and checked.
 *
 * @param series the series ids the request names, in its order; empty where it names none
 * @param since the oldest sample time the request wants; null for the whole ring
 */
record InsightsDataQuery(
        int level,
        TraceView view,
        Set<String> series,
        @Nullable Instant since) {

    static InsightsDataQuery of(InsightsDataParameters parameters, int levelCount) {
        String view = RequestParameters.text(parameters.view());
        return new InsightsDataQuery(
                level(parameters.level(), levelCount),
                view == null ? TraceView.SUMMARY : RequestParameters.enumValue("view", TraceView.class, view),
                series(parameters.series()),
                RequestParameters.instant("since", parameters.since()));
    }

    /** A missing level is refused with the accepted range rather than defaulted. */
    private static int level(@Nullable String value, int levelCount) {
        Integer level = RequestParameters.integer("level", value, 0, levelCount - 1);
        if (level == null) {
            throw ApiProblemException.badParameter(
                    "level", "level is required. Accepted: 0 to " + (levelCount - 1) + ".");
        }
        return level;
    }

    /** A comma-separated list of ids; an empty id is refused rather than skipped. */
    private static Set<String> series(@Nullable String value) {
        String present = RequestParameters.text(value);
        if (present == null) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String id : present.split(",", -1)) {
            if (id.isBlank()) {
                throw ApiProblemException.badParameter(
                        "series", "series names an empty id in '" + value + "'. Separate series ids with commas.");
            }
            ids.add(id.strip());
        }
        return ids;
    }

    /**
     * The ids to serve: those the request named, else every one {@code known} holds. An id {@code known} lacks is
     * refused.
     */
    Set<String> seriesAmong(Set<String> known) {
        if (series.isEmpty()) {
            return known;
        }
        List<String> unknown = series.stream().filter(id -> !known.contains(id)).toList();
        if (!unknown.isEmpty()) {
            throw ApiProblemException.badParameter(
                    "series",
                    "Unknown series " + String.join(", ", unknown) + ". Accepted: " + String.join(", ", known) + ".");
        }
        return series;
    }
}

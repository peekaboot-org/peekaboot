package org.peekaboot.backend.insights;

import org.jspecify.annotations.Nullable;

/**
 * The insights data endpoint's query parameters as the request sent them, each null where it is
 * absent. {@link InsightsDataReader} reads them, so a bad value is refused in one place.
 */
public record InsightsDataParameters(
        @Nullable String level,
        @Nullable String view,
        @Nullable String series,
        @Nullable String since) {}

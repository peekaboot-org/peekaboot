package org.peekaboot.backend.domain.trace;

import org.jspecify.annotations.Nullable;

/**
 * The trace listing's query parameters as the request sent them, each null where it is absent.
 * {@code TraceInsightsService} reads them, so a bad value is refused in one place.
 */
public record TraceListParameters(
        @Nullable String limit,
        @Nullable String cursor,
        @Nullable String bucket,
        @Nullable String rootActionType,
        @Nullable String rootOperation,
        @Nullable String route,
        @Nullable String path,
        @Nullable String statusCode,
        @Nullable String outcome,
        @Nullable String minDurationMs,
        @Nullable String since,
        @Nullable String until,
        @Nullable String view) {}

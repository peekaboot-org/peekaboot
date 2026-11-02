package org.peekaboot.backend.domain.log;

import org.jspecify.annotations.Nullable;

/**
 * The log search's query parameters as the request sent them, each null where it is absent.
 * {@code LogSearchService} reads them, so a bad value is refused in one place.
 */
public record LogSearchParameters(
        @Nullable String level,
        @Nullable String q,
        @Nullable String logger,
        @Nullable String traceId,
        @Nullable String since,
        @Nullable String until,
        @Nullable String limit,
        @Nullable String cursor) {}

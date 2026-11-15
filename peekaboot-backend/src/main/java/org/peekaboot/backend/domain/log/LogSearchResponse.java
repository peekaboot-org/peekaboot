package org.peekaboot.backend.domain.log;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.TraceLog;

/**
 * One page of the log search, newest first.
 *
 * @param next the cursor for the page after this one; null once every line was searched. One
 *     search reads a bounded number of lines ({@code LogSearchService.MAX_LINES_READ}), so a short
 *     or empty page can still carry one
 */
public record LogSearchResponse(
        List<TraceLog> logs, @Nullable String next) {}

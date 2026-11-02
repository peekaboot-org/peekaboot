package org.peekaboot.backend.domain.log;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.TraceLog;

/**
 * One page of the log search, newest first.
 *
 * @param next the cursor for the page after this one; null on the last page
 */
public record LogSearchResponse(
        List<TraceLog> logs, @Nullable String next) {}

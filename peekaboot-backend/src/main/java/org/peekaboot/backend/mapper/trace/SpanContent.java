package org.peekaboot.backend.mapper.trace;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.SpanExceptionInfo;
import org.peekaboot.backend.domain.trace.SqlStatement;
import org.peekaboot.backend.tracing.event.SpanData;

/** What a mapped span carries beside its position and timing, which decides what a mapping costs. */
interface SpanContent {

    Map<String, String> tags(SpanData span);

    @Nullable
    SpanExceptionInfo exception(SpanData span);

    /** Null for a span that is no query or recorded no SQL. */
    @Nullable
    SqlStatement statement(SpanData span);

    @Nullable
    Long rowCount(String spanId);
}

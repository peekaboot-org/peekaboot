package org.peekaboot.backend.mapper.trace;

import java.util.List;
import java.util.Map;
import org.peekaboot.backend.domain.trace.TraceTabSummary;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.tags.HttpSpanTags;

/** The tab summary {@link TraceTreeMapper} puts on a tree: over a whole trace, or over one subtree's own spans. */
final class TraceSummaries {

    /** The logs live on the bundle, not the snapshot; {@code TraceLogMapper.withLogsSummary} counts them. */
    private static final TraceTabSummary.LogsSummary LOGS_COUNTED_LATER = new TraceTabSummary.LogsSummary(0, 0, 0);

    private TraceSummaries() {}

    static TraceTabSummary ofTrace(List<SpanData> spans, SpanData rootSpanData) {
        int dbQueryCount = 0;
        long dbTotalDurationMs = 0L;

        for (SpanData span : spans) {
            if (DbSpans.isQuery(span)) {
                dbQueryCount++;
                dbTotalDurationMs += span.durationMs();
            }
        }

        return new TraceTabSummary(
                requestSummary(rootSpanData),
                new TraceTabSummary.SpansSummary(spans.size()),
                new TraceTabSummary.QueriesSummary(dbQueryCount, dbTotalDurationMs),
                LOGS_COUNTED_LATER);
    }

    /**
     * The subtree's own span and query figures, so a row rooted at an async entry reports what
     * that entry point's own work did and leaves the enclosing trace's out. No request part:
     * a subtree is not a request.
     */
    static TraceTabSummary ofSubtree(int spanCount, int queryCount, long queryDurationMs) {
        return new TraceTabSummary(
                null,
                new TraceTabSummary.SpansSummary(spanCount),
                new TraceTabSummary.QueriesSummary(queryCount, queryDurationMs),
                LOGS_COUNTED_LATER);
    }

    private static TraceTabSummary.RequestSummary requestSummary(SpanData rootSpanData) {
        if (rootSpanData == null || rootSpanData.tags() == null) {
            return null;
        }
        Map<String, String> tags = rootSpanData.tags();
        String method = HttpSpanTags.method(tags);
        String path = HttpSpanTags.path(tags);
        Integer statusCode = HttpSpanTags.statusCode(tags);
        if (method == null && path == null && statusCode == null) {
            return null;
        }
        return new TraceTabSummary.RequestSummary(method, path, statusCode);
    }
}

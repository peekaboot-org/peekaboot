package org.peekaboot.backend.mapper.trace;

import java.util.Map;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.tags.HttpSpanTags;

/** A root span's HTTP request as the summary names it, whichever convention tagged it. */
final class TraceRequests {

    private TraceRequests() {}

    /** Null where the root is missing or carries no HTTP tag at all. */
    static TraceSummary.Request of(SpanData root) {
        if (root == null || root.tags() == null) {
            return null;
        }
        Map<String, String> tags = root.tags();
        String method = HttpSpanTags.method(tags);
        String route = HttpSpanTags.route(tags);
        String path = HttpSpanTags.path(tags);
        Integer statusCode = HttpSpanTags.statusCode(tags);
        if (method == null && route == null && path == null && statusCode == null) {
            return null;
        }
        return new TraceSummary.Request(method, route, path, statusCode);
    }
}

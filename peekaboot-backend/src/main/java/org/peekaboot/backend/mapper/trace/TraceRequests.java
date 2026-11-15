package org.peekaboot.backend.mapper.trace;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.tags.HttpSpanTags;

/**
 * A root span's HTTP request as the summary names it, whichever convention tagged it. The path is
 * masked by the value rules its tag gets.
 */
final class TraceRequests {

    private final MaskingEngine maskingEngine;

    TraceRequests(MaskingEngine maskingEngine) {
        this.maskingEngine = maskingEngine;
    }

    /** Null where the root is missing or carries no HTTP tag at all. */
    TraceSummary.@Nullable Request of(@Nullable SpanData root) {
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
        return new TraceSummary.Request(method, route, maskingEngine.maskValue(path), statusCode);
    }
}

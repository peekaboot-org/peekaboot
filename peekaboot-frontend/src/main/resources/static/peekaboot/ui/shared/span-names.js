/**
 * Builds a spanId -> name lookup for a trace's span tree. Shared by the trace-detail
 * overlay's Spans and Logs tabs, both of which need it to label a span id for a human
 * (the span-logs popup's title, the logs tab's span filter chip).
 */
export function buildSpanNames(rootSpan) {
    const names = new Map();
    (function walk(span) {
        if (!span) return;
        names.set(span.spanId, span.name);
        (span.children || []).forEach(walk);
    })(rootSpan);
    return names;
}

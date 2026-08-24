package org.peekaboot.backend.mapper.trace;

import io.micrometer.tracing.Span;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.SpanEvent;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.TraceStatus;
import org.peekaboot.backend.domain.trace.TraceTabSummary;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.masking.TagMasker;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class TraceTreeMapper {

    private final MaskingEngine maskingEngine = new MaskingEngine();
    private final TagMasker tagMasker = new TagMasker(maskingEngine);

    public TraceTree map(TraceData traceData) {
        return map(traceData, false);
    }

    /**
     * Builds the {@link TraceTree} for a captured trace.
     *
     * @param truncated whether the {@code max-spans-per-trace} cap dropped real spans for
     *                  this trace before it reached here - a property of how the trace was
     *                  captured, not of its (already-deduplicated) span list, so it must be
     *                  passed in rather than derived from {@code traceData}.
     */
    public TraceTree map(TraceData traceData, boolean truncated) {
        if (traceData == null || traceData.spans() == null || traceData.spans().isEmpty()) {
            return new TraceTree(
                    traceData != null ? traceData.traceId() : null,
                    0L, 0L,
                    TraceStatus.OK,
                    RootActionType.UNKNOWN,
                    null, null,
                    new TraceTabSummary(null, new TraceTabSummary.SpansSummary(0, 0L, 0),
                            new TraceTabSummary.QueriesSummary(0, 0L),
                            new TraceTabSummary.LogsSummary(0, 0, 0)),
                    Map.of(),
                    null, null, null,
                    truncated
            );
        }

        List<SpanData> spans = traceData.spans();

        // Build lookup maps
        Map<String, SpanData> spanById = spans.stream()
                .collect(Collectors.toMap(SpanData::spanId, s -> s));
        Map<String, List<SpanData>> childrenByParentId = spans.stream()
                .filter(s -> s.parentId() != null)
                .collect(Collectors.groupingBy(SpanData::parentId));

        // Find root span
        SpanData rootSpanData = findRootSpan(spans, spanById);

        // Re-parent orphan subtrees (parent not in this trace, e.g. not yet
        // exported) under the root so they don't silently vanish from the tree
        attachOrphansToRoot(spans, spanById, childrenByParentId, rootSpanData);

        // Calculate summary
        TraceTabSummary summary = calculateSummary(spans, rootSpanData);

        // Determine trace status
        TraceStatus status = determineStatus(spans);

        // Build tree directly (no hoisting - keep all tags per span)
        SpanNode rootSpan = buildSpanTree(rootSpanData, childrenByParentId);

        long startTimeMs = traceData.startTime() != null ? traceData.startTime().toEpochMilli() : 0L;
        long durationMs = traceData.duration() != null ? traceData.duration().toMillis() : 0L;
        String rootOperation = rootSpanData != null ? rootSpanData.name() : null;
        RootActionType rootActionType = detectRootActionType(rootSpanData);

        return new TraceTree(
                traceData.traceId(),
                startTimeMs,
                durationMs,
                status,
                rootActionType,
                rootOperation,
                rootSpan,
                summary,
                Map.of(),  // No inherited attributes - all tags stay on spans
                null, null, null,
                truncated
        );
    }

    private void attachOrphansToRoot(List<SpanData> spans, Map<String, SpanData> spanById,
                                     Map<String, List<SpanData>> childrenByParentId, SpanData rootSpanData) {
        if (rootSpanData == null) {
            return;
        }
        List<SpanData> orphans = new ArrayList<>();
        for (SpanData span : spans) {
            if (span.equals(rootSpanData)) {
                continue;
            }
            if (span.parentId() == null || !spanById.containsKey(span.parentId())) {
                orphans.add(span);
                if (span.parentId() != null) {
                    childrenByParentId.get(span.parentId()).remove(span);
                }
            }
        }
        if (!orphans.isEmpty()) {
            childrenByParentId.computeIfAbsent(rootSpanData.spanId(), k -> new ArrayList<>()).addAll(orphans);
        }
    }

    private SpanData findRootSpan(List<SpanData> spans, Map<String, SpanData> spanById) {
        // Find span with null parentId or parent not in this trace
        for (SpanData span : spans) {
            if (span.parentId() == null || !spanById.containsKey(span.parentId())) {
                return span;
            }
        }
        // Fallback: return first span
        return spans.isEmpty() ? null : spans.get(0);
    }

    private RootActionType detectRootActionType(SpanData rootSpan) {
        if (rootSpan == null) {
            return RootActionType.UNKNOWN;
        }

        Span.Kind kind = rootSpan.kind();
        Map<String, String> tags = rootSpan.tags() != null ? rootSpan.tags() : Map.of();

        if (kind == Span.Kind.CONSUMER || hasTagPrefix(tags, "messaging.")) {
            return RootActionType.MESSAGE_CONSUMER;
        }
        if (kind == Span.Kind.SERVER) {
            return detectServerActionType(tags);
        }
        return detectNonServerActionType(kind, tags);
    }

    private static RootActionType detectNonServerActionType(Span.Kind kind, Map<String, String> tags) {
        // Spring's scheduled-task observation tag pair -> SCHEDULED_JOB. A genuine
        // @Scheduled invocation carries no Span.Kind (Micrometer only assigns one for
        // Sender/Receiver-style contexts), so this can't be pre-empted by the CLIENT-kind
        // branch below it; it must still run before the null-kind catch-all, which is
        // exactly where an unrecognised scheduled span used to fall.
        if (hasScheduledTaskTags(tags)) {
            return RootActionType.SCHEDULED_JOB;
        }
        if (kind == Span.Kind.CLIENT && hasTagPrefix(tags, "db.")) {
            return RootActionType.DATABASE;
        }
        // null kind -> INTERNAL (Micrometer's Span.Kind enum has no INTERNAL value;
        // internal spans are represented by null kind)
        if (kind == null) {
            return RootActionType.INTERNAL;
        }
        return RootActionType.UNKNOWN;
    }

    private static RootActionType detectServerActionType(Map<String, String> tags) {
        if (hasTagPrefix(tags, "http.")) {
            return RootActionType.HTTP_REQUEST;
        }
        if (hasTagPrefix(tags, "rpc.")) {
            return RootActionType.RPC_CALL;
        }
        if (hasScheduledTaskTags(tags)) {
            return RootActionType.SCHEDULED_JOB;
        }
        return RootActionType.HTTP_REQUEST;
    }

    private static boolean hasTagPrefix(Map<String, String> tags, String prefix) {
        return tags.keySet().stream().anyMatch(k -> k.startsWith(prefix));
    }

    /**
     * Spring's DefaultScheduledTaskObservationConvention is the only convention that sets
     * this exact pair of low-cardinality keys (ScheduledTaskObservationDocumentation.
     * LowCardinalityKeyNames), so their presence together identifies a @Scheduled task
     * span reliably, unlike testing the span's name for "schedule"/"cron"/"timer"/"job".
     */
    private static boolean hasScheduledTaskTags(Map<String, String> tags) {
        return tags.containsKey("code.function") && tags.containsKey("code.namespace");
    }

    private SpanNode buildSpanTree(SpanData spanData, Map<String, List<SpanData>> childrenByParentId) {
        if (spanData == null) {
            return null;
        }

        List<SpanData> childSpans = childrenByParentId.getOrDefault(spanData.spanId(), List.of());
        List<SpanNode> children = childSpans.stream()
                .sorted(Comparator.comparing(SpanData::startTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(child -> buildSpanTree(child, childrenByParentId))
                .toList();

        String status = spanData.hasError() ? "ERROR" : "OK";
        String kind = spanData.kind() != null ? spanData.kind().name() : null;
        long startTimeMs = spanData.startTime() != null ? spanData.startTime().toEpochMilli() : 0L;
        long durationMs = spanData.duration() != null ? spanData.duration().toMillis() : 0L;

        // Copy tags directly (no hoisting), masked - db.statement, http.url etc. may
        // carry a credential the key name alone can't catch. errorMessage below is masked
        // the same way - it can carry the same kind of credential, e.g. an exception
        // message that echoes back the failing request's URL.
        Map<String, Object> tags = new HashMap<>();
        if (spanData.tags() != null) {
            tags.putAll(tagMasker.mask(spanData.tags()));
        }

        // Map events
        List<SpanEvent> events = List.of();
        if (spanData.events() != null && !spanData.events().isEmpty()) {
            events = spanData.events().stream()
                    .map(e -> new SpanEvent(e.name(), e.timestamp()))
                    .toList();
        }

        return new SpanNode(
                spanData.spanId(),
                spanData.name(),
                kind,
                startTimeMs,
                durationMs,
                status,
                children,
                Map.copyOf(tags),
                events,
                List.of(),  // issues added by IssueDetector
                spanData.creationOrder(),
                maskingEngine.maskValue(spanData.errorMessage()),
                spanData.errorClass(),
                spanData.remoteServiceName()
        );
    }

    private TraceTabSummary calculateSummary(List<SpanData> spans, SpanData rootSpanData) {
        int dbQueryCount = 0;
        long dbTotalDurationMs = 0L;
        int errorCount = 0;
        long totalDurationMs = 0L;

        for (SpanData span : spans) {
            if (span.hasError()) {
                errorCount++;
            }
            long durationMs = span.duration() != null ? span.duration().toMillis() : 0L;
            totalDurationMs += durationMs;
            if (isDbQuery(span)) {
                dbQueryCount++;
                dbTotalDurationMs += durationMs;
            }
        }

        return new TraceTabSummary(
                extractRequestSummary(rootSpanData),
                new TraceTabSummary.SpansSummary(spans.size(), totalDurationMs, errorCount),
                new TraceTabSummary.QueriesSummary(dbQueryCount, dbTotalDurationMs),
                new TraceTabSummary.LogsSummary(0, 0, 0)  // Logs populated later by TraceInsightsService
        );
    }

    /**
     * An actual DB query is a CLIENT span carrying db.* tags (standard OpenTelemetry) or
     * jdbc.query* tags (datasource-proxy/Micrometer - not just jdbc.* to avoid counting
     * connection/result-set spans).
     */
    private static boolean isDbQuery(SpanData span) {
        if (span.kind() != Span.Kind.CLIENT || span.tags() == null) {
            return false;
        }
        return span.tags().keySet().stream().anyMatch(k ->
                k.startsWith("db.") || k.startsWith("jdbc.query"));
    }

    private static TraceTabSummary.RequestSummary extractRequestSummary(SpanData rootSpanData) {
        if (rootSpanData == null || rootSpanData.tags() == null) {
            return null;
        }
        Map<String, String> tags = rootSpanData.tags();
        String method = firstTag(tags, "http.method", "http.request.method");
        String path = firstTag(tags, "http.target", "url.path");
        Integer statusCode = parseStatusCode(firstTag(tags, "http.status_code", "http.response.status_code"));
        if (method == null && path == null && statusCode == null) {
            return null;
        }
        return new TraceTabSummary.RequestSummary(method, path, statusCode);
    }

    private static String firstTag(Map<String, String> tags, String key, String fallbackKey) {
        String value = tags.get(key);
        return value != null ? value : tags.get(fallbackKey);
    }

    private static Integer parseStatusCode(String statusValue) {
        if (statusValue == null) {
            return null;
        }
        try {
            return Integer.parseInt(statusValue);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private TraceStatus determineStatus(List<SpanData> spans) {
        for (SpanData span : spans) {
            if (span.hasError()) {
                return TraceStatus.HAS_ERRORS;
            }
        }
        return TraceStatus.OK;
    }
}

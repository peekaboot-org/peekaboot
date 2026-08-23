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

    private final TagMasker tagMasker = new TagMasker(new MaskingEngine());

    public TraceTree map(TraceData traceData) {
        return map(traceData, false);
    }

    /**
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
            if (span == rootSpanData) {
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

        boolean hasHttpTags = tags.keySet().stream().anyMatch(k -> k.startsWith("http."));
        boolean hasDbTags = tags.keySet().stream().anyMatch(k -> k.startsWith("db."));
        boolean hasMessagingTags = tags.keySet().stream().anyMatch(k -> k.startsWith("messaging."));
        boolean hasRpcTags = tags.keySet().stream().anyMatch(k -> k.startsWith("rpc."));
        // Spring's DefaultScheduledTaskObservationConvention is the only convention that sets
        // this exact pair of low-cardinality keys (ScheduledTaskObservationDocumentation.
        // LowCardinalityKeyNames), so their presence together identifies a @Scheduled task
        // span reliably, unlike testing the span's name for "schedule"/"cron"/"timer"/"job".
        boolean hasScheduledTaskTags = tags.containsKey("code.function") && tags.containsKey("code.namespace");

        // 1. CONSUMER kind OR messaging.* tags -> MESSAGE_CONSUMER
        if (kind == Span.Kind.CONSUMER || hasMessagingTags) {
            return RootActionType.MESSAGE_CONSUMER;
        }
        // 2. SERVER kind + http.* tags -> HTTP_REQUEST
        if (kind == Span.Kind.SERVER && hasHttpTags) {
            return RootActionType.HTTP_REQUEST;
        }
        // 3. SERVER kind + rpc.* tags -> RPC_CALL
        if (kind == Span.Kind.SERVER && hasRpcTags) {
            return RootActionType.RPC_CALL;
        }
        // 4. Spring's scheduled-task observation tag pair -> SCHEDULED_JOB. A genuine
        //    @Scheduled invocation carries no Span.Kind (Micrometer only assigns one for
        //    Sender/Receiver-style contexts), so this can't be pre-empted by the SERVER/
        //    CLIENT-kind branches above and below it; it must still run before the null-kind
        //    catch-all (7), which is exactly where an unrecognised scheduled span used to fall.
        if (hasScheduledTaskTags) {
            return RootActionType.SCHEDULED_JOB;
        }
        // 5. CLIENT kind + db.* tags as root -> DATABASE
        if (kind == Span.Kind.CLIENT && hasDbTags) {
            return RootActionType.DATABASE;
        }
        // 6. SERVER kind (default) -> HTTP_REQUEST
        if (kind == Span.Kind.SERVER) {
            return RootActionType.HTTP_REQUEST;
        }
        // 7. null kind -> INTERNAL (Micrometer's Span.Kind enum has no INTERNAL value;
        //    internal spans are represented by null kind)
        if (kind == null) {
            return RootActionType.INTERNAL;
        }
        // 8. Fallback
        return RootActionType.UNKNOWN;
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
        // carry a credential the key name alone can't catch.
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
                spanData.errorMessage(),
                spanData.errorClass(),
                spanData.remoteServiceName()
        );
    }

    private TraceTabSummary calculateSummary(List<SpanData> spans, SpanData rootSpanData) {
        int totalSpans = spans.size();
        int dbQueryCount = 0;
        long dbTotalDurationMs = 0L;
        int errorCount = 0;
        long totalDurationMs = 0L;

        for (SpanData span : spans) {
            if (span.hasError()) {
                errorCount++;
            }
            if (span.duration() != null) {
                totalDurationMs += span.duration().toMillis();
            }

            boolean isClient = span.kind() == Span.Kind.CLIENT;
            Map<String, String> tags = span.tags();

            if (tags != null) {
                // Check for actual DB queries:
                // - Standard OpenTelemetry: db.* tags
                // - datasource-proxy/Micrometer: jdbc.query* tags (not just jdbc.* to avoid counting connection/result-set spans)
                boolean hasDbTag = tags.keySet().stream().anyMatch(k ->
                        k.startsWith("db.") || k.startsWith("jdbc.query"));

                if (isClient && hasDbTag) {
                    dbQueryCount++;
                    if (span.duration() != null) {
                        dbTotalDurationMs += span.duration().toMillis();
                    }
                }
            }
        }

        // Extract request summary from root span tags
        TraceTabSummary.RequestSummary requestSummary = null;
        if (rootSpanData != null && rootSpanData.tags() != null) {
            Map<String, String> tags = rootSpanData.tags();
            String method = tags.get("http.method");
            if (method == null) {
                method = tags.get("http.request.method");
            }
            String path = tags.get("http.target");
            if (path == null) {
                path = tags.get("url.path");
            }
            String statusStr = tags.get("http.status_code");
            if (statusStr == null) {
                statusStr = tags.get("http.response.status_code");
            }
            Integer statusCode = null;
            if (statusStr != null) {
                try {
                    statusCode = Integer.parseInt(statusStr);
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
            if (method != null || path != null || statusCode != null) {
                requestSummary = new TraceTabSummary.RequestSummary(method, path, statusCode);
            }
        }

        return new TraceTabSummary(
                requestSummary,
                new TraceTabSummary.SpansSummary(totalSpans, totalDurationMs, errorCount),
                new TraceTabSummary.QueriesSummary(dbQueryCount, dbTotalDurationMs),
                new TraceTabSummary.LogsSummary(0, 0, 0)  // Logs populated later by TraceInsightsService
        );
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

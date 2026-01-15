package net.osslabz.peekaboot.backend.mapper.trace;

import io.micrometer.tracing.Span;
import net.osslabz.peekaboot.backend.domain.trace.RootActionType;
import net.osslabz.peekaboot.backend.domain.trace.SpanEvent;
import net.osslabz.peekaboot.backend.domain.trace.SpanNode;
import net.osslabz.peekaboot.backend.domain.trace.TraceMetrics;
import net.osslabz.peekaboot.backend.domain.trace.TraceStatus;
import net.osslabz.peekaboot.backend.domain.trace.TraceTree;
import net.osslabz.peekaboot.backend.tracing.store.SpanData;
import net.osslabz.peekaboot.backend.tracing.store.TraceData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class TraceTreeMapper {

    public TraceTree map(TraceData traceData) {
        if (traceData == null || traceData.spans() == null || traceData.spans().isEmpty()) {
            return new TraceTree(
                    traceData != null ? traceData.traceId() : null,
                    0L, 0L,
                    TraceStatus.OK,
                    RootActionType.UNKNOWN,
                    null, null,
                    new TraceMetrics(0, 0, 0L, 0, 0L, 0),
                    Map.of()
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

        // Calculate metrics
        TraceMetrics metrics = calculateMetrics(spans);

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
                metrics,
                Map.of()  // No inherited attributes - all tags stay on spans
        );
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
        String name = rootSpan.name() != null ? rootSpan.name().toLowerCase() : "";

        boolean hasHttpTags = tags.keySet().stream().anyMatch(k -> k.startsWith("http."));
        boolean hasDbTags = tags.keySet().stream().anyMatch(k -> k.startsWith("db."));
        boolean hasMessagingTags = tags.keySet().stream().anyMatch(k -> k.startsWith("messaging."));
        boolean hasRpcTags = tags.keySet().stream().anyMatch(k -> k.startsWith("rpc."));

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
        // 4. Name contains "schedule"/"cron"/"timer"/"job" -> SCHEDULED_JOB
        if (name.contains("schedule") || name.contains("cron") ||
                name.contains("timer") || name.contains("job")) {
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

        // Copy tags directly (no hoisting)
        Map<String, Object> tags = new HashMap<>();
        if (spanData.tags() != null) {
            tags.putAll(spanData.tags());
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

    private TraceMetrics calculateMetrics(List<SpanData> spans) {
        int totalSpans = spans.size();
        int dbQueryCount = 0;
        long dbTotalDurationMs = 0L;
        int httpCallCount = 0;
        long httpTotalDurationMs = 0L;
        int errorCount = 0;

        for (SpanData span : spans) {
            if (span.hasError()) {
                errorCount++;
            }

            boolean isClient = span.kind() == Span.Kind.CLIENT;
            Map<String, String> tags = span.tags();

            if (tags != null) {
                boolean hasDbTag = tags.keySet().stream().anyMatch(k -> k.startsWith("db."));
                boolean hasHttpTag = tags.keySet().stream().anyMatch(k -> k.startsWith("http."));

                if (isClient && hasDbTag) {
                    dbQueryCount++;
                    if (span.duration() != null) {
                        dbTotalDurationMs += span.duration().toMillis();
                    }
                }

                if (isClient && hasHttpTag) {
                    httpCallCount++;
                    if (span.duration() != null) {
                        httpTotalDurationMs += span.duration().toMillis();
                    }
                }
            }
        }

        return new TraceMetrics(totalSpans, dbQueryCount, dbTotalDurationMs, httpCallCount, httpTotalDurationMs, errorCount);
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

package org.peekaboot.backend.mapper.trace;

import io.micrometer.tracing.Span;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.peekaboot.backend.domain.trace.AsyncTaskMarker;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.SpanEvent;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.SpanStatus;
import org.peekaboot.backend.domain.trace.SubtreeView;
import org.peekaboot.backend.domain.trace.TraceStatus;
import org.peekaboot.backend.domain.trace.TraceTabSummary;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.masking.TagMasker;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceData;

public class TraceTreeMapper {

    private final MaskingEngine maskingEngine;
    private final TagMasker tagMasker;

    public TraceTreeMapper(MaskingEngine maskingEngine) {
        this.maskingEngine = maskingEngine;
        this.tagMasker = new TagMasker(maskingEngine);
    }

    /** Builds the {@link TraceTree} for a captured trace. */
    public TraceTree map(TraceData traceData) {
        if (traceData.spans().isEmpty()) {
            return new TraceTree(
                    traceData.traceId(),
                    0L,
                    0L,
                    TraceStatus.OK,
                    false,
                    RootActionType.UNKNOWN,
                    null,
                    null,
                    TraceTabSummary.empty(),
                    null,
                    List.of(),
                    List.of(),
                    null,
                    traceData.truncated());
        }

        List<SpanData> spans = traceData.spans();

        Map<String, SpanData> spanById = spans.stream().collect(Collectors.toMap(SpanData::spanId, s -> s));
        Map<String, List<SpanData>> childrenByParentId =
                spans.stream().filter(s -> s.parentId() != null).collect(Collectors.groupingBy(SpanData::parentId));

        SpanData rootSpanData = traceData.rootSpan();

        // Re-parent orphan subtrees (parent not in this trace, e.g. not yet
        // exported) under the root so they don't silently vanish from the tree
        attachOrphansToRoot(spans, spanById, childrenByParentId, rootSpanData);

        TraceTabSummary summary = calculateSummary(spans, rootSpanData, traceData.asyncSpanIds());
        TraceStatus status = summary.spans().errorCount() > 0 ? TraceStatus.HAS_ERRORS : TraceStatus.OK;

        SpanNode rootSpan = buildSpanTree(
                rootSpanData, childrenByParentId, RowCounts.byQuerySpanId(spans), traceData.asyncSpanIds(), false);

        long startTimeMs = traceData.startTime() != null ? traceData.startTime().toEpochMilli() : 0L;
        long durationMs = traceData.duration() != null ? traceData.duration().toMillis() : 0L;
        String rootOperation = rootSpanData != null ? rootSpanData.name() : null;
        RootActionType rootActionType = detectRootActionType(rootSpanData);

        return new TraceTree(
                traceData.traceId(),
                startTimeMs,
                durationMs,
                status,
                false, // decided by IssueDetector once the issues exist
                rootActionType,
                rootOperation,
                rootSpan,
                summary,
                null,
                List.of(),
                List.of(),
                null,
                traceData.truncated());
    }

    /**
     * The same trace seen from one of its spans: the tree rooted there, timed by that
     * subtree's own window rather than the trace's, classified from that span, summarised
     * over that subtree's own spans rather than the whole trace's - see
     * {@link #subtreeSummary} - and ended OK or in error by those same spans. What the
     * listing's async rows and the {@code ?root=} deep link both render.
     */
    public TraceTree mapSubtree(TraceData traceData, String subtreeRootSpanId) {
        TraceTree whole = map(traceData);
        SpanNode subtreeRoot = findNode(whole.rootSpan(), subtreeRootSpanId);
        if (subtreeRoot == null) {
            return whole;
        }
        SpanData subtreeRootData = traceData.spans().stream()
                .filter(span -> subtreeRootSpanId.equals(span.spanId()))
                .findFirst()
                .orElse(null);
        boolean enclosed = subtreeRootData != null
                && subtreeRootData.parentId() != null
                && traceData.spans().stream().anyMatch(span -> span.spanId().equals(subtreeRootData.parentId()));
        TraceTabSummary summary = subtreeSummary(subtreeRoot);
        // the subtree's own errors, not the enclosing trace's: background work that succeeded
        // must not wear the ERROR badge of the request that dispatched it, which would also
        // pre-empt the row's own SLOW verdict
        TraceStatus status = summary.spans().errorCount() > 0 ? TraceStatus.HAS_ERRORS : TraceStatus.OK;
        return new TraceTree(
                traceData.traceId(),
                subtreeRoot.startTimeMs(),
                subtreeWindowMs(subtreeRoot),
                status,
                whole.slow(),
                detectRootActionType(subtreeRootData),
                subtreeRoot.name(),
                subtreeRoot,
                summary,
                whole.httpExchange(),
                whole.logs(),
                whole.queries(),
                new SubtreeView(subtreeRootSpanId, enclosed),
                traceData.truncated());
    }

    /**
     * The subtree's own span, error and query figures - not the enclosing trace's, so a row
     * rooted at an async entry reports what that entry point's own work did. Unlike
     * {@link #calculateSummary}, nothing here is excluded for being async: every span in a
     * subtree rooted at an async entry is async-marked or a descendant of one, so that
     * exclusion would report zero. The one exclusion that still applies is a nested async
     * entry - a node whose {@link SpanNode#asyncEntry()} is set and which isn't the
     * subtree's own root - together with everything under it, since that work is reachable
     * as a subtree of its own and must not be counted twice. No request part: a subtree is
     * not a request.
     */
    private static TraceTabSummary subtreeSummary(SpanNode subtreeRoot) {
        SubtreeTotals totals = new SubtreeTotals();
        accumulateSubtree(subtreeRoot, true, totals);
        return new TraceTabSummary(
                null,
                new TraceTabSummary.SpansSummary(totals.spanCount, totals.totalDurationMs, totals.errorCount),
                new TraceTabSummary.QueriesSummary(totals.queryCount, totals.queryDurationMs),
                new TraceTabSummary.LogsSummary(0, 0, 0));
    }

    private static void accumulateSubtree(SpanNode node, boolean isSubtreeRoot, SubtreeTotals totals) {
        if (!isSubtreeRoot && node.asyncEntry()) {
            return;
        }
        totals.spanCount++;
        if (node.status() == SpanStatus.ERROR) {
            totals.errorCount++;
        }
        totals.totalDurationMs += node.durationMs();
        if (DbSpans.isQuery(node)) {
            totals.queryCount++;
            totals.queryDurationMs += node.durationMs();
        }
        for (SpanNode child : node.children()) {
            accumulateSubtree(child, false, totals);
        }
    }

    /** Mutable running totals for {@link #accumulateSubtree}, folded into a {@link TraceTabSummary} once complete. */
    private static final class SubtreeTotals {
        int spanCount;
        long totalDurationMs;
        int errorCount;
        int queryCount;
        long queryDurationMs;
    }

    private static SpanNode findNode(SpanNode node, String spanId) {
        if (node == null) {
            return null;
        }
        if (spanId.equals(node.spanId())) {
            return node;
        }
        for (SpanNode child : node.children()) {
            SpanNode found = findNode(child, spanId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * The subtree's own wall-clock window. Not the root span's duration: a child can outlive
     * its parent, which is the ordering that makes this whole feature necessary.
     */
    private static long subtreeWindowMs(SpanNode root) {
        long start = root.startTimeMs();
        long end = latestEndMs(root, root.startTimeMs() + root.durationMs());
        return Math.max(end - start, 0);
    }

    private static long latestEndMs(SpanNode node, long latest) {
        long end = Math.max(latest, node.startTimeMs() + node.durationMs());
        for (SpanNode child : node.children()) {
            end = latestEndMs(child, end);
        }
        return end;
    }

    private void attachOrphansToRoot(
            List<SpanData> spans,
            Map<String, SpanData> spanById,
            Map<String, List<SpanData>> childrenByParentId,
            SpanData rootSpanData) {
        if (rootSpanData == null) {
            return;
        }
        // in a cycle the root has a stored parent; cutting that edge is what ends the walk.
        // map(TraceData) is public, so the root need not be one of `spans` and the group can be absent
        List<SpanData> rootSiblings =
                rootSpanData.parentId() == null ? null : childrenByParentId.get(rootSpanData.parentId());
        if (rootSiblings != null) {
            rootSiblings.remove(rootSpanData);
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
            childrenByParentId
                    .computeIfAbsent(rootSpanData.spanId(), k -> new ArrayList<>())
                    .addAll(orphans);
        }
    }

    public RootActionType detectRootActionType(SpanData rootSpan) {
        if (rootSpan == null) {
            return RootActionType.UNKNOWN;
        }

        Span.Kind kind = rootSpan.kind();
        Map<String, String> tags = rootSpan.tags() != null ? rootSpan.tags() : Map.of();

        // messaging.* tags say "a message was involved", not "we received one" - a
        // producer root span carries the same tags as a consumer. Only CONSUMER kind
        // states the direction outright; PRODUCER states the opposite, so it must not
        // fall into the tag arm below.
        if (kind == Span.Kind.CONSUMER || (kind != Span.Kind.PRODUCER && hasTagPrefix(tags, "messaging."))) {
            return RootActionType.MESSAGE_CONSUMER;
        }
        if (kind == Span.Kind.SERVER) {
            return detectServerActionType(tags);
        }
        return detectNonServerActionType(kind, rootSpan.name(), rootSpan.parentId(), tags);
    }

    /**
     * Classifies a root span that isn't inbound. Note what is deliberately absent: no
     * {@code http.}/{@code rpc.} check. Those tags on a non-SERVER root describe an
     * <em>outbound</em> call that became the root only because its caller's span hasn't
     * been exported yet, so they say nothing about what started the trace - UNKNOWN is
     * the honest answer there, not HTTP_REQUEST.
     */
    private static RootActionType detectNonServerActionType(
            Span.Kind kind, String name, String parentId, Map<String, String> tags) {
        // Peekaboot's own marker, so unambiguous - unlike the third-party tag families below,
        // which infer from someone else's convention. First for that reason, and because the
        // entry span carries no Span.Kind and would otherwise fall to the null-kind catch-all.
        if (tags.containsKey(AsyncTaskMarker.TAG_KEY)) {
            return RootActionType.ASYNC_TASK;
        }
        // Spring's scheduled-task observation tag pair -> SCHEDULED_JOB. A genuine
        // @Scheduled invocation carries no Span.Kind (Micrometer only assigns one for
        // Sender/Receiver-style contexts), so this can't be pre-empted by the CLIENT-kind
        // branch below it, and it must run before the null-kind catch-all that would
        // otherwise swallow it.
        if (hasScheduledTaskTags(tags)) {
            return RootActionType.SCHEDULED_JOB;
        }
        // CLIENT-kind, not merely db.* tagged: only the client side of a query is a span
        // Peekaboot ever sees, so any other kind carrying db.* is not a database action.
        if (kind == Span.Kind.CLIENT && hasTagPrefix(tags, "db.")) {
            return RootActionType.DATABASE;
        }
        // datasource-micrometer's connection observation as the root: a pooled connection
        // acquired outside traced work (HikariCP maintenance). Checked after DATABASE because
        // every datasource observation in a HikariCP app carries jdbc.datasource.*; the fixed
        // contextual name "connection" (JdbcObservationDocumentation.CONNECTION) singles out
        // the acquisition.
        //
        // The parent check is what makes "outside traced work" true rather than merely
        // apparent, and it is the same caveat the http./rpc. note above states. A request on
        // an excluded prefix - Peekaboot's own, or the actuator's - has its root span skipped,
        // so a connection acquired while serving it is the only span stored under that trace
        // id and becomes the bundle's apparent root. Only a span that carried no parent at all
        // was really acquired outside a trace.
        if (kind == Span.Kind.CLIENT
                && parentId == null
                && "connection".equals(name)
                && hasTagPrefix(tags, "jdbc.datasource.")) {
            return RootActionType.CONNECTION_POOL;
        }
        // null kind -> INTERNAL (Micrometer's Span.Kind enum has no INTERNAL value;
        // internal spans are represented by null kind)
        if (kind == null) {
            return RootActionType.INTERNAL;
        }
        return RootActionType.UNKNOWN;
    }

    /**
     * Classifies an inbound (SERVER-kind) root span. HTTP_REQUEST appears twice on
     * purpose: once as a positive {@code http.} match, and again as the fallback, because
     * an inbound span the app served with no protocol tags at all is far likelier to be an
     * HTTP request than anything else Peekaboot can name.
     */
    private static RootActionType detectServerActionType(Map<String, String> tags) {
        if (HttpSpanTags.describesHttpRequest(tags)) {
            return RootActionType.HTTP_REQUEST;
        }
        if (hasTagPrefix(tags, "rpc.")) {
            return RootActionType.RPC_CALL;
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

    private SpanNode buildSpanTree(
            SpanData spanData,
            Map<String, List<SpanData>> childrenByParentId,
            Map<String, Long> rowCounts,
            Set<String> asyncSpanIds,
            boolean parentIsAsync) {
        if (spanData == null) {
            return null;
        }

        boolean isAsync = asyncSpanIds.contains(spanData.spanId());
        List<SpanData> childSpans = childrenByParentId.getOrDefault(spanData.spanId(), List.of());
        List<SpanNode> children = childSpans.stream()
                .sorted(Comparator.comparing(SpanData::startTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(child -> buildSpanTree(child, childrenByParentId, rowCounts, asyncSpanIds, isAsync))
                .toList();

        SpanStatus status = spanData.hasError() ? SpanStatus.ERROR : SpanStatus.OK;
        long startTimeMs = spanData.startTime() != null ? spanData.startTime().toEpochMilli() : 0L;

        return new SpanNode(
                spanData.spanId(),
                spanData.name(),
                spanData.kind(),
                startTimeMs,
                spanData.durationMs(),
                status,
                children,
                maskedTags(spanData),
                mapEvents(spanData),
                List.of(), // issues added by IssueDetector
                spanData.creationOrder(),
                maskingEngine.maskValue(spanData.errorMessage()),
                spanData.errorClass(),
                spanData.remoteServiceName(),
                queryText(spanData),
                rowCounts.get(spanData.spanId()),
                null,
                isAsync && !parentIsAsync);
    }

    /**
     * Every tag stays on its own span, masked - http.url etc. may carry a credential the
     * key name alone can't catch. A span's errorMessage and query text are masked the same
     * way: an exception message can echo back the failing request's URL. The statement
     * tags are the exception: the statement is served once, masked, as the span's
     * {@code query}, and shipping the raw tag beside it would say it twice. That holds
     * only on a query span, the one shape {@code query} is populated for.
     */
    private Map<String, String> maskedTags(SpanData spanData) {
        if (spanData.tags() == null) {
            return Map.of();
        }
        boolean servedAsQuery = DbSpans.isQuery(spanData);
        Map<String, String> kept = new LinkedHashMap<>();
        spanData.tags().forEach((key, value) -> {
            if (!servedAsQuery || !DbSpans.isStatementTag(key)) {
                kept.put(key, value);
            }
        });
        return tagMasker.mask(kept);
    }

    private static List<SpanEvent> mapEvents(SpanData spanData) {
        if (spanData.events() == null) {
            return List.of();
        }
        return spanData.events().stream()
                .map(e -> new SpanEvent(e.name(), e.timestamp()))
                .toList();
    }

    private String queryText(SpanData spanData) {
        return DbSpans.isQuery(spanData) ? maskingEngine.maskValue(DbSpans.sql(spanData)) : null;
    }

    private TraceTabSummary calculateSummary(List<SpanData> spans, SpanData rootSpanData, Set<String> asyncSpanIds) {
        int dbQueryCount = 0;
        long dbTotalDurationMs = 0L;
        int errorCount = 0;
        long totalDurationMs = 0L;

        for (SpanData span : spans) {
            if (span.hasError()) {
                errorCount++;
            }
            long durationMs = span.durationMs();
            // Excluded from the sum for the same reason async work is excluded from the trace
            // window: the caller never waited for it. The counts above and below still cover
            // every span - error-bucket admission reads any erroring span, so dropping one here
            // would show a trace as OK while it sat in the Errors bucket.
            if (!asyncSpanIds.contains(span.spanId())) {
                totalDurationMs += durationMs;
            }
            if (DbSpans.isQuery(span)) {
                dbQueryCount++;
                dbTotalDurationMs += durationMs;
            }
        }

        return new TraceTabSummary(
                extractRequestSummary(rootSpanData),
                new TraceTabSummary.SpansSummary(spans.size(), totalDurationMs, errorCount),
                new TraceTabSummary.QueriesSummary(dbQueryCount, dbTotalDurationMs),
                new TraceTabSummary.LogsSummary(0, 0, 0) // Logs populated later by TraceInsightsService
                );
    }

    private static TraceTabSummary.RequestSummary extractRequestSummary(SpanData rootSpanData) {
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

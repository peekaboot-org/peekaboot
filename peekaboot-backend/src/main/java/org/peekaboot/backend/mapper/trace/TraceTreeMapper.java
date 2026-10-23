package org.peekaboot.backend.mapper.trace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
import org.peekaboot.backend.tracing.tags.HttpSpanTags;

public class TraceTreeMapper {

    /** The logs live on the bundle, not the snapshot; {@code TraceLogMapper.withLogsSummary} counts them. */
    private static final TraceTabSummary.LogsSummary LOGS_COUNTED_LATER = new TraceTabSummary.LogsSummary(0, 0, 0);

    private final MaskingEngine maskingEngine;
    private final TagMasker tagMasker;
    private final SqlStatements sqlStatements;

    /** {@code sqlFormatter} is {@link SqlFormatter#NONE} where none is available. */
    public TraceTreeMapper(MaskingEngine maskingEngine, SqlFormatter sqlFormatter) {
        this.maskingEngine = maskingEngine;
        this.tagMasker = new TagMasker(maskingEngine);
        this.sqlStatements = new SqlStatements(maskingEngine, sqlFormatter);
    }

    public TraceTree map(TraceData traceData) {
        return map(traceData, queriesOf(traceData));
    }

    /** The statements and row counts of {@code traceData}'s query spans, to share with {@link QueryExtractor}. */
    public TraceQueries queriesOf(TraceData traceData) {
        return new TraceQueries(traceData, sqlStatements);
    }

    public TraceTree map(TraceData traceData, TraceQueries queries) {
        if (traceData.spans().isEmpty()) {
            return new TraceTree(
                    traceData.traceId(),
                    null,
                    0L,
                    TraceStatus.OK,
                    RootActionType.UNKNOWN,
                    null,
                    null,
                    TraceTabSummary.empty(),
                    null,
                    traceData.truncated());
        }

        List<SpanData> spans = traceData.spans();

        Map<String, SpanData> spanById = spans.stream().collect(Collectors.toMap(SpanData::spanId, s -> s));
        Map<String, List<SpanData>> childrenByParentId = childrenByParentId(spans);

        SpanData rootSpanData = traceData.rootSpan();

        // Re-parent orphan subtrees (parent not in this trace, e.g. not yet
        // exported) under the root so they don't silently vanish from the tree
        attachOrphansToRoot(spans, spanById, childrenByParentId, rootSpanData);

        TraceTabSummary summary = calculateSummary(spans, rootSpanData);

        SpanNode rootSpan = buildSpanTree(rootSpanData, childrenByParentId, queries, traceData.asyncSpanIds(), false);

        long durationMs = traceData.duration() != null ? traceData.duration().toMillis() : 0L;
        String rootOperation = rootSpanData != null ? rootSpanData.name() : null;
        RootActionType rootActionType = RootActionTypes.of(rootSpanData);

        return new TraceTree(
                traceData.traceId(),
                traceData.startTime(),
                durationMs,
                statusOf(spans.stream().anyMatch(SpanData::hasError)),
                rootActionType,
                rootOperation,
                rootSpan,
                summary,
                null,
                traceData.truncated());
    }

    /**
     * The same trace seen from one of its spans: the tree built from that span's own
     * descendants, timed by their window rather than the trace's, classified from that span,
     * summarised over those spans alone - see {@link #subtreeSummary} - and ended OK or in error
     * by them. What the listing's async rows render. A span no longer stored maps as the whole
     * trace: the cap can evict an entry between listing and mapping.
     */
    public TraceTree mapSubtree(TraceData traceData, String subtreeRootSpanId) {
        List<SpanData> spans = traceData.spans();
        SpanData subtreeRootData = spans.stream()
                .filter(span -> subtreeRootSpanId.equals(span.spanId()))
                .findFirst()
                .orElse(null);
        if (subtreeRootData == null) {
            return map(traceData);
        }
        Map<String, List<SpanData>> childrenByParentId = childrenByParentId(spans);
        String parentId = subtreeRootData.parentId();
        boolean enclosed = parentId != null
                && spans.stream().anyMatch(span -> span.spanId().equals(parentId));
        // every cycle reachable from the subtree's root runs through it, and its parent edge is the way in
        if (parentId != null) {
            childrenByParentId.get(parentId).remove(subtreeRootData);
        }
        Set<String> asyncSpanIds = traceData.asyncSpanIds();
        boolean parentIsAsync = parentId != null && asyncSpanIds.contains(parentId);
        SpanNode subtreeRoot =
                buildSpanTree(subtreeRootData, childrenByParentId, queriesOf(traceData), asyncSpanIds, parentIsAsync);
        SubtreeTotals totals = new SubtreeTotals();
        accumulateSubtree(subtreeRoot, totals);
        return new TraceTree(
                traceData.traceId(),
                subtreeRoot.startTime(),
                subtreeWindowMs(subtreeRoot),
                // the subtree's own errors, not the enclosing trace's: background work that succeeded
                // must not wear the ERROR badge of the request that dispatched it, which would also
                // pre-empt the row's own SLOW verdict
                statusOf(totals.anyFailed),
                RootActionTypes.of(subtreeRootData),
                subtreeRoot.name(),
                subtreeRoot,
                subtreeSummary(totals),
                new SubtreeView(subtreeRootSpanId, enclosed),
                traceData.truncated());
    }

    /** Mutable lists: the orphan and cycle handling re-parents spans by editing them. */
    private static Map<String, List<SpanData>> childrenByParentId(List<SpanData> spans) {
        return spans.stream()
                .filter(s -> s.parentId() != null)
                .collect(Collectors.groupingBy(SpanData::parentId, Collectors.toCollection(ArrayList::new)));
    }

    private static TraceStatus statusOf(boolean anySpanFailed) {
        return anySpanFailed ? TraceStatus.HAS_ERRORS : TraceStatus.OK;
    }

    /**
     * The subtree's own span and query figures - not the enclosing trace's, so a row
     * rooted at an async entry reports what that entry point's own work did. No request part:
     * a subtree is not a request.
     */
    private static TraceTabSummary subtreeSummary(SubtreeTotals totals) {
        return new TraceTabSummary(
                null,
                new TraceTabSummary.SpansSummary(totals.spanCount),
                new TraceTabSummary.QueriesSummary(totals.queryCount, totals.queryDurationMs),
                LOGS_COUNTED_LATER);
    }

    private static void accumulateSubtree(SpanNode node, SubtreeTotals totals) {
        totals.spanCount++;
        if (node.status() == SpanStatus.ERROR) {
            totals.anyFailed = true;
        }
        if (DbSpans.isQuery(node)) {
            totals.queryCount++;
            totals.queryDurationMs += node.durationMs();
        }
        for (SpanNode child : node.children()) {
            accumulateSubtree(child, totals);
        }
    }

    /** Mutable running totals for {@link #accumulateSubtree}, folded into a {@link TraceTabSummary} once complete. */
    private static final class SubtreeTotals {
        int spanCount;
        boolean anyFailed;
        int queryCount;
        long queryDurationMs;
    }

    /**
     * The subtree's own wall-clock window. Not the root span's duration: a child can outlive
     * its parent, which is the ordering that makes this whole feature necessary.
     */
    private static long subtreeWindowMs(SpanNode root) {
        long start = startMs(root);
        long end = latestEndMs(root, start + root.durationMs());
        return Math.max(end - start, 0);
    }

    private static long latestEndMs(SpanNode node, long latest) {
        long end = Math.max(latest, startMs(node) + node.durationMs());
        for (SpanNode child : node.children()) {
            end = latestEndMs(child, end);
        }
        return end;
    }

    /** Epoch millis, zero for a span whose start was never recorded. */
    private static long startMs(SpanNode node) {
        return node.startTime() != null ? node.startTime().toEpochMilli() : 0L;
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

    private SpanNode buildSpanTree(
            SpanData spanData,
            Map<String, List<SpanData>> childrenByParentId,
            TraceQueries queries,
            Set<String> asyncSpanIds,
            boolean parentIsAsync) {
        if (spanData == null) {
            return null;
        }

        boolean isAsync = asyncSpanIds.contains(spanData.spanId());
        List<SpanData> childSpans = childrenByParentId.getOrDefault(spanData.spanId(), List.of());
        List<SpanNode> children = childSpans.stream()
                .sorted(Comparator.comparing(SpanData::startTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(child -> buildSpanTree(child, childrenByParentId, queries, asyncSpanIds, isAsync))
                .toList();

        SpanStatus status = spanData.hasError() ? SpanStatus.ERROR : SpanStatus.OK;

        return new SpanNode(
                spanData.spanId(),
                spanData.name(),
                spanData.kind(),
                spanData.startTime(),
                spanData.durationMs(),
                status,
                children,
                maskedTags(spanData),
                mapEvents(spanData),
                List.of(), // issues added by IssueDetector
                maskingEngine.maskValue(spanData.errorMessage()),
                spanData.errorClass(),
                queries.statement(spanData),
                queries.rowCount(spanData.spanId()),
                isAsync && !parentIsAsync);
    }

    /**
     * Every tag stays on its own span, masked - http.url etc. may carry a credential the
     * key name alone can't catch. A span's errorMessage and query text are masked the same
     * way: an exception message can echo back the failing request's URL. The statement
     * tags and the parameter tags are the exception: they are served once, masked, as the
     * span's {@code statement}, and shipping the raw tag beside it would say it twice. That
     * holds only on a query span, the one shape {@code statement} is populated for.
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

    private TraceTabSummary calculateSummary(List<SpanData> spans, SpanData rootSpanData) {
        int dbQueryCount = 0;
        long dbTotalDurationMs = 0L;

        for (SpanData span : spans) {
            if (DbSpans.isQuery(span)) {
                dbQueryCount++;
                dbTotalDurationMs += span.durationMs();
            }
        }

        return new TraceTabSummary(
                extractRequestSummary(rootSpanData),
                new TraceTabSummary.SpansSummary(spans.size()),
                new TraceTabSummary.QueriesSummary(dbQueryCount, dbTotalDurationMs),
                LOGS_COUNTED_LATER);
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

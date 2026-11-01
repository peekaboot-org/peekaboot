package org.peekaboot.backend.mapper.trace;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.peekaboot.backend.domain.trace.HttpExchange;
import org.peekaboot.backend.domain.trace.QueryInfo;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.TraceDetail;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.store.TraceData;
import org.peekaboot.backend.tracing.store.TraceDataBundle;

/** A trace's data as the overlay renders it. */
final class TraceDetails {

    private final QueryExtractor queryExtractor = new QueryExtractor();

    /**
     * {@code logs} are the row's logs already mapped. A subtree row's data is its own work, so it
     * lists only the queries inside it and carries no request.
     */
    TraceDetail of(
            TraceDataBundle bundle, TraceData traceData, TraceQueries queries, TraceTree tree, List<TraceLog> logs) {
        List<QueryInfo> ownQueries;
        HttpExchange httpExchange;
        if (tree.subtree() == null) {
            ownQueries = queryExtractor.extract(traceData, queries);
            RequestCompletedEvent request = bundle.request();
            httpExchange = request != null ? HttpExchangeMapper.map(request) : null;
        } else {
            Set<String> inSubtree =
                    SpanWalk.subtree(tree.rootSpan()).map(SpanNode::spanId).collect(Collectors.toSet());
            // filtered before extracting, so the statements outside the subtree are never formatted
            List<SpanData> ownSpans = traceData.spans().stream()
                    .filter(span -> inSubtree.contains(span.spanId()))
                    .toList();
            ownQueries = queryExtractor.extract(ownSpans, queries);
            httpExchange = null;
        }
        return new TraceDetail(tree.rootSpan(), tree.orphans(), logs, ownQueries, httpExchange);
    }
}

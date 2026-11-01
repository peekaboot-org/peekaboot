package org.peekaboot.backend.mapper.trace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.peekaboot.backend.domain.trace.IssueSeverity;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.IssueUnit;
import org.peekaboot.backend.domain.trace.RepeatedQueryIssue;
import org.peekaboot.backend.domain.trace.SpanNode;

/** Finds the N+1 shape, one span running the same statement again and again where one query would do. */
final class RepeatedQueries {

    private RepeatedQueries() {}

    /**
     * Only the direct children of {@code parent} count. datasource-micrometer records a JDBC connection
     * span beside the query it served, so a loop outside a transaction still leaves its queries under
     * the loop's span.
     */
    static List<RepeatedQueryIssue> under(SpanNode parent, int threshold) {
        Map<String, List<SpanNode>> byStatement = parent.children().stream()
                .filter(child -> child.statement() != null)
                .collect(Collectors.groupingBy(
                        child -> SqlNormalizer.normalize(child.statement().text()),
                        LinkedHashMap::new,
                        Collectors.toList()));
        return byStatement.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= threshold)
                .map(entry -> issue(parent, entry.getKey(), entry.getValue(), threshold))
                .toList();
    }

    private static RepeatedQueryIssue issue(SpanNode parent, String statement, List<SpanNode> queries, int threshold) {
        int count = queries.size();
        long totalMs = queries.stream().mapToLong(SpanNode::durationMs).sum();
        return new RepeatedQueryIssue(
                IssueType.REPEATED_QUERY,
                IssueSeverity.WARNING,
                parent.spanId(),
                count,
                threshold,
                IssueUnit.QUERIES,
                "This span ran the same statement " + count + " times, " + totalMs + " ms in total.",
                statement,
                count,
                totalMs,
                queries.stream().map(SpanNode::spanId).toList());
    }
}

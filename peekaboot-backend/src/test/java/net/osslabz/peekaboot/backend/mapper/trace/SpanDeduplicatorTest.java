package net.osslabz.peekaboot.backend.mapper.trace;

import io.micrometer.tracing.Span;
import net.osslabz.peekaboot.backend.tracing.store.SpanData;
import net.osslabz.peekaboot.backend.tracing.store.TraceData;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpanDeduplicatorTest {

    private final SpanDeduplicator deduplicator = new SpanDeduplicator();

    @Test
    void deduplicate_shouldRemoveChildDuplicateWithSameNameAndMatchingTags() {
        // Parent span with real datasource name
        var parent = createSpan("parent1", null, "query", Map.of(
                "jdbc.query[0]", "SELECT * FROM person",
                "peer.service", "sample_app_db"
        ), 10);

        // Child span duplicating the parent (different peer.service but otherwise same tags)
        var child = createSpan("child1", "parent1", "query", Map.of(
                "jdbc.query[0]", "SELECT * FROM person",
                "peer.service", "dataSource"
        ), 11);

        var traceData = TraceData.fromSpans("trace1", List.of(parent, child));

        TraceData result = deduplicator.deduplicate(traceData);

        assertThat(result.spans()).hasSize(1);
        assertThat(result.spans().get(0).spanId()).isEqualTo("parent1");
    }

    @Test
    void deduplicate_shouldRemoveChildDuplicateForConnectionSpan() {
        var parent = createSpan("parent1", null, "connection", Map.of(
                "jdbc.datasource.name", "sample_app_db"
        ), 10);

        var child = createSpan("child1", "parent1", "connection", Map.of(
                "jdbc.datasource.name", "dataSource"
        ), 11);

        var traceData = TraceData.fromSpans("trace1", List.of(parent, child));

        TraceData result = deduplicator.deduplicate(traceData);

        assertThat(result.spans()).hasSize(1);
        assertThat(result.spans().get(0).spanId()).isEqualTo("parent1");
    }

    @Test
    void deduplicate_shouldKeepSpansWithDifferentNames() {
        var querySpan = createSpan("span1", null, "query", Map.of(
                "jdbc.query[0]", "SELECT * FROM person",
                "peer.service", "sample_app_db"
        ), 10);

        var resultSetSpan = createSpan("span2", "span1", "result-set", Map.of(
                "jdbc.row-count", "5",
                "peer.service", "sample_app_db"
        ), 11);

        var traceData = TraceData.fromSpans("trace1", List.of(querySpan, resultSetSpan));

        TraceData result = deduplicator.deduplicate(traceData);

        assertThat(result.spans()).hasSize(2);
    }

    @Test
    void deduplicate_shouldKeepSpansWithDifferentNonServiceTags() {
        var parent = createSpan("parent1", null, "query", Map.of(
                "jdbc.query[0]", "SELECT * FROM person",
                "peer.service", "sample_app_db"
        ), 10);

        var child = createSpan("child1", "parent1", "query", Map.of(
                "jdbc.query[0]", "SELECT * FROM orders",
                "peer.service", "dataSource"
        ), 11);

        var traceData = TraceData.fromSpans("trace1", List.of(parent, child));

        TraceData result = deduplicator.deduplicate(traceData);

        assertThat(result.spans()).hasSize(2);
    }

    @Test
    void deduplicate_shouldReparentChildrenOfRemovedSpans() {
        // Parent (kept)
        var parent = createSpan("parent1", null, "query", Map.of(
                "jdbc.query[0]", "SELECT * FROM person",
                "peer.service", "sample_app_db"
        ), 10);

        // Duplicate child (removed)
        var duplicate = createSpan("dup1", "parent1", "query", Map.of(
                "jdbc.query[0]", "SELECT * FROM person",
                "peer.service", "dataSource"
        ), 11);

        // Grandchild of parent, child of duplicate → should be re-parented to parent
        var resultSet = createSpan("rs1", "dup1", "result-set", Map.of(
                "jdbc.row-count", "5",
                "peer.service", "dataSource"
        ), 12);

        var traceData = TraceData.fromSpans("trace1", List.of(parent, duplicate, resultSet));

        TraceData result = deduplicator.deduplicate(traceData);

        assertThat(result.spans()).hasSize(2);
        assertThat(result.spans().stream().map(SpanData::spanId).toList())
                .containsExactly("parent1", "rs1");

        // result-set should now point to parent1
        SpanData reparented = result.spans().stream()
                .filter(s -> "rs1".equals(s.spanId()))
                .findFirst().orElseThrow();
        assertThat(reparented.parentId()).isEqualTo("parent1");
    }

    @Test
    void deduplicate_shouldHandleNullTraceData() {
        TraceData result = deduplicator.deduplicate(null);
        assertThat(result).isNull();
    }

    @Test
    void deduplicate_shouldHandleTraceDataWithNullSpans() {
        var traceData = new TraceData("trace1", null, null, null, 0, null);

        TraceData result = deduplicator.deduplicate(traceData);

        assertThat(result.spans()).isNull();
    }

    @Test
    void deduplicate_shouldHandleTraceDataWithEmptySpans() {
        var traceData = new TraceData("trace1", null, null, null, 0, List.of());

        TraceData result = deduplicator.deduplicate(traceData);

        assertThat(result.spans()).isEmpty();
    }

    @Test
    void deduplicate_shouldHandleSpansWithoutParentInTrace() {
        // Two root spans (no parent) with same name — should NOT be deduplicated
        var span1 = createSpan("span1", null, "query", Map.of(
                "jdbc.query[0]", "SELECT 1",
                "peer.service", "db1"
        ), 10);

        var span2 = createSpan("span2", null, "query", Map.of(
                "jdbc.query[0]", "SELECT 1",
                "peer.service", "db2"
        ), 11);

        var traceData = TraceData.fromSpans("trace1", List.of(span1, span2));

        TraceData result = deduplicator.deduplicate(traceData);

        assertThat(result.spans()).hasSize(2);
    }

    @Test
    void deduplicate_shouldHandleMultipleDuplicatePairsInSameTrace() {
        // First pair: connection spans
        var connParent = createSpan("conn-p", null, "connection", Map.of(
                "jdbc.datasource.name", "sample_app_db"
        ), 10);
        var connChild = createSpan("conn-c", "conn-p", "connection", Map.of(
                "jdbc.datasource.name", "dataSource"
        ), 11);

        // Second pair: query spans
        var queryParent = createSpan("query-p", "conn-p", "query", Map.of(
                "jdbc.query[0]", "SELECT * FROM person",
                "peer.service", "sample_app_db"
        ), 20);
        var queryChild = createSpan("query-c", "query-p", "query", Map.of(
                "jdbc.query[0]", "SELECT * FROM person",
                "peer.service", "dataSource"
        ), 21);

        // Non-duplicate span
        var resultSet = createSpan("rs1", "query-c", "result-set", Map.of(
                "jdbc.row-count", "3",
                "peer.service", "dataSource"
        ), 22);

        var traceData = TraceData.fromSpans("trace1",
                List.of(connParent, connChild, queryParent, queryChild, resultSet));

        TraceData result = deduplicator.deduplicate(traceData);

        assertThat(result.spans()).hasSize(3);
        assertThat(result.spans().stream().map(SpanData::spanId).toList())
                .containsExactly("conn-p", "query-p", "rs1");

        // result-set should be re-parented to query-p (surviving parent)
        SpanData reparented = result.spans().stream()
                .filter(s -> "rs1".equals(s.spanId()))
                .findFirst().orElseThrow();
        assertThat(reparented.parentId()).isEqualTo("query-p");
    }

    @Test
    void deduplicate_shouldPreserveSpanOrder() {
        var httpSpan = createSpan("http1", null, "GET /api/person", Map.of(
                "http.method", "GET"
        ), 1);

        var parent = createSpan("parent1", "http1", "query", Map.of(
                "jdbc.query[0]", "SELECT * FROM person",
                "peer.service", "sample_app_db"
        ), 10);

        var child = createSpan("child1", "parent1", "query", Map.of(
                "jdbc.query[0]", "SELECT * FROM person",
                "peer.service", "dataSource"
        ), 11);

        var resultSet = createSpan("rs1", "child1", "result-set", Map.of(
                "jdbc.row-count", "5",
                "peer.service", "dataSource"
        ), 12);

        var traceData = TraceData.fromSpans("trace1", List.of(httpSpan, parent, child, resultSet));

        TraceData result = deduplicator.deduplicate(traceData);

        assertThat(result.spans()).hasSize(3);
        // Order should be preserved: http, parent(query), result-set
        assertThat(result.spans().stream().map(SpanData::spanId).toList())
                .containsExactly("http1", "parent1", "rs1");
    }

    private SpanData createSpan(String spanId, String parentId, String name,
                                Map<String, String> tags, long creationOrder) {
        Instant start = Instant.EPOCH.plusMillis(creationOrder * 100);
        Instant end = start.plusMillis(50);
        return new SpanData(
                "trace1", spanId, parentId, name, Span.Kind.CLIENT,
                start, end, Duration.ofMillis(50),
                new HashMap<>(tags), List.of(), null, null, null, null, null, List.of(), creationOrder
        );
    }
}

package net.osslabz.peekaboot.backend.mapper.trace;

import io.micrometer.tracing.Span;
import net.osslabz.peekaboot.backend.domain.trace.RootActionType;
import net.osslabz.peekaboot.backend.domain.trace.SpanNode;
import net.osslabz.peekaboot.backend.domain.trace.TraceStatus;
import net.osslabz.peekaboot.backend.domain.trace.TraceTree;
import net.osslabz.peekaboot.backend.tracing.store.SpanData;
import net.osslabz.peekaboot.backend.tracing.store.TraceData;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceTreeMapperTest {

    private final TraceTreeMapper mapper = new TraceTreeMapper();

    @Test
    void map_shouldBuildTreeFromFlatSpans() {
        // Given: A trace with root -> child1 -> grandchild, and root -> child2
        var rootSpan = createSpan("trace1", "span-root", null, "root-op",
                Span.Kind.SERVER, 0, 100, Map.of("service.name", "api"));
        var child1 = createSpan("trace1", "span-child1", "span-root", "child1-op",
                Span.Kind.CLIENT, 10, 40, Map.of("service.name", "api"));
        var child2 = createSpan("trace1", "span-child2", "span-root", "child2-op",
                Span.Kind.CLIENT, 50, 30, Map.of("service.name", "api"));
        var grandchild = createSpan("trace1", "span-grandchild", "span-child1", "grandchild-op",
                Span.Kind.CLIENT, 15, 20, Map.of("service.name", "api"));

        var traceData = TraceData.fromSpans("trace1", List.of(rootSpan, child1, child2, grandchild));

        // When
        TraceTree result = mapper.map(traceData);

        // Then
        assertThat(result.traceId()).isEqualTo("trace1");
        assertThat(result.rootSpan()).isNotNull();
        assertThat(result.rootSpan().spanId()).isEqualTo("span-root");
        assertThat(result.rootSpan().name()).isEqualTo("root-op");
        assertThat(result.rootSpan().children()).hasSize(2);

        // Children should be sorted by startTime
        assertThat(result.rootSpan().children().get(0).spanId()).isEqualTo("span-child1");
        assertThat(result.rootSpan().children().get(1).spanId()).isEqualTo("span-child2");

        // Grandchild should be under child1
        SpanNode child1Node = result.rootSpan().children().get(0);
        assertThat(child1Node.children()).hasSize(1);
        assertThat(child1Node.children().get(0).spanId()).isEqualTo("span-grandchild");
    }

    @Test
    void map_shouldIdentifyRootSpanWithNullParentId() {
        var rootSpan = createSpan("trace1", "root-id", null, "root-op",
                Span.Kind.SERVER, 0, 100, Map.of());
        var childSpan = createSpan("trace1", "child-id", "root-id", "child-op",
                Span.Kind.CLIENT, 10, 50, Map.of());

        var traceData = TraceData.fromSpans("trace1", List.of(childSpan, rootSpan)); // Intentionally reversed

        TraceTree result = mapper.map(traceData);

        assertThat(result.rootSpan()).isNotNull();
        assertThat(result.rootSpan().spanId()).isEqualTo("root-id");
        assertThat(result.rootOperation()).isEqualTo("root-op");
    }

    @Test
    void map_shouldKeepTagsOnEachSpan() {
        // Given: Parent span with two children, each with their own tags
        var parent = createSpan("trace1", "parent", null, "parent-op",
                Span.Kind.SERVER, 0, 100, Map.of("service.name", "api"));
        var child1 = createSpan("trace1", "child1", "parent", "child1-op",
                Span.Kind.CLIENT, 10, 30, Map.of("db.system", "postgresql", "db.name", "mydb"));
        var child2 = createSpan("trace1", "child2", "parent", "child2-op",
                Span.Kind.CLIENT, 50, 30, Map.of("db.system", "postgresql", "db.name", "mydb"));

        var traceData = TraceData.fromSpans("trace1", List.of(parent, child1, child2));

        // When
        TraceTree result = mapper.map(traceData);

        // Then: All spans keep their own tags (no hoisting)
        SpanNode parentNode = result.rootSpan();
        assertThat(parentNode.tags()).containsEntry("service.name", "api");
        assertThat(parentNode.tags()).doesNotContainKey("db.system");

        // Children keep their tags
        for (SpanNode child : parentNode.children()) {
            assertThat(child.tags()).containsEntry("db.system", "postgresql");
            assertThat(child.tags()).containsEntry("db.name", "mydb");
        }
    }

    @Test
    void map_shouldPreserveDifferentTagsOnChildren() {
        // Given: Children with different values for db.name
        var parent = createSpan("trace1", "parent", null, "parent-op",
                Span.Kind.SERVER, 0, 100, Map.of());
        var child1 = createSpan("trace1", "child1", "parent", "child1-op",
                Span.Kind.CLIENT, 10, 30, Map.of("db.system", "postgresql", "db.name", "db1"));
        var child2 = createSpan("trace1", "child2", "parent", "child2-op",
                Span.Kind.CLIENT, 50, 30, Map.of("db.system", "postgresql", "db.name", "db2"));

        var traceData = TraceData.fromSpans("trace1", List.of(parent, child1, child2));

        // When
        TraceTree result = mapper.map(traceData);

        // Then: Each span keeps its own tags
        SpanNode parentNode = result.rootSpan();
        assertThat(parentNode.tags()).isEmpty();

        // Children keep their individual tags
        assertThat(parentNode.children().get(0).tags()).containsEntry("db.system", "postgresql");
        assertThat(parentNode.children().get(0).tags()).containsEntry("db.name", "db1");
        assertThat(parentNode.children().get(1).tags()).containsEntry("db.system", "postgresql");
        assertThat(parentNode.children().get(1).tags()).containsEntry("db.name", "db2");
    }

    @Test
    void map_shouldNotHoistToInheritedAttributes() {
        // Given: All spans have the same service.name
        var root = createSpan("trace1", "root", null, "root-op",
                Span.Kind.SERVER, 0, 100, Map.of("service.name", "api-service"));
        var child = createSpan("trace1", "child", "root", "child-op",
                Span.Kind.CLIENT, 10, 30, Map.of("service.name", "api-service"));

        var traceData = TraceData.fromSpans("trace1", List.of(root, child));

        // When
        TraceTree result = mapper.map(traceData);

        // Then: No hoisting - inheritedAttributes is empty, tags stay on spans
        assertThat(result.inheritedAttributes()).isEmpty();
        assertThat(result.rootSpan().tags()).containsEntry("service.name", "api-service");
        assertThat(result.rootSpan().children().get(0).tags()).containsEntry("service.name", "api-service");
    }

    @Test
    void map_shouldCalculateTraceMetrics() {
        // Given: A trace with DB queries and HTTP calls
        var root = createSpan("trace1", "root", null, "GET /api/users",
                Span.Kind.SERVER, 0, 200, Map.of("http.method", "GET"));
        var dbQuery1 = createSpan("trace1", "db1", "root", "SELECT users",
                Span.Kind.CLIENT, 10, 50, Map.of("db.system", "postgresql"));
        var dbQuery2 = createSpan("trace1", "db2", "root", "SELECT roles",
                Span.Kind.CLIENT, 70, 30, Map.of("db.type", "sql"));
        var httpCall = createSpan("trace1", "http1", "root", "GET /external",
                Span.Kind.CLIENT, 110, 60, Map.of("http.url", "http://external.com"));

        var traceData = TraceData.fromSpans("trace1", List.of(root, dbQuery1, dbQuery2, httpCall));

        // When
        TraceTree result = mapper.map(traceData);

        // Then
        assertThat(result.metrics().totalSpans()).isEqualTo(4);
        assertThat(result.metrics().dbQueryCount()).isEqualTo(2);
        assertThat(result.metrics().dbTotalDurationMs()).isEqualTo(80L); // 50 + 30
        assertThat(result.metrics().httpCallCount()).isEqualTo(1);
        assertThat(result.metrics().httpTotalDurationMs()).isEqualTo(60L); // HTTP call duration
        assertThat(result.metrics().errorCount()).isEqualTo(0);
    }

    @Test
    void map_shouldCountErrors() {
        var root = createSpan("trace1", "root", null, "root-op", Span.Kind.SERVER, 0, 100, Map.of());
        var errorSpan = createSpanWithError("trace1", "error", "root", "error-op",
                Span.Kind.CLIENT, 10, 50, Map.of(), "Connection refused", "java.net.ConnectException");

        var traceData = TraceData.fromSpans("trace1", List.of(root, errorSpan));

        TraceTree result = mapper.map(traceData);

        assertThat(result.metrics().errorCount()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(TraceStatus.HAS_ERRORS);
    }

    @Test
    void map_shouldReturnOkStatusWhenNoErrors() {
        var root = createSpan("trace1", "root", null, "root-op", Span.Kind.SERVER, 0, 100, Map.of());

        var traceData = TraceData.fromSpans("trace1", List.of(root));

        TraceTree result = mapper.map(traceData);

        assertThat(result.status()).isEqualTo(TraceStatus.OK);
    }

    @Test
    void map_shouldHandleEmptyTrace() {
        var traceData = new TraceData("trace1", null, null, null, 0, List.of());

        TraceTree result = mapper.map(traceData);

        assertThat(result.traceId()).isEqualTo("trace1");
        assertThat(result.rootSpan()).isNull();
        assertThat(result.metrics().totalSpans()).isEqualTo(0);
        assertThat(result.status()).isEqualTo(TraceStatus.OK);
    }

    @Test
    void map_shouldHandleSingleSpanTrace() {
        var singleSpan = createSpan("trace1", "only-span", null, "single-op",
                Span.Kind.SERVER, 0, 100, Map.of("service.name", "api"));

        var traceData = TraceData.fromSpans("trace1", List.of(singleSpan));

        TraceTree result = mapper.map(traceData);

        assertThat(result.rootSpan()).isNotNull();
        assertThat(result.rootSpan().spanId()).isEqualTo("only-span");
        assertThat(result.rootSpan().children()).isEmpty();
        assertThat(result.metrics().totalSpans()).isEqualTo(1);
        assertThat(result.rootOperation()).isEqualTo("single-op");
    }

    @Test
    void map_shouldConvertTimesToMilliseconds() {
        var baseTime = Instant.parse("2024-01-15T10:00:00Z");
        var span = new SpanData(
                "trace1", "span1", null, "op",
                Span.Kind.SERVER, baseTime, baseTime.plusMillis(150),
                Duration.ofMillis(150), Map.of(), List.of(),
                null, null, null, null, null, List.of(), 0
        );

        var traceData = TraceData.fromSpans("trace1", List.of(span));

        TraceTree result = mapper.map(traceData);

        assertThat(result.startTimeMs()).isEqualTo(baseTime.toEpochMilli());
        assertThat(result.durationMs()).isEqualTo(150L);
        assertThat(result.rootSpan().startTimeMs()).isEqualTo(baseTime.toEpochMilli());
        assertThat(result.rootSpan().durationMs()).isEqualTo(150L);
    }

    @Test
    void map_shouldSetSpanStatusBasedOnError() {
        var okSpan = createSpan("trace1", "ok", null, "ok-op", Span.Kind.SERVER, 0, 100, Map.of());
        var errorChild = createSpanWithError("trace1", "error", "ok", "error-op",
                Span.Kind.CLIENT, 10, 50, Map.of(), "Error", "Exception");

        var traceData = TraceData.fromSpans("trace1", List.of(okSpan, errorChild));

        TraceTree result = mapper.map(traceData);

        assertThat(result.rootSpan().status()).isEqualTo("OK");
        assertThat(result.rootSpan().children().get(0).status()).isEqualTo("ERROR");
    }

    @Test
    void map_shouldHandleOrphanSpans() {
        // Given: A span whose parent doesn't exist in the trace (orphan)
        var orphan = createSpan("trace1", "orphan", "missing-parent", "orphan-op",
                Span.Kind.CLIENT, 0, 50, Map.of());

        var traceData = TraceData.fromSpans("trace1", List.of(orphan));

        // When: The orphan should become a root
        TraceTree result = mapper.map(traceData);

        // Then
        assertThat(result.rootSpan()).isNotNull();
        assertThat(result.rootSpan().spanId()).isEqualTo("orphan");
    }

    // --- Root Action Type Detection Tests ---

    @Test
    void map_shouldDetectHttpRequestRootActionType() {
        var rootSpan = createSpan("trace1", "root", null, "GET /api/users",
                Span.Kind.SERVER, 0, 100, Map.of("http.method", "GET", "http.url", "/api/users"));

        var traceData = TraceData.fromSpans("trace1", List.of(rootSpan));

        TraceTree result = mapper.map(traceData);

        assertThat(result.rootActionType()).isEqualTo(RootActionType.HTTP_REQUEST);
    }

    @Test
    void map_shouldDetectMessageConsumerRootActionTypeFromConsumerKind() {
        var rootSpan = createSpan("trace1", "root", null, "receive message",
                Span.Kind.CONSUMER, 0, 100, Map.of());

        var traceData = TraceData.fromSpans("trace1", List.of(rootSpan));

        TraceTree result = mapper.map(traceData);

        assertThat(result.rootActionType()).isEqualTo(RootActionType.MESSAGE_CONSUMER);
    }

    @Test
    void map_shouldDetectMessageConsumerRootActionTypeFromMessagingTags() {
        var rootSpan = createSpan("trace1", "root", null, "process message",
                Span.Kind.SERVER, 0, 100, Map.of("messaging.system", "kafka", "messaging.destination", "orders"));

        var traceData = TraceData.fromSpans("trace1", List.of(rootSpan));

        TraceTree result = mapper.map(traceData);

        assertThat(result.rootActionType()).isEqualTo(RootActionType.MESSAGE_CONSUMER);
    }

    @Test
    void map_shouldDetectRpcCallRootActionType() {
        var rootSpan = createSpan("trace1", "root", null, "grpc.UserService/GetUser",
                Span.Kind.SERVER, 0, 100, Map.of("rpc.system", "grpc", "rpc.service", "UserService"));

        var traceData = TraceData.fromSpans("trace1", List.of(rootSpan));

        TraceTree result = mapper.map(traceData);

        assertThat(result.rootActionType()).isEqualTo(RootActionType.RPC_CALL);
    }

    @Test
    void map_shouldDetectScheduledJobRootActionTypeFromSchedule() {
        var rootSpan = createSpan("trace1", "root", null, "scheduled-task",
                Span.Kind.SERVER, 0, 100, Map.of());

        var traceData = TraceData.fromSpans("trace1", List.of(rootSpan));

        TraceTree result = mapper.map(traceData);

        assertThat(result.rootActionType()).isEqualTo(RootActionType.SCHEDULED_JOB);
    }

    @Test
    void map_shouldDetectScheduledJobRootActionTypeFromCron() {
        var rootSpan = createSpan("trace1", "root", null, "cron-cleanup",
                Span.Kind.SERVER, 0, 100, Map.of());

        var traceData = TraceData.fromSpans("trace1", List.of(rootSpan));

        TraceTree result = mapper.map(traceData);

        assertThat(result.rootActionType()).isEqualTo(RootActionType.SCHEDULED_JOB);
    }

    @Test
    void map_shouldDetectScheduledJobRootActionTypeFromTimer() {
        var rootSpan = createSpan("trace1", "root", null, "timer-heartbeat",
                Span.Kind.SERVER, 0, 100, Map.of());

        var traceData = TraceData.fromSpans("trace1", List.of(rootSpan));

        TraceTree result = mapper.map(traceData);

        assertThat(result.rootActionType()).isEqualTo(RootActionType.SCHEDULED_JOB);
    }

    @Test
    void map_shouldDetectScheduledJobRootActionTypeFromJob() {
        var rootSpan = createSpan("trace1", "root", null, "batch-job-processor",
                Span.Kind.SERVER, 0, 100, Map.of());

        var traceData = TraceData.fromSpans("trace1", List.of(rootSpan));

        TraceTree result = mapper.map(traceData);

        assertThat(result.rootActionType()).isEqualTo(RootActionType.SCHEDULED_JOB);
    }

    @Test
    void map_shouldDetectDatabaseRootActionType() {
        var rootSpan = createSpan("trace1", "root", null, "SELECT * FROM users",
                Span.Kind.CLIENT, 0, 100, Map.of("db.system", "postgresql", "db.statement", "SELECT * FROM users"));

        var traceData = TraceData.fromSpans("trace1", List.of(rootSpan));

        TraceTree result = mapper.map(traceData);

        assertThat(result.rootActionType()).isEqualTo(RootActionType.DATABASE);
    }

    @Test
    void map_shouldDetectInternalRootActionType() {
        var rootSpan = createSpan("trace1", "root", null, "internal-processing",
                null, 0, 100, Map.of());

        var traceData = TraceData.fromSpans("trace1", List.of(rootSpan));

        TraceTree result = mapper.map(traceData);

        assertThat(result.rootActionType()).isEqualTo(RootActionType.INTERNAL);
    }

    @Test
    void map_shouldDetectUnknownRootActionTypeAsFallback() {
        var rootSpan = createSpan("trace1", "root", null, "produce-event",
                Span.Kind.PRODUCER, 0, 100, Map.of());

        var traceData = TraceData.fromSpans("trace1", List.of(rootSpan));

        TraceTree result = mapper.map(traceData);

        assertThat(result.rootActionType()).isEqualTo(RootActionType.UNKNOWN);
    }

    @Test
    void map_shouldDetectHttpRequestForServerKindWithoutSpecificTags() {
        var rootSpan = createSpan("trace1", "root", null, "handle-request",
                Span.Kind.SERVER, 0, 100, Map.of());

        var traceData = TraceData.fromSpans("trace1", List.of(rootSpan));

        TraceTree result = mapper.map(traceData);

        assertThat(result.rootActionType()).isEqualTo(RootActionType.HTTP_REQUEST);
    }

    // Helper methods to create test data
    private SpanData createSpan(String traceId, String spanId, String parentId, String name,
                                Span.Kind kind, long startOffsetMs, long durationMs,
                                Map<String, String> tags) {
        Instant start = Instant.EPOCH.plusMillis(startOffsetMs);
        Instant end = start.plusMillis(durationMs);
        return new SpanData(
                traceId, spanId, parentId, name, kind,
                start, end, Duration.ofMillis(durationMs),
                tags, List.of(), null, null, null, null, null, List.of(), startOffsetMs
        );
    }

    private SpanData createSpanWithError(String traceId, String spanId, String parentId, String name,
                                         Span.Kind kind, long startOffsetMs, long durationMs,
                                         Map<String, String> tags, String errorMessage, String errorClass) {
        Instant start = Instant.EPOCH.plusMillis(startOffsetMs);
        Instant end = start.plusMillis(durationMs);
        return new SpanData(
                traceId, spanId, parentId, name, kind,
                start, end, Duration.ofMillis(durationMs),
                tags, List.of(), errorMessage, errorClass, null, null, null, List.of(), startOffsetMs
        );
    }
}

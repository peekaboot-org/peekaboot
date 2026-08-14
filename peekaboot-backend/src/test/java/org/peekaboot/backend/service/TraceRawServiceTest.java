package org.peekaboot.backend.service;

import org.peekaboot.backend.domain.trace.QueryInfo;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceRawData;
import org.peekaboot.backend.domain.trace.TraceRawResponse;
import org.peekaboot.backend.mapper.trace.QueryExtractor;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TraceRawServiceTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private InMemoryTraceStore store;
    private TraceRawService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryTraceStore(100, 50, Duration.ofMinutes(5));
        service = new TraceRawService(store, new QueryExtractor());
    }

    @Test
    void tracingAvailableWhenStorePresent() {
        assertThat(service.isTracingAvailable()).isTrue();
    }

    @Test
    void tracingUnavailableWithoutStore() {
        TraceRawService noStore = new TraceRawService(null, new QueryExtractor());

        assertThat(noStore.isTracingAvailable()).isFalse();
    }

    @Test
    void getTracesMapsSpansLogsQueriesAndHttpExchange() {
        store.addSpan(span("t1", "root", null, "GET /persons", 100, Map.of(), null));
        store.addSpan(span("t1", "db", "root", "SELECT * FROM person", 40,
                Map.of("db.system", "h2", "db.statement", "SELECT * FROM person"), null));
        store.addLog(new LogCapturedEvent("t1", "root", START, "INFO", "PersonService", "loaded", "main"));
        store.setRequest(new RequestCompletedEvent(
                "t1", "GET", "/persons", null,
                Map.of(), null, false,
                "PersonController", "list",
                Map.of(), Map.of(), List.of(),
                200, Map.of(), 120));

        TraceRawResponse response = service.getTraces(10, TraceBucket.ALL);

        assertThat(response.traces()).hasSize(1);
        TraceRawData trace = response.traces().getFirst();
        assertThat(trace.traceId()).isEqualTo("t1");
        assertThat(trace.durationMs()).isEqualTo(100);
        assertThat(trace.spans()).hasSize(2);
        assertThat(trace.logs()).containsExactly(
                new TraceLog("root", START, "INFO", "PersonService", "loaded", "main"));
        assertThat(trace.queries())
                .extracting(QueryInfo::sql, QueryInfo::dbSystem, QueryInfo::durationMs)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("SELECT * FROM person", "h2", 40L));
        assertThat(trace.httpExchange()).isNotNull();
        assertThat(trace.httpExchange().response().status()).isEqualTo(200);
        assertThat(trace.summary().spans().count()).isEqualTo(2);
        assertThat(trace.summary().spans().totalDurationMs()).isEqualTo(140);
        assertThat(trace.summary().queries().count()).isEqualTo(1);
        assertThat(trace.summary().queries().totalDurationMs()).isEqualTo(40);
        assertThat(trace.summary().logs().count()).isEqualTo(1);
        assertThat(trace.summary().errors().count()).isZero();
    }

    @Test
    void getTracesAggregatesSummaryAcrossTraces() {
        store.addSpan(span("t1", "s1", null, "op", 50, Map.of(), null));
        store.addSpan(span("t2", "s2", null, "op", 70, Map.of(), "java.lang.RuntimeException"));

        TraceRawResponse response = service.getTraces(10, TraceBucket.ALL);

        assertThat(response.summary().traceCount()).isEqualTo(2);
        assertThat(response.summary().spans().count()).isEqualTo(2);
        assertThat(response.summary().spans().totalDurationMs()).isEqualTo(120);
        assertThat(response.summary().errors().count()).isEqualTo(1);
    }

    @Test
    void getTracesRespectsBucket() {
        store.addSpan(span("plain", "s1", null, "op", 10, Map.of(), null));
        store.addSpan(span("broken", "s2", null, "op", 10, Map.of(), "java.lang.RuntimeException"));

        TraceRawResponse errors = service.getTraces(10, TraceBucket.ERRORS);

        assertThat(errors.traces())
                .extracting(TraceRawData::traceId)
                .containsExactly("broken");
    }

    @Test
    void getTracesRespectsLimit() {
        store.addSpan(span("t1", "s1", null, "op", 10, Map.of(), null));
        store.addSpan(span("t2", "s2", null, "op", 10, Map.of(), null));
        store.addSpan(span("t3", "s3", null, "op", 10, Map.of(), null));

        assertThat(service.getTraces(2, TraceBucket.ALL).traces()).hasSize(2);
    }

    @Test
    void getTracesWithoutStoreReturnsEmptyResponse() {
        TraceRawService noStore = new TraceRawService(null, new QueryExtractor());

        TraceRawResponse response = noStore.getTraces(10, TraceBucket.ALL);

        assertThat(response.traces()).isEmpty();
        assertThat(response.summary().traceCount()).isZero();
    }

    @Test
    void getTraceMapsSingleBundle() {
        store.addSpan(span("t1", "s1", null, "op", 10, Map.of(), null));

        Optional<TraceRawData> trace = service.getTrace("t1");

        assertThat(trace).isPresent();
        assertThat(trace.get().traceId()).isEqualTo("t1");
        assertThat(trace.get().spans()).hasSize(1);
    }

    @Test
    void getTraceUnknownIdReturnsEmpty() {
        assertThat(service.getTrace("nope")).isEmpty();
    }

    @Test
    void getTraceWithoutStoreReturnsEmpty() {
        TraceRawService noStore = new TraceRawService(null, new QueryExtractor());

        assertThat(noStore.getTrace("t1")).isEmpty();
    }

    private SpanData span(String traceId, String spanId, String parentId, String name,
            long durationMs, Map<String, String> tags, String errorClass) {
        return new SpanData(traceId, spanId, parentId, name, null,
                START, START.plusMillis(durationMs), Duration.ofMillis(durationMs),
                tags, List.of(),
                errorClass != null ? "boom" : null, errorClass,
                null, null, null, List.of(), store.nextCreationOrder());
    }
}

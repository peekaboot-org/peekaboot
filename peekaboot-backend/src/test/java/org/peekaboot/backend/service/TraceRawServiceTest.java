package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.QueryInfo;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceRawData;
import org.peekaboot.backend.domain.trace.TraceRawResponse;
import org.peekaboot.backend.mapper.trace.QueryExtractor;
import org.peekaboot.backend.mapper.trace.TraceRawMapper;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceBucket;

class TraceRawServiceTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private InMemoryTraceStore store;
    private TraceRawService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryTraceStore(100, 50, Duration.ofMinutes(5));
        service = new TraceRawService(store, new TraceRawMapper(new QueryExtractor()));
    }

    @Test
    void tracingAvailableWhenStorePresent() {
        assertThat(service.isTracingAvailable()).isTrue();
    }

    @Test
    void tracingUnavailableWithoutStore() {
        TraceRawService noStore = new TraceRawService(null, new TraceRawMapper(new QueryExtractor()));

        assertThat(noStore.isTracingAvailable()).isFalse();
    }

    @Test
    void getTracesMapsSpansLogsQueriesAndHttpExchange() {
        store.addSpan(span("t1", "root", null, "GET /persons", 100, Map.of(), null));
        store.addSpan(span(
                "t1",
                "db",
                "root",
                "SELECT * FROM person",
                40,
                Map.of("db.system", "h2", "db.statement", "SELECT * FROM person"),
                null));
        store.addLog(new LogCapturedEvent("t1", "root", START, "INFO", "PersonService", "loaded", "main"));
        store.setRequest(new RequestCompletedEvent(
                "t1",
                "GET",
                "/persons",
                null,
                Map.of(),
                null,
                false,
                "PersonController",
                "list",
                Map.of(),
                Map.of(),
                List.of(),
                200,
                Map.of(),
                120));

        TraceRawResponse response = service.getTraces(10, TraceBucket.ALL);

        assertThat(response.traces()).hasSize(1);
        TraceRawData trace = response.traces().getFirst();
        assertThat(trace.traceId()).isEqualTo("t1");
        assertThat(trace.durationMs()).isEqualTo(100);
        assertThat(trace.spans()).hasSize(2);
        assertThat(trace.logs())
                .containsExactly(new TraceLog("root", START, "INFO", "PersonService", "loaded", "main"));
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

        assertThat(errors.traces()).extracting(TraceRawData::traceId).containsExactly("broken");
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
        TraceRawService noStore = new TraceRawService(null, new TraceRawMapper(new QueryExtractor()));

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
        TraceRawService noStore = new TraceRawService(null, new TraceRawMapper(new QueryExtractor()));

        assertThat(noStore.getTrace("t1")).isEmpty();
    }

    /**
     * The raw trace endpoints embed SpanData directly (not routed through
     * TraceTreeMapper), so they need their own tag masking rather than inheriting it.
     */
    @Test
    void getTracesMasksSensitiveShapedSpanTags() {
        store.addSpan(span(
                "t1",
                "root",
                null,
                "GET /persons",
                100,
                Map.of("http.request.header.authorization", "Bearer abc123", "http.method", "GET"),
                null));

        TraceRawResponse response = service.getTraces(10, TraceBucket.ALL);

        Map<String, String> tags =
                response.traces().getFirst().spans().getFirst().tags();
        assertThat(tags).containsEntry("http.request.header.authorization", "******");
        assertThat(tags).containsEntry("http.method", "GET");
    }

    @Test
    void getTraceMasksSensitiveShapedSpanTags() {
        store.addSpan(span(
                "t1",
                "root",
                null,
                "GET /persons",
                100,
                Map.of("http.url", "https://admin:hunter2@example.com/api"),
                null));

        Optional<TraceRawData> trace = service.getTrace("t1");

        assertThat(trace).isPresent();
        assertThat(trace.get().spans().getFirst().tags().get("http.url")).isEqualTo("https://******@example.com/api");
    }

    // TraceTree already carries a truncated flag (Known Defect: max-spans-per-trace
    // eviction); TraceRawData didn't, even though /raw is an API surface too. bundle's
    // own truncated flag (set once the maxSpansPerTrace cap actually evicts a real span)
    // must reach the response.
    @Test
    void getTraceCarriesTheTruncatedFlagFromTheBundle() {
        InMemoryTraceStore smallStore = new InMemoryTraceStore(100, 1, Duration.ofMinutes(5));
        TraceRawService smallService = new TraceRawService(smallStore, new TraceRawMapper(new QueryExtractor()));
        smallStore.addSpan(span("t1", "root", null, "GET /persons", 100, Map.of(), null));
        smallStore.addSpan(span("t1", "child", "root", "SELECT", 10, Map.of(), null));

        Optional<TraceRawData> trace = smallService.getTrace("t1");

        assertThat(trace).isPresent();
        assertThat(trace.get().truncated()).isTrue();
    }

    @Test
    void getTraceCarriesFalseWhenNotTruncated() {
        store.addSpan(span("t1", "root", null, "GET /persons", 100, Map.of(), null));

        Optional<TraceRawData> trace = service.getTrace("t1");

        assertThat(trace).isPresent();
        assertThat(trace.get().truncated()).isFalse();
    }

    // Known Defect I5: errorMessage/errorClass used to pass straight through unmasked -
    // only tags went through TagMasker - even though a realistic exception message can
    // itself carry a credential, e.g. an HTTP client exception that echoes the failing
    // request's URL back with a query-string API key attached.
    @Test
    void getTraceMasksACredentialEmbeddedInTheSpanErrorMessage() {
        SpanData errorSpan = new SpanData(
                "t1",
                "root",
                null,
                "GET /persons",
                null,
                START,
                START.plusMillis(100),
                Duration.ofMillis(100),
                Map.of(),
                List.of(),
                "HttpClientErrorException: 401 on GET \"https://api.x/v1?api_key=SECRET\"",
                "org.springframework.web.client.HttpClientErrorException",
                null,
                null,
                null,
                List.of(),
                store.nextCreationOrder());
        store.addSpan(errorSpan);

        Optional<TraceRawData> trace = service.getTrace("t1");

        assertThat(trace).isPresent();
        String errorMessage = trace.get().spans().getFirst().errorMessage();
        assertThat(errorMessage).doesNotContain("SECRET").contains("api_key=******");
    }

    private SpanData span(
            String traceId,
            String spanId,
            String parentId,
            String name,
            long durationMs,
            Map<String, String> tags,
            String errorClass) {
        return new SpanData(
                traceId,
                spanId,
                parentId,
                name,
                null,
                START,
                START.plusMillis(durationMs),
                Duration.ofMillis(durationMs),
                tags,
                List.of(),
                errorClass != null ? "boom" : null,
                errorClass,
                null,
                null,
                null,
                List.of(),
                store.nextCreationOrder());
    }
}

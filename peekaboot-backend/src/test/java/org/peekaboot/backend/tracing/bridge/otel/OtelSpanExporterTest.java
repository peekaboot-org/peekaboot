package org.peekaboot.backend.tracing.bridge.otel;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.event.SpanDataEvent;
import org.peekaboot.backend.tracing.event.TraceDiscardedEvent;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.springframework.context.ApplicationEventPublisher;

class OtelSpanExporterTest {

    private static final AttributeKey<String> SERVICE_NAME_KEY = AttributeKey.stringKey("service.name");
    private static final AttributeKey<String> HTTP_URL_KEY = AttributeKey.stringKey("http.url");
    private static final AttributeKey<String> HTTP_TARGET_KEY = AttributeKey.stringKey("http.target");
    private static final AttributeKey<String> URL_PATH_KEY = AttributeKey.stringKey("url.path");
    private static final long START_NANOS =
            Instant.parse("2026-01-01T00:00:00Z").getEpochSecond() * 1_000_000_000L;

    private InMemoryTraceStore storage;
    private List<SpanDataEvent> publishedEvents;
    private ApplicationEventPublisher eventPublisher;
    private OtelSpanExporter exporter;

    @BeforeEach
    void setUp() {
        storage = TraceStores.withDefaults();
        publishedEvents = new ArrayList<>();
        eventPublisher = event -> {
            if (event instanceof SpanDataEvent spanDataEvent) {
                publishedEvents.add(spanDataEvent);
                storage.addSpan(spanDataEvent.spanData());
            } else if (event instanceof TraceDiscardedEvent discarded) {
                storage.discard(discarded.traceId());
            }
        };
        exporter = new OtelSpanExporter(eventPublisher, PeekabootPaths.defaults());
    }

    @Test
    void assignsIncreasingCreationOrdersInExportOrder() {
        String traceId = "0123456789abcdef0123456789abcdef";
        exporter.export(List.of(
                createTestSpan(traceId, "0000000000000001", "first", SpanKind.SERVER),
                createTestSpan(traceId, "0000000000000002", "second", SpanKind.CLIENT)));

        List<org.peekaboot.backend.tracing.store.SpanData> spans = storedSpans(traceId);
        assertThat(spans).hasSize(2);
        assertThat(spans.get(1).creationOrder()).isGreaterThan(spans.get(0).creationOrder());
    }

    @Test
    void convertsAndPublishesAnOtelSpan() {
        String traceId = "0123456789abcdef0123456789abcdef";
        String spanId = "0123456789abcdef";
        SpanData otelSpan = createTestSpan(traceId, spanId, "test-operation", SpanKind.SERVER);

        CompletableResultCode result = exporter.export(List.of(otelSpan));

        assertThat(result.isSuccess()).isTrue();
        assertThat(publishedEvents).hasSize(1);

        List<org.peekaboot.backend.tracing.store.SpanData> spans = storedSpans(traceId);
        assertThat(spans).hasSize(1);

        org.peekaboot.backend.tracing.store.SpanData stored = spans.getFirst();
        assertThat(stored.name()).isEqualTo("test-operation");
        assertThat(stored.kind()).isEqualTo(io.micrometer.tracing.Span.Kind.SERVER);
        assertThat(stored.traceId()).isEqualTo(traceId);
        assertThat(stored.spanId()).isEqualTo(spanId);
    }

    @Test
    void exportsEachSpanIntoItsOwnTrace() {
        String traceId1 = "aaaabbbbccccddddeeeeffffaaaabbbb";
        String traceId2 = "aaaabbbbccccddddeeeeffffaaaabbbc";

        SpanData span1 = createTestSpan(traceId1, "aaaa000000000001", "op1", SpanKind.CLIENT);
        SpanData span2 = createTestSpan(traceId1, "aaaa000000000002", "op2", SpanKind.SERVER);
        SpanData span3 = createTestSpan(traceId2, "aaaa000000000003", "op3", SpanKind.PRODUCER);

        exporter.export(List.of(span1, span2, span3));

        assertThat(publishedEvents).hasSize(3);
        assertThat(storedSpans(traceId1))
                .extracting(org.peekaboot.backend.tracing.store.SpanData::spanId)
                .containsExactly("aaaa000000000001", "aaaa000000000002");
        assertThat(storedSpans(traceId2))
                .extracting(org.peekaboot.backend.tracing.store.SpanData::spanId)
                .containsExactly("aaaa000000000003");
    }

    /**
     * The exporter buffers nothing, so a flush has nothing to wait for: it must complete
     * synchronously (the SDK's BatchSpanProcessor blocks on it) and leave later exports intact.
     */
    @Test
    void flushCompletesImmediatelyWithoutDroppingLaterExports() {
        String traceId = "0123456789abcdef0123456789abcdef";

        CompletableResultCode result = exporter.flush();
        exporter.export(List.of(createTestSpan(traceId, "0000000000000001", "after-flush", SpanKind.SERVER)));

        assertThat(result.isDone()).isTrue();
        assertThat(result.isSuccess()).isTrue();
        assertThat(publishedEvents).hasSize(1);
    }

    @Test
    void shutdownCompletesImmediately() {
        CompletableResultCode result = exporter.shutdown();

        assertThat(result.isDone()).isTrue();
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void skipsSpanWhenPathAttributeMatchesAnExcludedPrefix() {
        String traceId = "0123456789abcdef0123456789abcdef";
        SpanData span = testSpanBuilder(traceId, "0000000000000001", "GET /actuator/health", SpanKind.SERVER)
                .setAttributes(Attributes.of(URL_PATH_KEY, "/actuator/health"))
                .build();

        exporter.export(List.of(span));

        assertThat(publishedEvents).isEmpty();
        assertThat(storage.getTrace(traceId)).isEmpty();
    }

    @Test
    void skipsSpanWhenNameContainsPeekabootPath() {
        String traceId = "0123456789abcdef0123456789abcdef";
        SpanData span = testSpanBuilder(traceId, "0000000000000001", "GET /peekaboot/api/traces", SpanKind.SERVER)
                .build();

        exporter.export(List.of(span));

        assertThat(publishedEvents).isEmpty();
        assertThat(storage.getTrace(traceId)).isEmpty();
    }

    /**
     * Children export before their root, so the JDBC span of a {@code /actuator/health}
     * probe is already stored when the root arrives without a path Peekaboot keeps;
     * skipping that root discards what its trace has stored.
     */
    @Test
    void skippingARootSpanDiscardsWhatItsTraceHasAlreadyStored() {
        String traceId = "0123456789abcdef0123456789abcdef";
        String rootSpanId = "0000000000000001";
        SpanContext rootContext =
                SpanContext.create(traceId, rootSpanId, TraceFlags.getSampled(), TraceState.getDefault());
        SpanData jdbcChild = testSpanBuilder(traceId, "0000000000000002", "connection", SpanKind.CLIENT)
                .setParentSpanContext(rootContext)
                .setAttributes(Attributes.of(AttributeKey.stringKey("jdbc.datasource.name"), "dataSource"))
                .build();
        SpanData root = testSpanBuilder(traceId, rootSpanId, "GET /actuator/health", SpanKind.SERVER)
                .setAttributes(Attributes.of(URL_PATH_KEY, "/actuator/health"))
                .build();

        exporter.export(List.of(jdbcChild));
        assertThat(storage.getTrace(traceId)).isPresent();
        exporter.export(List.of(root));

        assertThat(storage.getTrace(traceId)).isEmpty();
    }

    /**
     * The exclusions describe inbound requests. An outbound call is never Peekaboot's own
     * request, however its name is spelled, so a CLIENT span stays in its trace.
     */
    @Test
    void keepsAClientSpanWhoseNameSpellsThePeekabootPrefix() {
        String traceId = "0123456789abcdef0123456789abcdef";
        String rootSpanId = "0000000000000001";
        SpanContext rootContext =
                SpanContext.create(traceId, rootSpanId, TraceFlags.getSampled(), TraceState.getDefault());
        SpanData peekabootCall = testSpanBuilder(
                        traceId, "0000000000000002", "GET /peekaboot/api/features", SpanKind.CLIENT)
                .setParentSpanContext(rootContext)
                .build();
        SpanData root = testSpanBuilder(traceId, rootSpanId, "GET /api/users", SpanKind.SERVER)
                .setAttributes(Attributes.of(URL_PATH_KEY, "/api/users"))
                .build();

        exporter.export(List.of(peekabootCall, root));

        assertThat(storedSpans(traceId))
                .extracting(org.peekaboot.backend.tracing.store.SpanData::spanId)
                .containsExactly("0000000000000002", rootSpanId);
    }

    /**
     * A health check against another service carries a remote URL whose path starts with an
     * excluded prefix. Dropping it would leave a silent gap where the slowest call may be.
     */
    @Test
    void keepsAClientSpanWhoseRemotePathStartsWithAnExcludedPrefix() {
        String traceId = "0123456789abcdef0123456789abcdef";
        String rootSpanId = "0000000000000001";
        SpanContext rootContext =
                SpanContext.create(traceId, rootSpanId, TraceFlags.getSampled(), TraceState.getDefault());
        SpanData healthCheck = testSpanBuilder(traceId, "0000000000000002", "GET", SpanKind.CLIENT)
                .setParentSpanContext(rootContext)
                .setAttributes(Attributes.of(HTTP_URL_KEY, "http://other/actuator/health"))
                .build();
        SpanData root = testSpanBuilder(traceId, rootSpanId, "GET /api/users", SpanKind.SERVER)
                .setAttributes(Attributes.of(URL_PATH_KEY, "/api/users"))
                .build();

        exporter.export(List.of(healthCheck, root));

        assertThat(storedSpans(traceId))
                .extracting(org.peekaboot.backend.tracing.store.SpanData::spanId)
                .containsExactly("0000000000000002", rootSpanId);
    }

    /**
     * A host route that merely spells Peekaboot's prefix deeper in its own path is not
     * Peekaboot's request: its path tag says so, and a span that carries one is judged by
     * it alone - otherwise the name match would discard the whole trace.
     */
    @Test
    void keepsAHostRouteWhosePathSpellsThePeekabootPrefix() {
        String traceId = "0123456789abcdef0123456789abcdef";
        String rootSpanId = "0000000000000001";
        SpanContext rootContext =
                SpanContext.create(traceId, rootSpanId, TraceFlags.getSampled(), TraceState.getDefault());
        SpanData jdbcChild = testSpanBuilder(traceId, "0000000000000002", "select", SpanKind.CLIENT)
                .setParentSpanContext(rootContext)
                .build();
        SpanData root = testSpanBuilder(traceId, rootSpanId, "GET /admin/peekaboot/status", SpanKind.SERVER)
                .setAttributes(Attributes.of(URL_PATH_KEY, "/admin/peekaboot/status"))
                .build();

        exporter.export(List.of(jdbcChild, root));

        assertThat(storedSpans(traceId))
                .extracting(org.peekaboot.backend.tracing.store.SpanData::spanId)
                .containsExactly("0000000000000002", rootSpanId);
    }

    @Test
    void exportsSpanWhenPathDoesNotMatchAnyExclusionRule() {
        // Negative control for the skip tests above: a span whose path clearly
        // isn't excluded must actually be exported, not just "not asserted".
        String traceId = "0123456789abcdef0123456789abcdef";
        SpanData span = testSpanBuilder(traceId, "0000000000000001", "GET /api/users", SpanKind.SERVER)
                .setAttributes(Attributes.of(URL_PATH_KEY, "/api/users"))
                .build();

        exporter.export(List.of(span));

        assertThat(publishedEvents).hasSize(1);
        assertThat(storage.getTrace(traceId)).isPresent();
    }

    @Test
    void prefersUrlPathOverHttpTargetTagWhenBothPresent() {
        // url.path is present but doesn't itself match any exclusion rule; if
        // extractPath() genuinely checks url.path first (short-circuiting
        // before ever consulting http.target), the span must NOT be skipped
        // even though http.target alone would match.
        String traceId = "0123456789abcdef0123456789abcdef";
        SpanData span = testSpanBuilder(traceId, "0000000000000001", "GET /keep-me", SpanKind.SERVER)
                .setAttributes(Attributes.builder()
                        .put(URL_PATH_KEY, "/keep-me")
                        .put(HTTP_TARGET_KEY, "/actuator/info")
                        .build())
                .build();

        exporter.export(List.of(span));

        assertThat(publishedEvents).hasSize(1);
        assertThat(storage.getTrace(traceId)).isPresent();
    }

    @Test
    void prefersUrlPathOverHttpUrlTagWhenBothPresent() {
        String traceId = "0123456789abcdef0123456789abcdef";
        SpanData span = testSpanBuilder(traceId, "0000000000000001", "GET /keep-me", SpanKind.SERVER)
                .setAttributes(Attributes.builder()
                        .put(URL_PATH_KEY, "/keep-me")
                        .put(HTTP_URL_KEY, "http://localhost:8080/actuator/metrics")
                        .build())
                .build();

        exporter.export(List.of(span));

        assertThat(publishedEvents).hasSize(1);
        assertThat(storage.getTrace(traceId)).isPresent();
    }

    @Test
    void extractsPathFromHttpTargetTagWhenUrlPathAbsent() {
        String traceId = "0123456789abcdef0123456789abcdef";
        SpanData span = testSpanBuilder(traceId, "0000000000000001", "GET /actuator/info", SpanKind.SERVER)
                .setAttributes(Attributes.of(HTTP_TARGET_KEY, "/actuator/info"))
                .build();

        exporter.export(List.of(span));

        assertThat(publishedEvents).isEmpty();
        assertThat(storage.getTrace(traceId)).isEmpty();
    }

    @Test
    void extractsPathFromHttpUrlTagAsFallback() {
        String traceId = "0123456789abcdef0123456789abcdef";
        SpanData span = testSpanBuilder(traceId, "0000000000000001", "GET /actuator/metrics", SpanKind.SERVER)
                .setAttributes(Attributes.of(HTTP_URL_KEY, "http://localhost:8080/actuator/metrics?x=1"))
                .build();

        exporter.export(List.of(span));

        assertThat(publishedEvents).isEmpty();
        assertThat(storage.getTrace(traceId)).isEmpty();
    }

    /**
     * Behind a {@code server.servlet.context-path} the span's path tags carry the prefix;
     * the host's actuator spans and Peekaboot's own must be skipped exactly as at the root.
     * The span names deliberately carry no matchable route, so the path tags alone decide.
     */
    @Test
    void skipsTheSameSpansBehindAContextPath() {
        OtelSpanExporter behindContext = new OtelSpanExporter(eventPublisher, new PeekabootPaths("/actuator", "/app"));
        String traceId = "0123456789abcdef0123456789abcdef";

        behindContext.export(List.of(
                testSpanBuilder(traceId, "0000000000000001", "GET", SpanKind.SERVER)
                        .setAttributes(Attributes.of(URL_PATH_KEY, "/app/actuator/health"))
                        .build(),
                testSpanBuilder(traceId, "0000000000000002", "GET", SpanKind.SERVER)
                        .setAttributes(Attributes.of(HTTP_URL_KEY, "http://localhost:8080/app/peekaboot/api/traces"))
                        .build(),
                testSpanBuilder(traceId, "0000000000000003", "GET", SpanKind.SERVER)
                        .setAttributes(Attributes.of(URL_PATH_KEY, "/app/api/users"))
                        .build()));

        assertThat(publishedEvents).hasSize(1);
        assertThat(storedSpan(traceId).tags()).containsEntry("url.path", "/app/api/users");
    }

    @Test
    void convertsErrorStatusToErrorMessageAndClass() {
        String traceId = "0123456789abcdef0123456789abcdef";
        String spanId = "0000000000000001";
        SpanData span = testSpanBuilder(traceId, spanId, "op", SpanKind.SERVER)
                .setStatus(StatusData.create(StatusCode.ERROR, "boom"))
                .build();

        exporter.export(List.of(span));

        org.peekaboot.backend.tracing.store.SpanData stored = storedSpan(traceId);
        assertThat(stored.errorMessage()).isEqualTo("boom");
        assertThat(stored.errorClass()).isEqualTo("ERROR");
    }

    /**
     * Micrometer's OTel bridge records a thrown exception as an {@code exception} event and
     * sets the status description to its message when it has one; the event is the only
     * carrier of the exception class, and of the message when the throwable had none.
     */
    @Test
    void takesTheErrorClassAndMessageFromTheRecordedExceptionWhenTheStatusHasNoDescription() {
        String traceId = exportThroughTheSdk(span -> {
            span.recordException(new IllegalStateException("pool exhausted"));
            span.setStatus(StatusCode.ERROR);
        });

        org.peekaboot.backend.tracing.store.SpanData stored = storedSpan(traceId);
        assertThat(stored.errorClass()).isEqualTo("java.lang.IllegalStateException");
        assertThat(stored.errorMessage()).isEqualTo("pool exhausted");
    }

    @Test
    void keepsTheStatusDescriptionAsTheMessageAndStillTakesTheClassFromTheRecordedException() {
        String traceId = exportThroughTheSdk(span -> {
            span.recordException(new IllegalStateException("pool exhausted"));
            span.setStatus(StatusCode.ERROR, "described by the app");
        });

        org.peekaboot.backend.tracing.store.SpanData stored = storedSpan(traceId);
        assertThat(stored.errorClass()).isEqualTo("java.lang.IllegalStateException");
        assertThat(stored.errorMessage()).isEqualTo("described by the app");
    }

    /** Runs one span through the real SDK into the exporter; returns its trace id. */
    private String exportThroughTheSdk(java.util.function.Consumer<io.opentelemetry.api.trace.Span> body) {
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            io.opentelemetry.api.trace.Span span =
                    provider.get("test").spanBuilder("op").startSpan();
            body.accept(span);
            span.end();
            return span.getSpanContext().getTraceId();
        }
    }

    @Test
    void extractsParentSpanIdWhenParentContextIsValid() {
        String traceId = "0123456789abcdef0123456789abcdef";
        String parentSpanId = "aaaaaaaaaaaaaaaa";
        SpanContext parentContext =
                SpanContext.create(traceId, parentSpanId, TraceFlags.getSampled(), TraceState.getDefault());
        SpanData span = testSpanBuilder(traceId, "0000000000000001", "op", SpanKind.SERVER)
                .setParentSpanContext(parentContext)
                .build();

        exporter.export(List.of(span));

        org.peekaboot.backend.tracing.store.SpanData stored = storedSpan(traceId);
        assertThat(stored.parentId()).isEqualTo(parentSpanId);
    }

    @Test
    void extractsServiceNameFromResource() {
        String traceId = "0123456789abcdef0123456789abcdef";
        SpanData span = testSpanBuilder(traceId, "0000000000000001", "op", SpanKind.SERVER)
                .setResource(Resource.create(Attributes.of(SERVICE_NAME_KEY, "orders-service")))
                .build();

        exporter.export(List.of(span));

        org.peekaboot.backend.tracing.store.SpanData stored = storedSpan(traceId);
        assertThat(stored.remoteServiceName()).isEqualTo("orders-service");
    }

    @Test
    void extractsAttributesAsTags() {
        String traceId = "0123456789abcdef0123456789abcdef";
        SpanData span = testSpanBuilder(traceId, "0000000000000001", "op", SpanKind.SERVER)
                .setAttributes(Attributes.of(AttributeKey.stringKey("db.system"), "postgresql"))
                .build();

        exporter.export(List.of(span));

        org.peekaboot.backend.tracing.store.SpanData stored = storedSpan(traceId);
        assertThat(stored.tags()).containsEntry("db.system", "postgresql");
    }

    @Test
    void extractsEventsFromSpanData() {
        String traceId = "0123456789abcdef0123456789abcdef";
        // A fixed, realistic epoch-nanos value (not System.nanoTime(), which is
        // an arbitrary monotonic reading unrelated to wall-clock time) so the
        // converted Instant can be asserted precisely, actually exercising
        // OtelSpanExporter's private nanosToInstant() conversion.
        Instant eventInstant = Instant.parse("2024-01-15T10:00:00.500Z");
        long eventNanos = eventInstant.getEpochSecond() * 1_000_000_000L + eventInstant.getNano();
        EventData event = EventData.create(eventNanos, "cache-miss", Attributes.empty());
        SpanData span = testSpanBuilder(traceId, "0000000000000001", "op", SpanKind.SERVER)
                .setEvents(List.of(event))
                .build();

        exporter.export(List.of(span));

        org.peekaboot.backend.tracing.store.SpanData stored = storedSpan(traceId);
        assertThat(stored.events()).hasSize(1);
        assertThat(stored.events().getFirst().name()).isEqualTo("cache-miss");
        assertThat(stored.events().getFirst().timestamp()).isEqualTo(eventInstant);
    }

    /** Micrometer's Span.Kind has no INTERNAL; a null kind is what classifies a root as INTERNAL downstream. */
    @ParameterizedTest
    @CsvSource({"CLIENT, CLIENT", "SERVER, SERVER", "PRODUCER, PRODUCER", "CONSUMER, CONSUMER", "INTERNAL,"})
    void mapsEachSpanKindToItsMicrometerKind(SpanKind otelKind, io.micrometer.tracing.Span.Kind expected) {
        String traceId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1";
        SpanData span =
                testSpanBuilder(traceId, "0000000000000001", "op", otelKind).build();

        exporter.export(List.of(span));

        assertThat(storedSpan(traceId).kind()).isEqualTo(expected);
    }

    /**
     * Looks up the spans stored for {@code traceId}. Named to disambiguate from the imported
     * OTel {@link SpanData}: the store's own {@code SpanData} record must stay fully qualified
     * at every call site regardless, but this centralizes the lookup expression itself.
     */
    private List<org.peekaboot.backend.tracing.store.SpanData> storedSpans(String traceId) {
        return storage.getTrace(traceId).orElseThrow().spans();
    }

    private org.peekaboot.backend.tracing.store.SpanData storedSpan(String traceId) {
        return storedSpans(traceId).getFirst();
    }

    private static SpanData createTestSpan(String traceId, String spanId, String name, SpanKind kind) {
        return testSpanBuilder(traceId, spanId, name, kind).build();
    }

    /** A finished one-second span from {@code test-service}, started 2026-01-01T00:00:00Z; a test sets what it asserts on. */
    private static TestSpanData.Builder testSpanBuilder(String traceId, String spanId, String name, SpanKind kind) {
        return TestSpanData.builder()
                .setSpanContext(SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault()))
                .setName(name)
                .setKind(kind)
                .setStartEpochNanos(START_NANOS)
                .setEndEpochNanos(START_NANOS + 1_000_000_000L)
                .setHasEnded(true)
                .setStatus(StatusData.ok())
                .setResource(Resource.create(Attributes.of(SERVICE_NAME_KEY, "test-service")))
                .setInstrumentationScopeInfo(InstrumentationScopeInfo.create("test"));
    }
}

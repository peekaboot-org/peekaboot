package net.osslabz.peekaboot.backend.tracing.bridge.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import net.osslabz.peekaboot.backend.tracing.event.SpanDataEvent;
import net.osslabz.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OtelSpanExporterTest {

    private static final AttributeKey<String> SERVICE_NAME_KEY = AttributeKey.stringKey("service.name");
    private static final AttributeKey<String> HTTP_URL_KEY = AttributeKey.stringKey("http.url");
    private static final AttributeKey<String> HTTP_TARGET_KEY = AttributeKey.stringKey("http.target");
    private static final AttributeKey<String> URL_PATH_KEY = AttributeKey.stringKey("url.path");

    private InMemoryTraceStore storage;
    private List<SpanDataEvent> publishedEvents;
    private OtelSpanExporter exporter;

    @BeforeEach
    void setUp() {
        storage = new InMemoryTraceStore(100, 50, Duration.ofMinutes(5));
        publishedEvents = new ArrayList<>();
        ApplicationEventPublisher eventPublisher = event -> {
            if (event instanceof SpanDataEvent spanDataEvent) {
                publishedEvents.add(spanDataEvent);
                storage.addSpan(spanDataEvent.spanData());
            }
        };
        exporter = new OtelSpanExporter(storage, eventPublisher);
    }

    @Test
    void shouldConvertAndPublishOtelSpan() {
        String traceId = "0123456789abcdef0123456789abcdef";
        String spanId = "0123456789abcdef";
        SpanData otelSpan = createTestSpan(traceId, spanId, "test-operation", SpanKind.SERVER);

        CompletableResultCode result = exporter.export(List.of(otelSpan));

        assertThat(result.isSuccess()).isTrue();
        assertThat(publishedEvents).hasSize(1);

        List<net.osslabz.peekaboot.backend.tracing.store.SpanData> spans = storage.getTrace(traceId).orElseThrow().spans();
        assertThat(spans).hasSize(1);

        net.osslabz.peekaboot.backend.tracing.store.SpanData stored = spans.getFirst();
        assertThat(stored.name()).isEqualTo("test-operation");
        assertThat(stored.kind()).isEqualTo(io.micrometer.tracing.Span.Kind.SERVER);
        assertThat(stored.traceId()).isEqualTo(traceId);
        assertThat(stored.spanId()).isEqualTo(spanId);
    }

    @Test
    void shouldExportMultipleSpans() {
        String traceId1 = "aaaabbbbccccddddeeeeffffaaaabbbb";
        String traceId2 = "aaaabbbbccccddddeeeeffffaaaabbbc";

        SpanData span1 = createTestSpan(traceId1, "aaaa000000000001", "op1", SpanKind.CLIENT);
        SpanData span2 = createTestSpan(traceId1, "aaaa000000000002", "op2", SpanKind.SERVER);
        SpanData span3 = createTestSpan(traceId2, "aaaa000000000003", "op3", SpanKind.PRODUCER);

        exporter.export(List.of(span1, span2, span3));

        assertThat(publishedEvents).hasSize(3);
        assertThat(storage.getTrace(traceId1).orElseThrow().spans()).hasSize(2);
        assertThat(storage.getTrace(traceId2).orElseThrow().spans()).hasSize(1);
    }

    @Test
    void shouldHandleFlush() {
        CompletableResultCode result = exporter.flush();
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void shouldHandleShutdown() {
        CompletableResultCode result = exporter.shutdown();
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void shouldMapAllSpanKinds() {
        int index = 0;
        for (SpanKind kind : SpanKind.values()) {
            String traceId = String.format("%032x", index++);
            SpanData span = createTestSpan(traceId, "0000000000000001", "op", kind);
            exporter.export(List.of(span));

            List<net.osslabz.peekaboot.backend.tracing.store.SpanData> stored = storage.getTrace(traceId).orElseThrow().spans();
            assertThat(stored).hasSize(1);

            if (kind == SpanKind.INTERNAL) {
                assertThat(stored.getFirst().kind()).isNull();
            } else {
                assertThat(stored.getFirst().kind()).isNotNull();
            }
        }
    }

    @Test
    void shouldSkipSpanWhenPathAttributeMatchesFilterPathMatcher() {
        String traceId = "0123456789abcdef0123456789abcdef";
        SpanData span = testSpanBuilder(traceId, "0000000000000001", "GET /actuator/health", SpanKind.SERVER)
                .attributes(Attributes.of(URL_PATH_KEY, "/actuator/health"))
                .build();

        exporter.export(List.of(span));

        assertThat(publishedEvents).isEmpty();
        assertThat(storage.getTrace(traceId)).isEmpty();
    }

    @Test
    void shouldSkipSpanWhenNameContainsPeekabootPath() {
        String traceId = "0123456789abcdef0123456789abcdef";
        SpanData span = testSpanBuilder(traceId, "0000000000000001", "GET /peekaboot/api/traces", SpanKind.SERVER)
                .build();

        exporter.export(List.of(span));

        assertThat(publishedEvents).isEmpty();
        assertThat(storage.getTrace(traceId)).isEmpty();
    }

    @Test
    void shouldExtractPathFromHttpTargetTagWhenUrlPathAbsent() {
        String traceId = "0123456789abcdef0123456789abcdef";
        SpanData span = testSpanBuilder(traceId, "0000000000000001", "GET /actuator/info", SpanKind.SERVER)
                .attributes(Attributes.of(HTTP_TARGET_KEY, "/actuator/info"))
                .build();

        exporter.export(List.of(span));

        assertThat(publishedEvents).isEmpty();
        assertThat(storage.getTrace(traceId)).isEmpty();
    }

    @Test
    void shouldExtractPathFromHttpUrlTagAsFallback() {
        String traceId = "0123456789abcdef0123456789abcdef";
        SpanData span = testSpanBuilder(traceId, "0000000000000001", "GET /actuator/metrics", SpanKind.SERVER)
                .attributes(Attributes.of(HTTP_URL_KEY, "http://localhost:8080/actuator/metrics?x=1"))
                .build();

        exporter.export(List.of(span));

        assertThat(publishedEvents).isEmpty();
        assertThat(storage.getTrace(traceId)).isEmpty();
    }

    @Test
    void shouldConvertErrorStatusToErrorMessageAndClass() {
        String traceId = "0123456789abcdef0123456789abcdef";
        String spanId = "0000000000000001";
        SpanData span = testSpanBuilder(traceId, spanId, "op", SpanKind.SERVER)
                .status(StatusData.create(StatusCode.ERROR, "boom"))
                .build();

        exporter.export(List.of(span));

        net.osslabz.peekaboot.backend.tracing.store.SpanData stored =
                storage.getTrace(traceId).orElseThrow().spans().getFirst();
        assertThat(stored.errorMessage()).isEqualTo("boom");
        assertThat(stored.errorClass()).isEqualTo("ERROR");
    }

    @Test
    void shouldExtractParentSpanIdWhenParentContextIsValid() {
        String traceId = "0123456789abcdef0123456789abcdef";
        String parentSpanId = "aaaaaaaaaaaaaaaa";
        SpanContext parentContext = SpanContext.create(traceId, parentSpanId, TraceFlags.getSampled(), TraceState.getDefault());
        SpanData span = testSpanBuilder(traceId, "0000000000000001", "op", SpanKind.SERVER)
                .parentSpanContext(parentContext)
                .build();

        exporter.export(List.of(span));

        net.osslabz.peekaboot.backend.tracing.store.SpanData stored =
                storage.getTrace(traceId).orElseThrow().spans().getFirst();
        assertThat(stored.parentId()).isEqualTo(parentSpanId);
    }

    @Test
    void shouldExtractServiceNameFromResource() {
        String traceId = "0123456789abcdef0123456789abcdef";
        SpanData span = testSpanBuilder(traceId, "0000000000000001", "op", SpanKind.SERVER)
                .serviceName("orders-service")
                .build();

        exporter.export(List.of(span));

        net.osslabz.peekaboot.backend.tracing.store.SpanData stored =
                storage.getTrace(traceId).orElseThrow().spans().getFirst();
        assertThat(stored.remoteServiceName()).isEqualTo("orders-service");
    }

    @Test
    void shouldExtractAttributesAsTags() {
        String traceId = "0123456789abcdef0123456789abcdef";
        SpanData span = testSpanBuilder(traceId, "0000000000000001", "op", SpanKind.SERVER)
                .attributes(Attributes.of(AttributeKey.stringKey("db.system"), "postgresql"))
                .build();

        exporter.export(List.of(span));

        net.osslabz.peekaboot.backend.tracing.store.SpanData stored =
                storage.getTrace(traceId).orElseThrow().spans().getFirst();
        assertThat(stored.tags()).containsEntry("db.system", "postgresql");
    }

    @Test
    void shouldExtractEventsFromSpanData() {
        String traceId = "0123456789abcdef0123456789abcdef";
        long eventNanos = System.nanoTime();
        EventData event = EventData.create(eventNanos, "cache-miss", Attributes.empty());
        SpanData span = testSpanBuilder(traceId, "0000000000000001", "op", SpanKind.SERVER)
                .events(List.of(event))
                .build();

        exporter.export(List.of(span));

        net.osslabz.peekaboot.backend.tracing.store.SpanData stored =
                storage.getTrace(traceId).orElseThrow().spans().getFirst();
        assertThat(stored.events()).hasSize(1);
        assertThat(stored.events().getFirst().name()).isEqualTo("cache-miss");
    }

    @Test
    void shouldMapSpanKindsToSpecificMicrometerKind() {
        assertMappedKind(SpanKind.CLIENT, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1", io.micrometer.tracing.Span.Kind.CLIENT);
        assertMappedKind(SpanKind.SERVER, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa2", io.micrometer.tracing.Span.Kind.SERVER);
        assertMappedKind(SpanKind.PRODUCER, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa3", io.micrometer.tracing.Span.Kind.PRODUCER);
        assertMappedKind(SpanKind.CONSUMER, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa4", io.micrometer.tracing.Span.Kind.CONSUMER);
    }

    private void assertMappedKind(SpanKind otelKind, String traceId, io.micrometer.tracing.Span.Kind expected) {
        SpanData span = testSpanBuilder(traceId, "0000000000000001", "op", otelKind).build();

        exporter.export(List.of(span));

        net.osslabz.peekaboot.backend.tracing.store.SpanData stored =
                storage.getTrace(traceId).orElseThrow().spans().getFirst();
        assertThat(stored.kind()).isEqualTo(expected);
    }

    private SpanData createTestSpan(String traceId, String spanId, String name, SpanKind kind) {
        return new TestSpanData(traceId, spanId, name, kind, Attributes.empty(), StatusData.ok(),
                SpanContext.getInvalid(), List.of(), "test-service");
    }

    private TestSpanDataBuilder testSpanBuilder(String traceId, String spanId, String name, SpanKind kind) {
        return new TestSpanDataBuilder(traceId, spanId, name, kind);
    }

    private static class TestSpanDataBuilder {
        private final String traceId;
        private final String spanId;
        private final String name;
        private final SpanKind kind;
        private Attributes attributes = Attributes.empty();
        private StatusData status = StatusData.ok();
        private SpanContext parentSpanContext = SpanContext.getInvalid();
        private List<EventData> events = List.of();
        private String serviceName = "test-service";

        TestSpanDataBuilder(String traceId, String spanId, String name, SpanKind kind) {
            this.traceId = traceId;
            this.spanId = spanId;
            this.name = name;
            this.kind = kind;
        }

        TestSpanDataBuilder attributes(Attributes attributes) {
            this.attributes = attributes;
            return this;
        }

        TestSpanDataBuilder status(StatusData status) {
            this.status = status;
            return this;
        }

        TestSpanDataBuilder parentSpanContext(SpanContext parentSpanContext) {
            this.parentSpanContext = parentSpanContext;
            return this;
        }

        TestSpanDataBuilder events(List<EventData> events) {
            this.events = events;
            return this;
        }

        TestSpanDataBuilder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        SpanData build() {
            return new TestSpanData(traceId, spanId, name, kind, attributes, status, parentSpanContext, events, serviceName);
        }
    }

    @SuppressWarnings("deprecation")
    private static class TestSpanData implements SpanData {
        private final String traceId;
        private final String spanId;
        private final String name;
        private final SpanKind kind;
        private final Attributes attributes;
        private final StatusData status;
        private final SpanContext parentSpanContext;
        private final List<EventData> events;
        private final String serviceName;
        private final long startNanos = System.nanoTime();
        private final long endNanos = startNanos + 1_000_000_000L;

        TestSpanData(String traceId, String spanId, String name, SpanKind kind,
                Attributes attributes, StatusData status, SpanContext parentSpanContext,
                List<EventData> events, String serviceName) {
            this.traceId = traceId;
            this.spanId = spanId;
            this.name = name;
            this.kind = kind;
            this.attributes = attributes;
            this.status = status;
            this.parentSpanContext = parentSpanContext;
            this.events = events;
            this.serviceName = serviceName;
        }

        @Override public SpanContext getSpanContext() {
            return SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault());
        }
        @Override public SpanContext getParentSpanContext() { return parentSpanContext; }
        @Override public Resource getResource() {
            return Resource.create(Attributes.of(SERVICE_NAME_KEY, serviceName));
        }
        @Override public InstrumentationScopeInfo getInstrumentationScopeInfo() {
            return InstrumentationScopeInfo.create("test");
        }
        @Override public InstrumentationLibraryInfo getInstrumentationLibraryInfo() {
            return InstrumentationLibraryInfo.create("test", "1.0.0");
        }
        @Override public String getName() { return name; }
        @Override public SpanKind getKind() { return kind; }
        @Override public long getStartEpochNanos() { return startNanos; }
        @Override public Attributes getAttributes() { return attributes; }
        @Override public List<EventData> getEvents() { return events; }
        @Override public List<io.opentelemetry.sdk.trace.data.LinkData> getLinks() { return List.of(); }
        @Override public StatusData getStatus() { return status; }
        @Override public long getEndEpochNanos() { return endNanos; }
        @Override public boolean hasEnded() { return true; }
        @Override public int getTotalRecordedEvents() { return 0; }
        @Override public int getTotalRecordedLinks() { return 0; }
        @Override public int getTotalAttributeCount() { return 0; }
    }
}

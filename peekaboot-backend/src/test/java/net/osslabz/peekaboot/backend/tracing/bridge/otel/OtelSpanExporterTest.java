package net.osslabz.peekaboot.backend.tracing.bridge.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import net.osslabz.peekaboot.backend.tracing.store.InMemorySpanStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OtelSpanExporterTest {

    private static final AttributeKey<String> SERVICE_NAME_KEY = AttributeKey.stringKey("service.name");

    private InMemorySpanStore store;
    private OtelSpanExporter exporter;

    @BeforeEach
    void setUp() {
        store = new InMemorySpanStore(100, 50);
        exporter = new OtelSpanExporter(store);
    }

    @Test
    void shouldConvertAndStoreOtelSpan() {
        String traceId = "0123456789abcdef0123456789abcdef";
        String spanId = "0123456789abcdef";
        SpanData otelSpan = createTestSpan(traceId, spanId, "test-operation", SpanKind.SERVER);

        CompletableResultCode result = exporter.export(List.of(otelSpan));

        assertThat(result.isSuccess()).isTrue();

        List<net.osslabz.peekaboot.backend.tracing.store.SpanData> spans = store.getSpansForTrace(traceId);
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

        assertThat(store.getSpansForTrace(traceId1)).hasSize(2);
        assertThat(store.getSpansForTrace(traceId2)).hasSize(1);
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

            List<net.osslabz.peekaboot.backend.tracing.store.SpanData> stored = store.getSpansForTrace(traceId);
            assertThat(stored).hasSize(1);

            if (kind == SpanKind.INTERNAL) {
                assertThat(stored.getFirst().kind()).isNull();
            } else {
                assertThat(stored.getFirst().kind()).isNotNull();
            }
        }
    }

    private SpanData createTestSpan(String traceId, String spanId, String name, SpanKind kind) {
        return new TestSpanData(traceId, spanId, name, kind);
    }

    @SuppressWarnings("deprecation")
    private static class TestSpanData implements SpanData {
        private final String traceId;
        private final String spanId;
        private final String name;
        private final SpanKind kind;
        private final long startNanos = System.nanoTime();
        private final long endNanos = startNanos + 1_000_000_000L;

        TestSpanData(String traceId, String spanId, String name, SpanKind kind) {
            this.traceId = traceId;
            this.spanId = spanId;
            this.name = name;
            this.kind = kind;
        }

        @Override public SpanContext getSpanContext() {
            return SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault());
        }
        @Override public SpanContext getParentSpanContext() { return SpanContext.getInvalid(); }
        @Override public Resource getResource() {
            return Resource.create(Attributes.of(SERVICE_NAME_KEY, "test-service"));
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
        @Override public Attributes getAttributes() { return Attributes.empty(); }
        @Override public List<EventData> getEvents() { return List.of(); }
        @Override public List<io.opentelemetry.sdk.trace.data.LinkData> getLinks() { return List.of(); }
        @Override public StatusData getStatus() { return StatusData.ok(); }
        @Override public long getEndEpochNanos() { return endNanos; }
        @Override public boolean hasEnded() { return true; }
        @Override public int getTotalRecordedEvents() { return 0; }
        @Override public int getTotalRecordedLinks() { return 0; }
        @Override public int getTotalAttributeCount() { return 0; }
    }
}

package net.osslabz.peekaboot.tracing;

import io.micrometer.tracing.ScopedSpan;
import io.micrometer.tracing.Span;
import net.osslabz.peekaboot.tracing.context.InMemoryCurrentTraceContext;
import net.osslabz.peekaboot.tracing.store.InMemorySpanStore;
import net.osslabz.peekaboot.tracing.store.SpanData;
import net.osslabz.peekaboot.tracing.store.TraceData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTracerTest {

    private InMemorySpanStore store;
    private InMemoryCurrentTraceContext currentTraceContext;
    private InMemoryTracer tracer;

    @BeforeEach
    void setUp() {
        store = new InMemorySpanStore(100, 50);
        currentTraceContext = new InMemoryCurrentTraceContext();
        tracer = new InMemoryTracer(store, currentTraceContext);
        MDC.clear();
    }

    @Test
    void shouldCreateAndEndSpan() {
        Span span = tracer.nextSpan().name("test-span").start();
        String traceId = span.context().traceId();
        String spanId = span.context().spanId();

        assertThat(traceId).isNotNull().hasSize(32);
        assertThat(spanId).isNotNull().hasSize(16);

        span.end();

        List<SpanData> spans = store.getSpansForTrace(traceId);
        assertThat(spans).hasSize(1);
        assertThat(spans.getFirst().name()).isEqualTo("test-span");
        assertThat(spans.getFirst().isComplete()).isTrue();
    }

    @Test
    void shouldCreateParentChildRelationship() {
        Span parent = tracer.nextSpan().name("parent").start();
        String traceId = parent.context().traceId();

        try (var scope = tracer.withSpan(parent)) {
            Span child = tracer.nextSpan().name("child").start();
            assertThat(child.context().traceId()).isEqualTo(traceId);
            assertThat(child.context().parentId()).isEqualTo(parent.context().spanId());
            child.end();
        }
        parent.end();

        List<SpanData> spans = store.getSpansForTrace(traceId);
        assertThat(spans).hasSize(2);
    }

    @Test
    void shouldSupportScopedSpan() {
        ScopedSpan scoped = tracer.startScopedSpan("scoped-operation");
        String traceId = scoped.context().traceId();

        assertThat(currentTraceContext.context()).isNotNull();
        assertThat(currentTraceContext.context().spanId()).isEqualTo(scoped.context().spanId());

        scoped.tag("key", "value");
        scoped.event("checkpoint");
        scoped.end();

        assertThat(currentTraceContext.context()).isNull();

        List<SpanData> spans = store.getSpansForTrace(traceId);
        assertThat(spans).hasSize(1);
        assertThat(spans.getFirst().tags()).containsEntry("key", "value");
        assertThat(spans.getFirst().events()).hasSize(1);
    }

    @Test
    void shouldSetMdcInScope() {
        Span span = tracer.nextSpan().name("mdc-test").start();

        assertThat(MDC.get("traceId")).isNull();

        try (var scope = tracer.withSpan(span)) {
            assertThat(MDC.get("traceId")).isEqualTo(span.context().traceId());
            assertThat(MDC.get("spanId")).isEqualTo(span.context().spanId());
        }

        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("spanId")).isNull();

        span.end();
    }

    @Test
    void shouldSupportNestedScopes() {
        Span outer = tracer.nextSpan().name("outer").start();
        Span inner = tracer.nextSpan(outer).name("inner").start();

        try (var outerScope = tracer.withSpan(outer)) {
            assertThat(MDC.get("spanId")).isEqualTo(outer.context().spanId());

            try (var innerScope = tracer.withSpan(inner)) {
                assertThat(MDC.get("spanId")).isEqualTo(inner.context().spanId());
            }

            assertThat(MDC.get("spanId")).isEqualTo(outer.context().spanId());
        }

        assertThat(MDC.get("spanId")).isNull();

        inner.end();
        outer.end();
    }

    @Test
    void shouldSupportBaggage() {
        Span span = tracer.nextSpan().name("baggage-test").start();

        try (var scope = tracer.withSpan(span)) {
            try (var baggageScope = tracer.createBaggageInScope("user-id", "12345")) {
                assertThat(baggageScope.get()).isEqualTo("12345");
                assertThat(tracer.getAllBaggage()).containsEntry("user-id", "12345");
            }
        }

        span.end();
    }

    @Test
    void shouldRecordTags() {
        Span span = tracer.nextSpan()
                .name("tagged-span")
                .start();

        span.tag("string-tag", "value")
            .tag("number-tag", 42L)
            .tag("boolean-tag", true);
        span.end();

        List<SpanData> spans = store.getSpansForTrace(span.context().traceId());
        assertThat(spans.getFirst().tags())
                .containsEntry("string-tag", "value")
                .containsEntry("number-tag", "42")
                .containsEntry("boolean-tag", "true");
    }

    @Test
    void shouldRecordError() {
        Span span = tracer.nextSpan().name("error-span").start();
        span.error(new RuntimeException("Test error"));
        span.end();

        List<SpanData> spans = store.getSpansForTrace(span.context().traceId());
        assertThat(spans.getFirst().hasError()).isTrue();
        assertThat(spans.getFirst().errorMessage()).isEqualTo("Test error");
        assertThat(spans.getFirst().errorClass()).isEqualTo("java.lang.RuntimeException");
    }

    @Test
    void shouldQueryRecentTraces() {
        for (int i = 0; i < 5; i++) {
            Span span = tracer.nextSpan().name("trace-" + i).start();
            span.end();
        }

        List<TraceData> traces = store.getRecentTraces(3);
        assertThat(traces).hasSize(3);
    }

    @Test
    void shouldPreserveSpanOrder() {
        Span parent = tracer.nextSpan().name("parent").start();
        String traceId = parent.context().traceId();
        parent.end();

        try (var scope = tracer.withSpan(parent)) {
            for (int i = 0; i < 3; i++) {
                Span child = tracer.nextSpan().name("child-" + i).start();
                child.end();
            }
        }

        List<SpanData> spans = store.getSpansForTrace(traceId);
        assertThat(spans).hasSize(4);
        assertThat(spans.get(0).name()).isEqualTo("parent");
        assertThat(spans.get(1).name()).isEqualTo("child-0");
        assertThat(spans.get(2).name()).isEqualTo("child-1");
        assertThat(spans.get(3).name()).isEqualTo("child-2");
    }
}

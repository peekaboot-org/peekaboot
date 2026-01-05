package net.osslabz.peekaboot.tracing.bridge.brave;

import brave.Span;
import brave.handler.MutableSpan;
import brave.propagation.TraceContext;
import net.osslabz.peekaboot.tracing.store.InMemorySpanStore;
import net.osslabz.peekaboot.tracing.store.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BraveSpanHandlerTest {

    private InMemorySpanStore store;
    private BraveSpanHandler handler;

    @BeforeEach
    void setUp() {
        store = new InMemorySpanStore(100, 50);
        handler = new BraveSpanHandler(store);
    }

    @Test
    void shouldConvertAndStoreBraveSpan() {
        TraceContext context = TraceContext.newBuilder()
                .traceId(1L)
                .spanId(2L)
                .parentId(3L)
                .sampled(true)
                .build();

        MutableSpan span = new MutableSpan();
        span.name("test-operation");
        span.kind(Span.Kind.SERVER);
        span.startTimestamp(System.currentTimeMillis() * 1000);
        span.finishTimestamp(System.currentTimeMillis() * 1000 + 100_000);
        span.tag("http.method", "GET");
        span.tag("http.status_code", "200");
        span.annotate(System.currentTimeMillis() * 1000, "checkpoint");

        boolean result = handler.end(context, span, null);

        assertThat(result).isTrue();

        List<SpanData> spans = store.getSpansForTrace(context.traceIdString());
        assertThat(spans).hasSize(1);

        SpanData stored = spans.getFirst();
        assertThat(stored.name()).isEqualTo("test-operation");
        assertThat(stored.kind()).isEqualTo(io.micrometer.tracing.Span.Kind.SERVER);
        assertThat(stored.traceId()).isEqualTo(context.traceIdString());
        assertThat(stored.spanId()).isEqualTo(context.spanIdString());
        assertThat(stored.parentId()).isEqualTo(context.parentIdString());
        assertThat(stored.tags()).containsEntry("http.method", "GET");
        assertThat(stored.tags()).containsEntry("http.status_code", "200");
        assertThat(stored.events()).hasSize(1);
        assertThat(stored.events().getFirst().name()).isEqualTo("checkpoint");
    }

    @Test
    void shouldHandleSpanWithError() {
        TraceContext context = TraceContext.newBuilder()
                .traceId(10L)
                .spanId(20L)
                .build();

        MutableSpan span = new MutableSpan();
        span.name("error-span");
        span.startTimestamp(System.currentTimeMillis() * 1000);
        span.finishTimestamp(System.currentTimeMillis() * 1000 + 50_000);
        span.error(new RuntimeException("Something went wrong"));

        handler.end(context, span, null);

        List<SpanData> spans = store.getSpansForTrace(context.traceIdString());
        assertThat(spans).hasSize(1);

        SpanData stored = spans.getFirst();
        assertThat(stored.hasError()).isTrue();
        assertThat(stored.errorMessage()).isEqualTo("Something went wrong");
        assertThat(stored.errorClass()).isEqualTo("java.lang.RuntimeException");
    }

    @Test
    void shouldHandleAllSpanKinds() {
        for (Span.Kind braveKind : Span.Kind.values()) {
            TraceContext context = TraceContext.newBuilder()
                    .traceId(100L + braveKind.ordinal())
                    .spanId(200L + braveKind.ordinal())
                    .build();

            MutableSpan span = new MutableSpan();
            span.name("kind-" + braveKind.name());
            span.kind(braveKind);
            span.startTimestamp(System.currentTimeMillis() * 1000);
            span.finishTimestamp(System.currentTimeMillis() * 1000 + 10_000);

            handler.end(context, span, null);

            List<SpanData> spans = store.getSpansForTrace(context.traceIdString());
            assertThat(spans).hasSize(1);
            assertThat(spans.getFirst().kind()).isNotNull();
        }
    }

    @Test
    void shouldHandleRemoteEndpoint() {
        TraceContext context = TraceContext.newBuilder()
                .traceId(500L)
                .spanId(600L)
                .build();

        MutableSpan span = new MutableSpan();
        span.name("client-call");
        span.kind(Span.Kind.CLIENT);
        span.remoteServiceName("payment-service");
        span.remoteIpAndPort("192.168.1.100", 8080);
        span.startTimestamp(System.currentTimeMillis() * 1000);
        span.finishTimestamp(System.currentTimeMillis() * 1000 + 25_000);

        handler.end(context, span, null);

        List<SpanData> spans = store.getSpansForTrace(context.traceIdString());
        SpanData stored = spans.getFirst();
        assertThat(stored.remoteServiceName()).isEqualTo("payment-service");
        assertThat(stored.remoteIp()).isEqualTo("192.168.1.100");
        assertThat(stored.remotePort()).isEqualTo(8080);
    }
}

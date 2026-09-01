package org.peekaboot.testingapp.integration;

import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.ScopedSpan;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.SpanCustomizer;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A {@link Tracer} that reports one fixed, well-formed trace and records nothing.
 *
 * <p>It exists so the toolbar filters have a {@code traceId}/{@code spanId} of the
 * right shape to render, while the {@code TraceStore} stays empty except for what a
 * test puts in it - no span this tracer hands out ever reaches the OpenTelemetry SDK,
 * so the {@code OtelSpanExporter} never publishes anything.
 *
 * <p>Every method other than {@link #currentSpan()} delegates to {@link Tracer#NOOP},
 * which is enough for the real instrumentation on the request path (Spring's
 * observation handlers, datasource-micrometer's {@code DataSourceObservationListener})
 * to build, scope and end spans without ever seeing a {@code null}.
 *
 * <p>Everything here is immutable and free of shared mutable state, so the single
 * tracer bean can be invoked concurrently from any number of request-handling threads.
 * A {@code Mockito.mock(Tracer.class, RETURNS_DEEP_STUBS)} is not: its lazily generated
 * deep stubs are recorded into the mock's invocation container without synchronization,
 * so concurrent JDBC instrumentation can make a later call read back the stub recorded
 * for an earlier, different method, surfacing as {@code ClassCastException:
 * Span$MockitoMock$... cannot be cast to io.micrometer.tracing.TraceContext} out of
 * {@code Span.Builder.start().context()}.
 */
final class DeterministicTracer implements Tracer {

    private final Span currentSpan;

    DeterministicTracer(String traceId, String spanId) {
        this.currentSpan = new FixedSpan(new FixedTraceContext(traceId, spanId, null, Boolean.TRUE));
    }

    @Override
    public Span currentSpan() {
        return currentSpan;
    }

    @Override
    public Span nextSpan() {
        return Tracer.NOOP.nextSpan();
    }

    @Override
    public Span nextSpan(Span parent) {
        return Tracer.NOOP.nextSpan(parent);
    }

    @Override
    public SpanInScope withSpan(Span span) {
        return Tracer.NOOP.withSpan(span);
    }

    @Override
    public ScopedSpan startScopedSpan(String name) {
        return Tracer.NOOP.startScopedSpan(name);
    }

    @Override
    public Span.Builder spanBuilder() {
        return Tracer.NOOP.spanBuilder();
    }

    @Override
    public TraceContext.Builder traceContextBuilder() {
        return Tracer.NOOP.traceContextBuilder();
    }

    @Override
    public CurrentTraceContext currentTraceContext() {
        return Tracer.NOOP.currentTraceContext();
    }

    @Override
    public SpanCustomizer currentSpanCustomizer() {
        return Tracer.NOOP.currentSpanCustomizer();
    }

    @Override
    public Map<String, String> getAllBaggage() {
        return Tracer.NOOP.getAllBaggage();
    }

    @Override
    public Baggage getBaggage(String name) {
        return Tracer.NOOP.getBaggage(name);
    }

    @Override
    public Baggage getBaggage(TraceContext traceContext, String name) {
        return Tracer.NOOP.getBaggage(traceContext, name);
    }

    @Override
    public Baggage createBaggage(String name) {
        return Tracer.NOOP.createBaggage(name);
    }

    @Override
    public Baggage createBaggage(String name, String value) {
        return Tracer.NOOP.createBaggage(name, value);
    }

    private record FixedTraceContext(String traceId, String spanId, String parentId, Boolean sampled)
            implements TraceContext {}

    /**
     * Reports the fixed context; every recording method is a no-op, mirroring
     * {@link Span#NOOP}.
     */
    private record FixedSpan(TraceContext context) implements Span {

        @Override
        public boolean isNoop() {
            return true;
        }

        @Override
        public Span start() {
            return this;
        }

        @Override
        public Span name(String name) {
            return this;
        }

        @Override
        public Span event(String value) {
            return this;
        }

        @Override
        public Span event(String value, long time, TimeUnit timeUnit) {
            return this;
        }

        @Override
        public Span tag(String key, String value) {
            return this;
        }

        @Override
        public Span error(Throwable throwable) {
            return this;
        }

        @Override
        public void end() {}

        @Override
        public void end(long time, TimeUnit timeUnit) {}

        @Override
        public void abandon() {}

        @Override
        public Span remoteServiceName(String remoteServiceName) {
            return this;
        }

        @Override
        public Span remoteIpAndPort(String ip, int port) {
            return this;
        }
    }
}

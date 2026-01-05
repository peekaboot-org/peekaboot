package net.osslabz.peekaboot.tracing;

import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.ScopedSpan;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.SpanCustomizer;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import net.osslabz.peekaboot.tracing.baggage.InMemoryBaggage;
import net.osslabz.peekaboot.tracing.context.InMemoryCurrentTraceContext;
import net.osslabz.peekaboot.tracing.context.InMemoryTraceContext;
import net.osslabz.peekaboot.tracing.context.InMemoryTraceContextBuilder;
import net.osslabz.peekaboot.tracing.span.InMemorySpan;
import net.osslabz.peekaboot.tracing.span.InMemorySpanBuilder;
import net.osslabz.peekaboot.tracing.span.InMemoryScopedSpan;
import net.osslabz.peekaboot.tracing.span.InMemorySpanCustomizer;
import net.osslabz.peekaboot.tracing.store.InMemorySpanStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryTracer implements Tracer {

    private final InMemorySpanStore store;
    private final InMemoryCurrentTraceContext currentTraceContext;
    private final ConcurrentHashMap<String, InMemoryBaggage> baggageRegistry = new ConcurrentHashMap<>();

    public InMemoryTracer(InMemorySpanStore store, InMemoryCurrentTraceContext currentTraceContext) {
        this.store = store;
        this.currentTraceContext = currentTraceContext;
    }

    @Override
    public Span nextSpan() {
        return spanBuilder().start();
    }

    @Override
    public Span nextSpan(Span parent) {
        if (parent == null) {
            return spanBuilder().setNoParent().start();
        }
        return spanBuilder().setParent(parent.context()).start();
    }

    @Override
    public SpanInScope withSpan(Span span) {
        if (span == null) {
            return new SpanInScopeImpl(currentTraceContext.newScope(null), null);
        }
        CurrentTraceContext.Scope scope = currentTraceContext.newScope(span.context());
        return new SpanInScopeImpl(scope, span);
    }

    @Override
    public ScopedSpan startScopedSpan(String name) {
        Span span = spanBuilder().name(name).start();
        CurrentTraceContext.Scope scope = currentTraceContext.newScope(span.context());
        return new InMemoryScopedSpan((InMemorySpan) span, scope);
    }

    @Override
    public Span.Builder spanBuilder() {
        return new InMemorySpanBuilder(store, currentTraceContext);
    }

    @Override
    public TraceContext.Builder traceContextBuilder() {
        return new InMemoryTraceContextBuilder();
    }

    @Override
    public CurrentTraceContext currentTraceContext() {
        return currentTraceContext;
    }

    @Override
    public SpanCustomizer currentSpanCustomizer() {
        Span current = currentSpan();
        if (current == null || current.isNoop()) {
            return NoopSpanCustomizer.INSTANCE;
        }
        return new InMemorySpanCustomizer((InMemorySpan) current);
    }

    @Override
    public Span currentSpan() {
        TraceContext context = currentTraceContext.context();
        if (context == null) {
            return null;
        }
        InMemorySpan span = new InMemorySpan(
                (InMemoryTraceContext) context,
                store,
                store.nextCreationOrder()
        );
        span.setNoop(true);
        return span;
    }

    @Override
    public Map<String, String> getAllBaggage() {
        TraceContext context = currentTraceContext.context();
        if (context == null) {
            return Map.of();
        }
        ConcurrentHashMap<String, String> result = new ConcurrentHashMap<>();
        baggageRegistry.forEach((name, baggage) -> {
            String value = baggage.get(context);
            if (value != null) {
                result.put(name, value);
            }
        });
        return result;
    }

    @Override
    public Baggage getBaggage(String name) {
        return baggageRegistry.computeIfAbsent(name, k -> new InMemoryBaggage(k, currentTraceContext));
    }

    @Override
    public Baggage getBaggage(TraceContext traceContext, String name) {
        return getBaggage(name);
    }

    @Override
    public Baggage createBaggage(String name) {
        return baggageRegistry.computeIfAbsent(name, k -> new InMemoryBaggage(k, currentTraceContext));
    }

    @Override
    public Baggage createBaggage(String name, String value) {
        InMemoryBaggage baggage = baggageRegistry.computeIfAbsent(name, k -> new InMemoryBaggage(k, currentTraceContext));
        baggage.set(value);
        return baggage;
    }

    @Override
    public BaggageInScope createBaggageInScope(String name, String value) {
        return createBaggage(name, value).makeCurrent();
    }

    @Override
    public BaggageInScope createBaggageInScope(TraceContext traceContext, String name, String value) {
        InMemoryBaggage baggage = baggageRegistry.computeIfAbsent(name, k -> new InMemoryBaggage(k, currentTraceContext));
        return baggage.makeCurrent(traceContext, value);
    }

    private static class SpanInScopeImpl implements SpanInScope {
        private final CurrentTraceContext.Scope scope;
        private final Span span;

        SpanInScopeImpl(CurrentTraceContext.Scope scope, Span span) {
            this.scope = scope;
            this.span = span;
        }

        @Override
        public void close() {
            scope.close();
        }
    }

    private static class NoopSpanCustomizer implements SpanCustomizer {
        static final NoopSpanCustomizer INSTANCE = new NoopSpanCustomizer();

        @Override
        public SpanCustomizer name(String name) {
            return this;
        }

        @Override
        public SpanCustomizer tag(String key, String value) {
            return this;
        }

        @Override
        public SpanCustomizer event(String value) {
            return this;
        }
    }
}

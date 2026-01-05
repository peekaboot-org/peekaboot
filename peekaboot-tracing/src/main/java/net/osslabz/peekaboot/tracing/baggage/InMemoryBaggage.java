package net.osslabz.peekaboot.tracing.baggage;

import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.TraceContext;
import net.osslabz.peekaboot.tracing.context.InMemoryCurrentTraceContext;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryBaggage implements Baggage {

    private final String name;
    private final ConcurrentMap<String, String> contextValues = new ConcurrentHashMap<>();
    private final InMemoryCurrentTraceContext currentTraceContext;

    public InMemoryBaggage(String name, InMemoryCurrentTraceContext currentTraceContext) {
        this.name = name;
        this.currentTraceContext = currentTraceContext;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String get() {
        TraceContext context = currentTraceContext.context();
        if (context == null) {
            return null;
        }
        return contextValues.get(context.traceId());
    }

    @Override
    public String get(TraceContext traceContext) {
        if (traceContext == null) {
            return null;
        }
        return contextValues.get(traceContext.traceId());
    }

    @Override
    @SuppressWarnings("deprecation")
    public Baggage set(String value) {
        TraceContext context = currentTraceContext.context();
        if (context != null) {
            if (value != null) {
                contextValues.put(context.traceId(), value);
            } else {
                contextValues.remove(context.traceId());
            }
        }
        return this;
    }

    @Override
    @SuppressWarnings("deprecation")
    public Baggage set(TraceContext traceContext, String value) {
        if (traceContext != null) {
            if (value != null) {
                contextValues.put(traceContext.traceId(), value);
            } else {
                contextValues.remove(traceContext.traceId());
            }
        }
        return this;
    }

    @Override
    public BaggageInScope makeCurrent() {
        return new InMemoryBaggageInScope(this, currentTraceContext.context());
    }

    @Override
    public BaggageInScope makeCurrent(String value) {
        set(value);
        return new InMemoryBaggageInScope(this, currentTraceContext.context());
    }

    @Override
    public BaggageInScope makeCurrent(TraceContext traceContext, String value) {
        set(traceContext, value);
        return new InMemoryBaggageInScope(this, traceContext);
    }

    void removeValue(TraceContext context) {
        if (context != null) {
            contextValues.remove(context.traceId());
        }
    }
}

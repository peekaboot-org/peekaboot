package net.osslabz.peekaboot.tracing.baggage;

import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.TraceContext;

import java.util.concurrent.atomic.AtomicBoolean;

public class InMemoryBaggageInScope implements BaggageInScope {

    private final InMemoryBaggage baggage;
    private final TraceContext context;
    private final String previousValue;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    InMemoryBaggageInScope(InMemoryBaggage baggage, TraceContext context) {
        this.baggage = baggage;
        this.context = context;
        this.previousValue = baggage.get(context);
    }

    @Override
    public String name() {
        return baggage.name();
    }

    @Override
    public String get() {
        return baggage.get(context);
    }

    @Override
    public String get(TraceContext traceContext) {
        return baggage.get(traceContext);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (previousValue != null) {
            baggage.set(context, previousValue);
        } else {
            baggage.removeValue(context);
        }
    }
}

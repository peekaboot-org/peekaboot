package net.osslabz.peekaboot.tracing.context;

import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.TraceContext;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicBoolean;

public class InMemoryScope implements CurrentTraceContext.Scope {

    private static final String MDC_TRACE_ID = "traceId";
    private static final String MDC_SPAN_ID = "spanId";

    private final InMemoryCurrentTraceContext owner;
    private final TraceContext previous;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    InMemoryScope(InMemoryCurrentTraceContext owner, TraceContext previous) {
        this.owner = owner;
        this.previous = previous;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        owner.restore(previous);
        updateMdc(previous);
    }

    static void updateMdc(TraceContext context) {
        if (context != null) {
            MDC.put(MDC_TRACE_ID, context.traceId());
            MDC.put(MDC_SPAN_ID, context.spanId());
        } else {
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_SPAN_ID);
        }
    }
}

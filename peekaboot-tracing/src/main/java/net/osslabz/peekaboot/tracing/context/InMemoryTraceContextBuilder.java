package net.osslabz.peekaboot.tracing.context;

import io.micrometer.tracing.TraceContext;

public class InMemoryTraceContextBuilder implements TraceContext.Builder {

    private String traceId;
    private String parentId;
    private String spanId;
    private Boolean sampled = true;

    @Override
    public TraceContext.Builder traceId(String traceId) {
        this.traceId = traceId;
        return this;
    }

    @Override
    public TraceContext.Builder parentId(String parentId) {
        this.parentId = parentId;
        return this;
    }

    @Override
    public TraceContext.Builder spanId(String spanId) {
        this.spanId = spanId;
        return this;
    }

    @Override
    public TraceContext.Builder sampled(Boolean sampled) {
        this.sampled = sampled;
        return this;
    }

    @Override
    public TraceContext build() {
        if (traceId == null) {
            traceId = InMemoryTraceContext.generateTraceId();
        }
        if (spanId == null) {
            spanId = InMemoryTraceContext.generateSpanId();
        }
        return new InMemoryTraceContext(traceId, parentId, spanId, sampled);
    }
}

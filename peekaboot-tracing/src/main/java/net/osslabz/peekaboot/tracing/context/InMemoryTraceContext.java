package net.osslabz.peekaboot.tracing.context;

import io.micrometer.tracing.TraceContext;

import java.util.Objects;
import java.util.UUID;

public record InMemoryTraceContext(
        String traceId,
        String parentId,
        String spanId,
        Boolean sampled
) implements TraceContext {

    public InMemoryTraceContext {
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(spanId, "spanId must not be null");
    }

    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @Override
    public String traceId() {
        return traceId;
    }

    @Override
    public String parentId() {
        return parentId;
    }

    @Override
    public String spanId() {
        return spanId;
    }

    @Override
    public Boolean sampled() {
        return sampled;
    }
}

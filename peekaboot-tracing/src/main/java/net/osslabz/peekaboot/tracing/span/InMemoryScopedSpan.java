package net.osslabz.peekaboot.tracing.span;

import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.ScopedSpan;
import io.micrometer.tracing.TraceContext;

public class InMemoryScopedSpan implements ScopedSpan {

    private final InMemorySpan span;
    private final CurrentTraceContext.Scope scope;

    public InMemoryScopedSpan(InMemorySpan span, CurrentTraceContext.Scope scope) {
        this.span = span;
        this.scope = scope;
    }

    @Override
    public boolean isNoop() {
        return span.isNoop();
    }

    @Override
    public TraceContext context() {
        return span.context();
    }

    @Override
    public ScopedSpan name(String name) {
        span.name(name);
        return this;
    }

    @Override
    public ScopedSpan tag(String key, String value) {
        span.tag(key, value);
        return this;
    }

    @Override
    public ScopedSpan event(String value) {
        span.event(value);
        return this;
    }

    @Override
    public ScopedSpan error(Throwable throwable) {
        span.error(throwable);
        return this;
    }

    @Override
    public void end() {
        try {
            span.end();
        } finally {
            scope.close();
        }
    }
}

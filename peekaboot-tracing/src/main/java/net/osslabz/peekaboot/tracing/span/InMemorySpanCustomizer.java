package net.osslabz.peekaboot.tracing.span;

import io.micrometer.tracing.SpanCustomizer;

public class InMemorySpanCustomizer implements SpanCustomizer {

    private final InMemorySpan span;

    public InMemorySpanCustomizer(InMemorySpan span) {
        this.span = span;
    }

    @Override
    public SpanCustomizer name(String name) {
        span.name(name);
        return this;
    }

    @Override
    public SpanCustomizer tag(String key, String value) {
        span.tag(key, value);
        return this;
    }

    @Override
    public SpanCustomizer event(String value) {
        span.event(value);
        return this;
    }
}

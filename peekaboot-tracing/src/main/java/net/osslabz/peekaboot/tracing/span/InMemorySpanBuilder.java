package net.osslabz.peekaboot.tracing.span;

import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import net.osslabz.peekaboot.tracing.context.InMemoryCurrentTraceContext;
import net.osslabz.peekaboot.tracing.context.InMemoryTraceContext;
import net.osslabz.peekaboot.tracing.store.InMemorySpanStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class InMemorySpanBuilder implements Span.Builder {

    private final InMemorySpanStore store;
    private final InMemoryCurrentTraceContext currentTraceContext;

    private TraceContext parentContext;
    private boolean noParent;
    private String name;
    private Span.Kind kind;
    private Instant startTime;
    private final Map<String, String> tags = new LinkedHashMap<>();
    private final List<Link> links = new ArrayList<>();
    private Throwable error;
    private String remoteServiceName;
    private String remoteIp;
    private int remotePort;

    public InMemorySpanBuilder(InMemorySpanStore store, InMemoryCurrentTraceContext currentTraceContext) {
        this.store = store;
        this.currentTraceContext = currentTraceContext;
    }

    @Override
    public Span.Builder setParent(TraceContext context) {
        this.parentContext = context;
        return this;
    }

    @Override
    public Span.Builder setNoParent() {
        this.noParent = true;
        return this;
    }

    @Override
    public Span.Builder name(String name) {
        this.name = name;
        return this;
    }

    @Override
    public Span.Builder event(String value) {
        return this;
    }

    @Override
    public Span.Builder tag(String key, String value) {
        if (key != null && value != null) {
            tags.put(key, value);
        }
        return this;
    }

    @Override
    public Span.Builder error(Throwable throwable) {
        this.error = throwable;
        return this;
    }

    @Override
    public Span.Builder kind(Span.Kind spanKind) {
        this.kind = spanKind;
        return this;
    }

    @Override
    public Span.Builder remoteServiceName(String remoteServiceName) {
        this.remoteServiceName = remoteServiceName;
        return this;
    }

    @Override
    public Span.Builder remoteIpAndPort(String ip, int port) {
        this.remoteIp = ip;
        this.remotePort = port;
        return this;
    }

    @Override
    public Span.Builder startTimestamp(long startTimestamp, TimeUnit unit) {
        long micros = unit.toMicros(startTimestamp);
        this.startTime = Instant.EPOCH.plusNanos(micros * 1000);
        return this;
    }

    @Override
    public Span.Builder addLink(Link link) {
        if (link != null) {
            links.add(link);
        }
        return this;
    }

    @Override
    public Span start() {
        TraceContext parent = resolveParent();

        String traceId;
        String parentId;

        if (parent != null) {
            traceId = parent.traceId();
            parentId = parent.spanId();
        } else {
            traceId = InMemoryTraceContext.generateTraceId();
            parentId = null;
        }

        String spanId = InMemoryTraceContext.generateSpanId();
        InMemoryTraceContext context = new InMemoryTraceContext(traceId, parentId, spanId, true);

        InMemorySpan span = new InMemorySpan(context, store, store.nextCreationOrder());
        span.name(name);
        span.setKind(kind);

        if (startTime != null) {
            span.setStartTime(startTime);
        }

        tags.forEach(span::tag);
        links.forEach(span::addLink);

        if (error != null) {
            span.error(error);
        }

        if (remoteServiceName != null) {
            span.remoteServiceName(remoteServiceName);
        }

        if (remoteIp != null) {
            span.remoteIpAndPort(remoteIp, remotePort);
        }

        return span.start();
    }

    private TraceContext resolveParent() {
        if (noParent) {
            return null;
        }
        if (parentContext != null) {
            return parentContext;
        }
        return currentTraceContext.context();
    }
}

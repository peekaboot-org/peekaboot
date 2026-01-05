package net.osslabz.peekaboot.tracing.span;

import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import net.osslabz.peekaboot.tracing.context.InMemoryTraceContext;
import net.osslabz.peekaboot.tracing.store.InMemorySpanStore;
import net.osslabz.peekaboot.tracing.store.SpanData;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class InMemorySpan implements Span {

    private final InMemoryTraceContext context;
    private final InMemorySpanStore store;
    private final long creationOrder;

    private volatile String name;
    private volatile Kind kind;
    private volatile Instant startTime;
    private volatile Instant endTime;
    private final Map<String, String> tags = new ConcurrentHashMap<>();
    private final List<SpanData.Event> events = new CopyOnWriteArrayList<>();
    private final List<SpanData.LinkData> links = new CopyOnWriteArrayList<>();
    private volatile Throwable error;
    private volatile String remoteServiceName;
    private volatile String remoteIp;
    private volatile int remotePort;
    private volatile boolean started;
    private volatile boolean ended;
    private volatile boolean noop;

    public InMemorySpan(InMemoryTraceContext context, InMemorySpanStore store, long creationOrder) {
        this.context = context;
        this.store = store;
        this.creationOrder = creationOrder;
    }

    @Override
    public boolean isNoop() {
        return noop;
    }

    public void setNoop(boolean noop) {
        this.noop = noop;
    }

    @Override
    public TraceContext context() {
        return context;
    }

    @Override
    public Span start() {
        if (!started) {
            started = true;
            if (startTime == null) {
                startTime = Instant.now();
            }
        }
        return this;
    }

    @Override
    public Span name(String name) {
        this.name = name;
        return this;
    }

    @Override
    public Span event(String value) {
        events.add(new SpanData.Event(value, Instant.now()));
        return this;
    }

    @Override
    public Span event(String value, long time, TimeUnit timeUnit) {
        long micros = timeUnit.toMicros(time);
        Instant timestamp = Instant.EPOCH.plusNanos(micros * 1000);
        events.add(new SpanData.Event(value, timestamp));
        return this;
    }

    @Override
    public Span tag(String key, String value) {
        if (key != null && value != null) {
            tags.put(key, value);
        }
        return this;
    }

    @Override
    public Span error(Throwable throwable) {
        this.error = throwable;
        return this;
    }

    @Override
    public void end() {
        if (!ended) {
            ended = true;
            endTime = Instant.now();
            reportToStore();
        }
    }

    @Override
    public void end(long time, TimeUnit timeUnit) {
        if (!ended) {
            ended = true;
            long micros = timeUnit.toMicros(time);
            endTime = Instant.EPOCH.plusNanos(micros * 1000);
            reportToStore();
        }
    }

    @Override
    public void abandon() {
        ended = true;
    }

    @Override
    public Span remoteServiceName(String remoteServiceName) {
        this.remoteServiceName = remoteServiceName;
        return this;
    }

    @Override
    public Span remoteIpAndPort(String ip, int port) {
        this.remoteIp = ip;
        this.remotePort = port;
        return this;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public void addLink(Link link) {
        if (link != null && link.getTraceContext() != null) {
            TraceContext tc = link.getTraceContext();
            links.add(new SpanData.LinkData(tc.traceId(), tc.spanId(), Map.of()));
        }
    }

    private void reportToStore() {
        if (noop || store == null) {
            return;
        }

        SpanData spanData = new SpanData(
                context.traceId(),
                context.spanId(),
                context.parentId(),
                name,
                kind,
                startTime,
                endTime,
                (startTime != null && endTime != null)
                    ? java.time.Duration.between(startTime, endTime)
                    : null,
                Map.copyOf(tags),
                List.copyOf(events),
                error != null ? error.getMessage() : null,
                error != null ? error.getClass().getName() : null,
                remoteServiceName,
                remoteIp,
                remotePort > 0 ? remotePort : null,
                List.copyOf(links),
                creationOrder
        );
        store.report(spanData);
    }
}

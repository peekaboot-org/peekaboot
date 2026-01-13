package net.osslabz.peekaboot.backend.tracing.store;

import net.osslabz.peekaboot.backend.tracing.event.LogCapturedEvent;
import net.osslabz.peekaboot.backend.tracing.event.RequestCompletedEvent;
import net.osslabz.peekaboot.backend.tracing.event.SpanCompletedEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TraceDataBundle {

    private final String traceId;
    private final List<SpanCompletedEvent> spans = Collections.synchronizedList(new ArrayList<>());
    private final List<LogCapturedEvent> logs = Collections.synchronizedList(new ArrayList<>());
    private volatile RequestCompletedEvent request;
    private final long createdAt;

    public TraceDataBundle(String traceId) {
        this.traceId = traceId;
        this.createdAt = System.currentTimeMillis();
    }

    public String traceId() {
        return traceId;
    }

    public long createdAt() {
        return createdAt;
    }

    public void addSpan(SpanCompletedEvent span) {
        spans.add(span);
    }

    public void addLog(LogCapturedEvent log) {
        logs.add(log);
    }

    public void setRequest(RequestCompletedEvent request) {
        this.request = request;
    }

    public List<SpanCompletedEvent> spans() {
        return new ArrayList<>(spans);
    }

    public List<LogCapturedEvent> logs() {
        return new ArrayList<>(logs);
    }

    public RequestCompletedEvent request() {
        return request;
    }
}

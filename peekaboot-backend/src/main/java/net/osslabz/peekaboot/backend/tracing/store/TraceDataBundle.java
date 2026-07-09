package net.osslabz.peekaboot.backend.tracing.store;

import net.osslabz.peekaboot.backend.tracing.event.LogCapturedEvent;
import net.osslabz.peekaboot.backend.tracing.event.RequestCompletedEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Bundle of all data collected for a single trace: spans, logs, and request info.
 */
public class TraceDataBundle {

    private final String traceId;
    private final List<SpanData> spans = Collections.synchronizedList(new ArrayList<>());
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

    public void addSpan(SpanData span, int maxSpans) {
        spans.add(span);
        if (spans.size() > maxSpans) {
            synchronized (spans) {
                if (spans.size() > maxSpans) {
                    spans.subList(0, spans.size() - maxSpans).clear();
                }
            }
        }
    }

    public void addLog(LogCapturedEvent log) {
        logs.add(log);
    }

    public void setRequest(RequestCompletedEvent request) {
        this.request = request;
    }

    public List<SpanData> spans() {
        // copy under the list's mutex (via toArray) before sorting; streaming
        // the synchronizedList directly races with concurrent addSpan calls
        return new ArrayList<>(spans).stream()
                .sorted(Comparator.comparingLong(SpanData::creationOrder))
                .toList();
    }

    public List<LogCapturedEvent> logs() {
        return new ArrayList<>(logs);
    }

    public RequestCompletedEvent request() {
        return request;
    }
}

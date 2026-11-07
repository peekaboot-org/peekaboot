package org.peekaboot.autoconfigure;

import io.micrometer.tracing.Span;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.event.CapturedExceptionInfo;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.store.TraceStore;

/**
 * The spans and captured logs the auto-configuration tests put in a {@link TraceStore}, all of
 * trace {@code t1} apart from the untraced log. The backend's own span fixture is out of reach: it
 * publishes no test jar.
 */
final class TraceFixtures {

    static final String TRACE_ID = "t1";

    private static final String ROOT_SPAN_ID = "s1";

    private static final AtomicLong NEXT_CREATION_ORDER = new AtomicLong();

    private TraceFixtures() {}

    /** The SERVER root a captured span or log hangs off. */
    static void addRootSpan(TraceStore traceStore) {
        traceStore.addSpan(span(ROOT_SPAN_ID, null, "GET /x", Span.Kind.SERVER, 0, 100, Map.of()));
    }

    /** A JDBC query below the root span, running {@code statement}. */
    static SpanData query(String statement) {
        return query("q1", statement);
    }

    /** A JDBC query below the root span with its own {@code spanId}, running {@code statement}. */
    static SpanData query(String spanId, String statement) {
        return span(spanId, ROOT_SPAN_ID, "SELECT", Span.Kind.CLIENT, 10, 10, Map.of("db.query.text", statement));
    }

    /** A root span of no particular kind, lasting {@code durationMs}. */
    static SpanData rootSpanLasting(long durationMs) {
        return span(ROOT_SPAN_ID, null, "op", null, 0, durationMs, Map.of());
    }

    /** A SERVER root that answered 500 after {@code com.acme.Shop.buy} threw. */
    static SpanData failedRootSpan() {
        Instant start = Instant.EPOCH;
        return new SpanData(
                TRACE_ID,
                ROOT_SPAN_ID,
                null,
                false,
                "GET /x",
                Span.Kind.SERVER,
                start,
                start.plusMillis(10),
                Map.of("http.route", "/x", "http.response.status_code", "500"),
                List.of(),
                new CapturedExceptionInfo(
                        "java.lang.IllegalStateException",
                        "boom",
                        "java.lang.IllegalStateException: boom\n\tat com.acme.Shop.buy(Shop.java:7)"),
                NEXT_CREATION_ORDER.incrementAndGet());
    }

    /** An ERROR log on the root span whose one frame sits in {@code com.acme.vendor}. */
    static LogCapturedEvent vendorFrameLog() {
        return new LogCapturedEvent(
                TRACE_ID,
                ROOT_SPAN_ID,
                Instant.EPOCH,
                "ERROR",
                "com.acme.vendor.Client",
                "main",
                "boom",
                "java.lang.IllegalStateException: boom\n\tat com.acme.vendor.Client.call(Client.java:42)\n");
    }

    /** A line written outside any trace, as the appender publishes one written at startup. */
    static LogCapturedEvent untracedLog(String message) {
        return new LogCapturedEvent(null, null, Instant.EPOCH, "INFO", "com.acme.Startup", "main", message, null);
    }

    private static SpanData span(
            String spanId,
            @Nullable String parentId,
            String name,
            Span.@Nullable Kind kind,
            long startMs,
            long durationMs,
            Map<String, String> tags) {
        Instant start = Instant.EPOCH.plusMillis(startMs);
        return new SpanData(
                TRACE_ID,
                spanId,
                parentId,
                false,
                name,
                kind,
                start,
                start.plusMillis(durationMs),
                tags,
                List.of(),
                null,
                NEXT_CREATION_ORDER.incrementAndGet());
    }
}

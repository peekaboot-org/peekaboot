package org.peekaboot.backend.testsupport;

import static org.peekaboot.backend.testsupport.Spans.span;

import io.micrometer.tracing.Span;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.peekaboot.backend.mapper.trace.SqlFormatter;
import org.peekaboot.backend.mapper.trace.TraceResources;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.digest.ErrorDigest;
import org.peekaboot.backend.tracing.digest.ErrorDigestRecorder;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.store.TraceStore;

/**
 * A request to {@code GET /orders/{id}} whose handler threw from {@code org.acme.Shop.buy}, the
 * application's own package here, and the recorder that files such a trace in an {@link ErrorDigest}.
 */
public final class FailedTraces {

    public static final String ROUTE = "/orders/{id}";
    public static final String TYPE = "java.lang.IllegalStateException";

    private FailedTraces() {}

    /** The digest recorder as the auto-configuration builds it, with {@code org.acme} the application's package. */
    public static ErrorDigestRecorder recorder(TraceStore store, ErrorDigest digest) {
        return new ErrorDigestRecorder(
                store,
                digest,
                new TraceResources(
                        new MaskingEngine(),
                        SqlFormatter.NONE,
                        new PeekabootTracingProperties(),
                        new StackTraceRules(List.of(), List.of("org.acme"))));
    }

    /** The handler span that threw {@code message} from line {@code line} of {@code Shop.java}, below {@link #root}. */
    public static SpanData handler(String traceId, Instant start, String message, int line) {
        String stackTrace = TYPE + ": " + message + "\n\tat org.acme.Shop.buy(Shop.java:" + line + ")"
                + "\n\tat org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:1)";
        return span("handler")
                .in(traceId)
                .parent("root")
                .at(start, Duration.ofMillis(5))
                .exception(TYPE, message, stackTrace)
                .build();
    }

    /** The SERVER span the request ends with, answering {@code status}. */
    public static SpanData root(String traceId, Instant start, int status) {
        return span("root")
                .in(traceId)
                .named("GET " + ROUTE)
                .kind(Span.Kind.SERVER)
                .at(start, Duration.ofMillis(10))
                .tag("http.request.method", "GET")
                .tag("http.route", ROUTE)
                .tag("url.path", "/orders/1")
                .tag("http.response.status_code", String.valueOf(status))
                .build();
    }
}

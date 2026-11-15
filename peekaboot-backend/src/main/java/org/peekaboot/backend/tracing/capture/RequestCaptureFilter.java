package org.peekaboot.backend.tracing.capture;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.masking.MaskingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Filter that captures HTTP request/response details and publishes them as a {@code RequestCompletedEvent}.
 * This enables the Request tab in the trace detail view. It also sets the Server-Timing header
 * the toolbar's idle mode reads a trace id from. It never asks the container for parameters
 * the application did not read.
 */
public class RequestCaptureFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RequestCaptureFilter.class);
    private static final String CAPTURE_FAILED = "Failed to capture request details";

    private final RequestDetails requestDetails;

    private final Tracer tracer;
    private final ApplicationEventPublisher eventPublisher;
    private final PeekabootPaths paths;

    public RequestCaptureFilter(
            Tracer tracer,
            ApplicationEventPublisher eventPublisher,
            MaskingEngine maskingEngine,
            PeekabootPaths paths) {
        this.tracer = tracer;
        this.eventPublisher = eventPublisher;
        this.requestDetails = new RequestDetails(maskingEngine);
        this.paths = paths;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        if (paths.isExcluded(PeekabootPaths.pathWithinApplication(httpRequest))) {
            chain.doFilter(request, response);
            return;
        }

        // Resolved here, on the request thread, where the server span is current; an async
        // completion callback runs on a container thread that has no span.
        Span currentSpan = tracer.currentSpan();
        setServerTimingHeader(httpResponse, currentSpan);

        String traceId = currentSpan == null ? null : currentSpan.context().traceId();
        if (traceId == null) {
            log.trace("No current trace, skipping request capture");
            chain.doFilter(request, response);
            return;
        }

        filterAndCapture(new ParameterReads(httpRequest), httpResponse, chain, traceId);
    }

    /** The chain runs on the wrapper, so the capture knows afterwards whether parameters were read. */
    private void filterAndCapture(
            ParameterReads request, HttpServletResponse response, FilterChain chain, String traceId)
            throws IOException, ServletException {
        int statusOnFailure = 0;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException failure) {
            // Nothing has set 500 yet: the container sets it outside every filter, and so
            // does Spring's ServerHttpObservationFilter, one filter further out - unless the
            // response is already committed, in which case that setStatus(500) is silently
            // ignored and the client, and the server span, keep whatever status was already
            // flushed. Reading it back here instead of assuming 500 keeps this exchange on
            // the same number.
            statusOnFailure =
                    response.isCommitted() ? response.getStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            throw failure;
        } finally {
            captureNowOrOnCompletion(request, response, traceId, statusOnFailure);
        }
    }

    /** Runs in a finally block: an exception from here would replace the handler's. */
    private void captureNowOrOnCompletion(
            ParameterReads request, HttpServletResponse response, String traceId, int statusOnFailure) {
        try {
            if (request.isAsyncStarted()) {
                // a DeferredResult/Callable/SseEmitter handler has only handed off: its status
                // is not known until the async cycle completes
                request.getAsyncContext().addListener(new CaptureOnCompletion(request, response, traceId));
            } else {
                int status = statusOnFailure != 0 ? statusOnFailure : response.getStatus();
                captureRequest(request, response, traceId, status);
            }
        } catch (RuntimeException e) {
            log.warn(CAPTURE_FAILED, e);
        }
    }

    private void capture(ParameterReads request, HttpServletResponse response, String traceId, int status) {
        try {
            captureRequest(request, response, traceId, status);
        } catch (Exception e) {
            log.warn(CAPTURE_FAILED, e);
        }
    }

    /** Captures once the async cycle has run its course, whichever way it ended. */
    private final class CaptureOnCompletion implements AsyncListener {

        private final ParameterReads request;
        private final HttpServletResponse response;
        private final String traceId;

        private CaptureOnCompletion(ParameterReads request, HttpServletResponse response, String traceId) {
            this.request = request;
            this.response = response;
            this.traceId = traceId;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            capture(request, response, traceId, response.getStatus());
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            // the container completes the cycle afterwards; onComplete captures then
        }

        @Override
        public void onError(AsyncEvent event) {
            // the container completes the cycle afterwards; onComplete captures then
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            // listeners do not carry over into a restarted cycle unless they re-register
            event.getAsyncContext().addListener(this);
        }
    }

    private static void setServerTimingHeader(HttpServletResponse response, Span currentSpan) {
        if (currentSpan == null) {
            return;
        }
        TraceContext context = currentSpan.context();
        String traceId = context.traceId();
        String spanId = context.spanId();
        if (traceId == null || spanId == null) {
            return;
        }
        String traceFlags = Boolean.TRUE.equals(context.sampled()) ? "01" : "00";
        response.setHeader("Server-Timing", "trace;desc=\"00-" + traceId + "-" + spanId + "-" + traceFlags + "\"");
    }

    private void captureRequest(ParameterReads request, HttpServletResponse response, String traceId, int status) {
        eventPublisher.publishEvent(requestDetails.event(request, response, traceId, status));
        log.trace("Published RequestCompletedEvent for trace {}", traceId);
    }
}

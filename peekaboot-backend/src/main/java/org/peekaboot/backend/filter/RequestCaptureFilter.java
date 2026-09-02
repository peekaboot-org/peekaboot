package org.peekaboot.backend.filter;

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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Filter that captures HTTP request/response details and publishes them via Spring events.
 * This enables the Request tab in the trace detail view.
 */
public class RequestCaptureFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RequestCaptureFilter.class);

    private final MaskingEngine maskingEngine;

    private final Tracer tracer;
    private final ApplicationEventPublisher eventPublisher;
    private final PeekabootPaths paths;
    /** Epoch millis; the duration is the difference between two reads around the chain. */
    private final LongSupplier clock;

    public RequestCaptureFilter(
            Tracer tracer,
            ApplicationEventPublisher eventPublisher,
            MaskingEngine maskingEngine,
            PeekabootPaths paths) {
        this(tracer, eventPublisher, maskingEngine, paths, System::currentTimeMillis);
    }

    RequestCaptureFilter(
            Tracer tracer,
            ApplicationEventPublisher eventPublisher,
            MaskingEngine maskingEngine,
            PeekabootPaths paths,
            LongSupplier clock) {
        this.tracer = tracer;
        this.eventPublisher = eventPublisher;
        this.maskingEngine = maskingEngine;
        this.paths = paths;
        this.clock = clock;
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

        setServerTimingHeader(httpResponse);

        long startTime = clock.getAsLong();

        try {
            chain.doFilter(request, response);
        } finally {
            // Resolved here, on the request thread, where the server span is current; an
            // async completion callback runs on a container thread that has no span.
            String traceId = currentTraceId();
            if (traceId == null) {
                log.trace("No current trace, skipping request capture");
            } else if (httpRequest.isAsyncStarted()) {
                // a DeferredResult/Callable/SseEmitter handler has only handed off: its status
                // and duration are not known until the async cycle completes
                httpRequest
                        .getAsyncContext()
                        .addListener(new CaptureOnCompletion(httpRequest, httpResponse, traceId, startTime));
            } else {
                capture(httpRequest, httpResponse, traceId, startTime);
            }
        }
    }

    private String currentTraceId() {
        Span currentSpan = tracer.currentSpan();
        return currentSpan == null ? null : currentSpan.context().traceId();
    }

    private void capture(HttpServletRequest request, HttpServletResponse response, String traceId, long startTime) {
        try {
            captureRequest(request, response, traceId, startTime);
        } catch (Exception e) {
            log.warn("Failed to capture request details", e);
        }
    }

    /** Captures once the async cycle has run its course, whichever way it ended. */
    private final class CaptureOnCompletion implements AsyncListener {

        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final String traceId;
        private final long startTime;

        private CaptureOnCompletion(
                HttpServletRequest request, HttpServletResponse response, String traceId, long startTime) {
            this.request = request;
            this.response = response;
            this.traceId = traceId;
            this.startTime = startTime;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            capture(request, response, traceId, startTime);
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

    private void setServerTimingHeader(HttpServletResponse response) {
        Span currentSpan = tracer.currentSpan();
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

    private void captureRequest(
            HttpServletRequest request, HttpServletResponse response, String traceId, long startTime) {
        long durationMs = clock.getAsLong() - startTime;

        Map<String, String> requestHeaders = maskedRequestHeaders(request);
        Map<String, String> responseHeaders = maskedResponseHeaders(response);

        Map<String, List<String>> queryParams = new HashMap<>();
        Map<String, List<String>> formParams = new HashMap<>();
        splitParameters(request, queryParams, formParams);

        String controllerClass = null;
        String controllerMethod = null;
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (handler instanceof HandlerMethod handlerMethod) {
            controllerClass = handlerMethod.getBeanType().getName();
            controllerMethod = handlerMethod.getMethod().getName();
        }

        RequestCompletedEvent event = new RequestCompletedEvent(
                traceId,
                request.getMethod(),
                request.getRequestURI(),
                maskingEngine.maskQueryString(request.getQueryString()),
                requestHeaders,
                null, // request bodies are not captured
                false, // requestBodyTruncated
                controllerClass,
                controllerMethod,
                queryParams,
                formParams,
                List.of(), // uploads are not captured
                response.getStatus(),
                responseHeaders,
                durationMs);

        eventPublisher.publishEvent(event);
        log.trace("Published RequestCompletedEvent for trace {}", traceId);
    }

    private Map<String, String> maskedRequestHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Collections.list(request.getHeaderNames())
                .forEach(name -> headers.put(
                        name, maskingEngine.mask(name, String.join(", ", Collections.list(request.getHeaders(name))))));
        return headers;
    }

    /** A header sent more than once (Set-Cookie, typically) is captured as one comma-joined value. */
    private Map<String, String> maskedResponseHeaders(HttpServletResponse response) {
        Map<String, String> headers = new HashMap<>();
        response.getHeaderNames()
                .forEach(name ->
                        headers.put(name, maskingEngine.mask(name, String.join(", ", response.getHeaders(name)))));
        return headers;
    }

    /**
     * getParameterMap() merges query-string and body parameters (form-encoded and
     * multipart alike); a parameter the query string does not carry came from the body.
     */
    private void splitParameters(
            HttpServletRequest request, Map<String, List<String>> queryParams, Map<String, List<String>> formParams) {
        Set<String> queryStringKeys = parseQueryStringKeys(request.getQueryString());
        request.getParameterMap().forEach((key, values) -> {
            if (values == null || values.length == 0) {
                return;
            }
            List<String> maskedValues =
                    Arrays.stream(values).map(v -> maskingEngine.mask(key, v)).toList();
            if (queryStringKeys.contains(key)) {
                queryParams.put(key, maskedValues);
            } else {
                formParams.put(key, maskedValues);
            }
        });
    }

    private Set<String> parseQueryStringKeys(String queryString) {
        if (queryString == null || queryString.isBlank()) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        for (String pair : queryString.split("&", -1)) {
            int equalsIndex = pair.indexOf('=');
            String key = equalsIndex >= 0 ? pair.substring(0, equalsIndex) : pair;
            if (!key.isBlank()) {
                keys.add(URLDecoder.decode(key, StandardCharsets.UTF_8));
            }
        }
        return keys;
    }
}

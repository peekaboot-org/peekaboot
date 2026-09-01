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
import java.net.URLEncoder;
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

    private final MaskingEngine maskingEngine = new MaskingEngine();

    private final Tracer tracer;
    private final ApplicationEventPublisher eventPublisher;
    /** Epoch millis; the duration is the difference between two reads around the chain. */
    private final LongSupplier clock;

    public RequestCaptureFilter(Tracer tracer, ApplicationEventPublisher eventPublisher) {
        this(tracer, eventPublisher, System::currentTimeMillis);
    }

    RequestCaptureFilter(Tracer tracer, ApplicationEventPublisher eventPublisher, LongSupplier clock) {
        this.tracer = tracer;
        this.eventPublisher = eventPublisher;
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

        if (PeekabootPaths.isExcluded(PeekabootPaths.pathWithinApplication(httpRequest))) {
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
                // Request
                request.getMethod(),
                request.getRequestURI(),
                maskQueryString(request.getQueryString()),
                requestHeaders,
                null, // requestBody - not captured yet
                false, // requestBodyTruncated
                controllerClass,
                controllerMethod,
                queryParams,
                formParams,
                List.of(), // uploadedFiles - not captured yet
                // Response
                response.getStatus(),
                responseHeaders,
                // Timing
                durationMs);

        eventPublisher.publishEvent(event);
        log.trace("Published RequestCompletedEvent for trace {}", traceId);
    }

    private Map<String, String> maskedRequestHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Collections.list(request.getHeaderNames())
                .forEach(name -> headers.put(name, maskingEngine.mask(name, request.getHeader(name))));
        return headers;
    }

    private Map<String, String> maskedResponseHeaders(HttpServletResponse response) {
        Map<String, String> headers = new HashMap<>();
        response.getHeaderNames()
                .forEach(name -> headers.put(name, maskingEngine.mask(name, response.getHeader(name))));
        return headers;
    }

    /**
     * getParameterMap() merges query-string and form-body parameters; splits them
     * using the actual query string.
     */
    private void splitParameters(
            HttpServletRequest request, Map<String, List<String>> queryParams, Map<String, List<String>> formParams) {
        Set<String> queryStringKeys = parseQueryStringKeys(request.getQueryString());
        boolean formRequest = isFormRequest(request);
        request.getParameterMap().forEach((key, values) -> {
            if (values == null || values.length == 0) {
                return;
            }
            List<String> maskedValues =
                    Arrays.stream(values).map(v -> maskingEngine.mask(key, v)).toList();
            if (queryStringKeys.contains(key) || !formRequest) {
                queryParams.put(key, maskedValues);
            } else {
                formParams.put(key, maskedValues);
            }
        });
    }

    private static boolean isFormRequest(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null
                && contentType.contains("application/x-www-form-urlencoded")
                && ("POST".equalsIgnoreCase(request.getMethod()) || "PUT".equalsIgnoreCase(request.getMethod()));
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

    /**
     * Masks the raw query string per parameter rather than treating it as one opaque
     * string - a whole-string regex could not tell a sensitive value from the rest of
     * the string without false positives/negatives. Each pair is decoded, masked via the
     * same {@link MaskingEngine#mask(String, String)} rule as everywhere else, and
     * re-encoded; a bare flag with no "=" (e.g. "?debug") is passed through unchanged
     * since it carries no value to mask.
     */
    private String maskQueryString(String queryString) {
        if (queryString == null || queryString.isBlank()) {
            return queryString;
        }
        String[] pairs = queryString.split("&", -1);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                result.append('&');
            }
            String pair = pairs[i];
            int equalsIndex = pair.indexOf('=');
            if (equalsIndex < 0) {
                result.append(pair);
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, equalsIndex), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(equalsIndex + 1), StandardCharsets.UTF_8);
            String maskedValue = maskingEngine.mask(key, value);
            result.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(maskedValue, StandardCharsets.UTF_8));
        }
        return result.toString();
    }
}

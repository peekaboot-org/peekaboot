package org.peekaboot.backend.filter;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
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

    public RequestCaptureFilter(Tracer tracer, ApplicationEventPublisher eventPublisher) {
        this.tracer = tracer;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        String uri = httpRequest.getRequestURI();

        if (shouldSkip(uri)) {
            chain.doFilter(request, response);
            return;
        }

        setServerTimingHeader(httpResponse);

        long startTime = System.currentTimeMillis();

        try {
            chain.doFilter(request, response);
        } finally {
            try {
                captureRequest(httpRequest, httpResponse, startTime);
            } catch (Exception e) {
                log.warn("Failed to capture request details", e);
            }
        }
    }

    private boolean shouldSkip(String path) {
        return FilterPathMatcher.shouldSkip(path);
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

    private void captureRequest(HttpServletRequest request, HttpServletResponse response, long startTime) {
        Span currentSpan = tracer.currentSpan();
        if (currentSpan == null) {
            log.trace("No current span, skipping request capture");
            return;
        }

        String traceId = currentSpan.context().traceId();
        if (traceId == null) {
            log.trace("No traceId in current span, skipping request capture");
            return;
        }

        long durationMs = System.currentTimeMillis() - startTime;

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

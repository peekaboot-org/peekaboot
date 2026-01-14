package net.osslabz.peekaboot.backend.filter;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.osslabz.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Filter that captures HTTP request/response details and publishes them via Spring events.
 * This enables the Request tab in the trace detail view.
 */
public class RequestCaptureFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RequestCaptureFilter.class);

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization",
            "cookie",
            "set-cookie",
            "x-auth-token",
            "x-api-key"
    );

    private final Tracer tracer;
    private final ApplicationEventPublisher eventPublisher;

    public RequestCaptureFilter(Tracer tracer, ApplicationEventPublisher eventPublisher) {
        this.tracer = tracer;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest) ||
            !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        String uri = httpRequest.getRequestURI();

        if (shouldSkip(uri)) {
            chain.doFilter(request, response);
            return;
        }

        long startTime = System.currentTimeMillis();

        try {
            chain.doFilter(request, response);
        } finally {
            try {
                captureRequest(httpRequest, httpResponse, startTime);
            } catch (Exception e) {
                log.warn("Failed to capture request details: {}", e.getMessage());
            }
        }
    }

    private boolean shouldSkip(String path) {
        return FilterPathMatcher.shouldSkip(path);
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

        Map<String, String> requestHeaders = new HashMap<>();
        Collections.list(request.getHeaderNames()).forEach(name -> {
            String value = isSensitiveHeader(name) ? "********" : request.getHeader(name);
            requestHeaders.put(name, value);
        });

        Map<String, String> responseHeaders = new HashMap<>();
        response.getHeaderNames().forEach(name -> {
            String value = isSensitiveHeader(name) ? "********" : response.getHeader(name);
            responseHeaders.put(name, value);
        });

        Map<String, String> queryParams = new HashMap<>();
        request.getParameterMap().forEach((key, values) -> {
            if (values != null && values.length > 0) {
                queryParams.put(key, values[0]);
            }
        });

        String controllerClass = null;
        String controllerMethod = null;
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (handler instanceof HandlerMethod handlerMethod) {
            controllerClass = handlerMethod.getBeanType().getSimpleName();
            controllerMethod = handlerMethod.getMethod().getName();
        }

        RequestCompletedEvent event = new RequestCompletedEvent(
                traceId,
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                durationMs,
                requestHeaders,
                responseHeaders,
                queryParams,
                controllerClass,
                controllerMethod,
                null,
                false
        );

        eventPublisher.publishEvent(event);
        log.trace("Published RequestCompletedEvent for trace {}", traceId);
    }

    private boolean isSensitiveHeader(String headerName) {
        return SENSITIVE_HEADERS.contains(headerName.toLowerCase());
    }
}

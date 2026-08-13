package net.osslabz.peekaboot.backend.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.osslabz.peekaboot.backend.testsupport.LogCapture;
import net.osslabz.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RequestCaptureFilterTest {

    @Mock
    Tracer tracer;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @Mock
    HttpServletRequest request;

    @Mock
    HttpServletResponse response;

    @Mock
    FilterChain chain;

    RequestCaptureFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestCaptureFilter(tracer, eventPublisher);
    }

    private void setupTraceContext(String traceId) {
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(context.traceId()).thenReturn(traceId);
        when(span.context()).thenReturn(context);
        when(tracer.currentSpan()).thenReturn(span);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/static/app.js",
            "/webjars/jquery.js",
            "/actuator/health",
            "/peekaboot/api/traces",
            "/error"
    })
    void shouldSkipExcludedPaths(String path) throws Exception {
        when(request.getRequestURI()).thenReturn(path);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldNotPublishEventWithoutTraceId() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/users");
        when(tracer.currentSpan()).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldPublishEventWithTraceId() throws Exception {
        setupTraceContext("abc123");
        setupBasicRequestResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        RequestCompletedEvent event = captor.getValue();
        assertThat(event.traceId()).isEqualTo("abc123");
        assertThat(event.method()).isEqualTo("GET");
        assertThat(event.path()).isEqualTo("/api/users");
        assertThat(event.status()).isEqualTo(200);
    }

    @Test
    void shouldCaptureRequestHeaders() throws Exception {
        setupTraceContext("trace1");
        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getMethod()).thenReturn("POST");
        when(response.getStatus()).thenReturn(201);

        Enumeration<String> headerNames = Collections.enumeration(java.util.List.of("content-type", "accept"));
        when(request.getHeaderNames()).thenReturn(headerNames);
        when(request.getHeader("content-type")).thenReturn("application/json");
        when(request.getHeader("accept")).thenReturn("application/json");

        when(response.getHeaderNames()).thenReturn(Collections.emptyList());
        when(request.getParameterMap()).thenReturn(Map.of());

        filter.doFilter(request, response, chain);

        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        RequestCompletedEvent event = captor.getValue();
        assertThat(event.requestHeaders()).containsEntry("content-type", "application/json");
        assertThat(event.requestHeaders()).containsEntry("accept", "application/json");
    }

    @Test
    void shouldMaskSensitiveHeaders() throws Exception {
        setupTraceContext("trace1");
        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(200);

        Enumeration<String> headerNames = Collections.enumeration(java.util.List.of(
                "authorization", "cookie", "x-api-key", "content-type"
        ));
        when(request.getHeaderNames()).thenReturn(headerNames);
        when(request.getHeader("authorization")).thenReturn("Bearer secret-token");
        when(request.getHeader("cookie")).thenReturn("session=xyz");
        when(request.getHeader("x-api-key")).thenReturn("api-key-123");
        when(request.getHeader("content-type")).thenReturn("application/json");

        when(response.getHeaderNames()).thenReturn(Collections.emptyList());
        when(request.getParameterMap()).thenReturn(Map.of());

        filter.doFilter(request, response, chain);

        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        RequestCompletedEvent event = captor.getValue();
        assertThat(event.requestHeaders()).containsEntry("authorization", "********");
        assertThat(event.requestHeaders()).containsEntry("cookie", "********");
        assertThat(event.requestHeaders()).containsEntry("x-api-key", "********");
        assertThat(event.requestHeaders()).containsEntry("content-type", "application/json");
    }

    @Test
    void shouldCaptureQueryParameters() throws Exception {
        setupTraceContext("trace1");
        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(200);
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(response.getHeaderNames()).thenReturn(Collections.emptyList());

        Map<String, String[]> params = new HashMap<>();
        params.put("page", new String[]{"1"});
        params.put("size", new String[]{"10"});
        when(request.getParameterMap()).thenReturn(params);

        filter.doFilter(request, response, chain);

        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        RequestCompletedEvent event = captor.getValue();
        assertThat(event.queryParams()).containsEntry("page", List.of("1"));
        assertThat(event.queryParams()).containsEntry("size", List.of("10"));
    }

    @Test
    void shouldSeparateQueryAndFormParameters() throws Exception {
        // getParameterMap() merges query-string and form-body parameters;
        // only actual query-string keys belong in queryParams and only
        // body keys in formParams
        setupTraceContext("trace1");
        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
        when(request.getQueryString()).thenReturn("page=1");
        when(response.getStatus()).thenReturn(201);
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(response.getHeaderNames()).thenReturn(Collections.emptyList());

        Map<String, String[]> params = new HashMap<>();
        params.put("page", new String[]{"1"});
        params.put("firstName", new String[]{"Bob"});
        when(request.getParameterMap()).thenReturn(params);

        filter.doFilter(request, response, chain);

        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        RequestCompletedEvent event = captor.getValue();
        assertThat(event.queryParams()).containsOnlyKeys("page");
        assertThat(event.formParams()).containsOnlyKeys("firstName");
        assertThat(event.formParams()).containsEntry("firstName", List.of("Bob"));
    }

    @Test
    void shouldCaptureControllerInfo() throws Exception {
        setupTraceContext("trace1");
        setupBasicRequestResponse();

        HandlerMethod handlerMethod = mock(HandlerMethod.class);
        when(handlerMethod.getBeanType()).thenReturn((Class) TestController.class);
        when(handlerMethod.getMethod()).thenReturn(TestController.class.getMethod("getUsers"));
        when(request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE)).thenReturn(handlerMethod);

        filter.doFilter(request, response, chain);

        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        RequestCompletedEvent event = captor.getValue();
        assertThat(event.controllerClass()).endsWith("TestController");
        assertThat(event.controllerMethod()).isEqualTo("getUsers");
    }

    @Test
    void shouldCaptureResponseHeaders() throws Exception {
        setupTraceContext("trace1");
        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(200);
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(request.getParameterMap()).thenReturn(Map.of());

        when(response.getHeaderNames()).thenReturn(java.util.List.of("content-type", "x-request-id"));
        when(response.getHeader("content-type")).thenReturn("application/json");
        when(response.getHeader("x-request-id")).thenReturn("req-123");

        filter.doFilter(request, response, chain);

        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        RequestCompletedEvent event = captor.getValue();
        assertThat(event.responseHeaders()).containsEntry("content-type", "application/json");
        assertThat(event.responseHeaders()).containsEntry("x-request-id", "req-123");
    }

    @Test
    void shouldMaskSensitiveResponseHeaders() throws Exception {
        setupTraceContext("trace1");
        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(200);
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(request.getParameterMap()).thenReturn(Map.of());

        when(response.getHeaderNames()).thenReturn(java.util.List.of("set-cookie", "content-type"));
        when(response.getHeader("set-cookie")).thenReturn("session=abc123");
        when(response.getHeader("content-type")).thenReturn("application/json");

        filter.doFilter(request, response, chain);

        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        RequestCompletedEvent event = captor.getValue();
        assertThat(event.responseHeaders()).containsEntry("set-cookie", "********");
        assertThat(event.responseHeaders()).containsEntry("content-type", "application/json");
    }

    @Test
    void shouldStillExecuteFilterChainEvenWhenNoTraceId() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/users");
        when(tracer.currentSpan()).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldCalculateDuration() throws Exception {
        setupTraceContext("trace1");
        setupBasicRequestResponse();

        filter.doFilter(request, response, chain);

        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        RequestCompletedEvent event = captor.getValue();
        assertThat(event.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldSetServerTimingHeader() throws Exception {
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(context.traceId()).thenReturn("0af7651916cd43dd8448eb211c80319c");
        when(context.spanId()).thenReturn("b7ad6b7169203331");
        when(context.sampled()).thenReturn(true);
        when(span.context()).thenReturn(context);
        when(tracer.currentSpan()).thenReturn(span);
        setupBasicRequestResponse();

        filter.doFilter(request, response, chain);

        verify(response).setHeader("Server-Timing", "trace;desc=\"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01\"");
    }

    @Test
    void shouldSetServerTimingHeaderWithUnsampledFlag() throws Exception {
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(context.traceId()).thenReturn("abc123");
        when(context.spanId()).thenReturn("def456");
        when(context.sampled()).thenReturn(false);
        when(span.context()).thenReturn(context);
        when(tracer.currentSpan()).thenReturn(span);
        setupBasicRequestResponse();

        filter.doFilter(request, response, chain);

        verify(response).setHeader("Server-Timing", "trace;desc=\"00-abc123-def456-00\"");
    }

    @Test
    void shouldNotSetServerTimingHeaderWhenNoSpan() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/users");
        when(tracer.currentSpan()).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(response, never()).setHeader(eq("Server-Timing"), any());
    }

    @Test
    void shouldLogWarningAndNotPublishEventWhenCaptureFails() throws Exception {
        setupTraceContext("trace1");
        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(200);
        when(request.getHeaderNames()).thenThrow(new RuntimeException("boom"));

        ListAppender<ILoggingEvent> appender = LogCapture.attach(RequestCaptureFilter.class);
        try {
            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
            verify(eventPublisher, never()).publishEvent(any());
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo("Failed to capture request details: boom");
            });
        } finally {
            LogCapture.detach(RequestCaptureFilter.class, appender);
        }
    }

    private void setupBasicRequestResponse() {
        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(200);
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(response.getHeaderNames()).thenReturn(Collections.emptyList());
        when(request.getParameterMap()).thenReturn(Map.of());
    }

    public static class TestController {
        public void getUsers() {
        }
    }
}

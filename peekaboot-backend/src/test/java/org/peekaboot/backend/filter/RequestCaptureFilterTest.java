package org.peekaboot.backend.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.peekaboot.backend.testsupport.LogCapture;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

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
    @ValueSource(
            strings = {"/static/app.js", "/webjars/jquery.js", "/actuator/health", "/peekaboot/api/traces", "/error"})
    void shouldSkipExcludedPaths(String path) throws Exception {
        stubPath(path);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldNotPublishEventWithoutTraceId() throws Exception {
        stubPath("/api/users");
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
        stubPath("/api/users");
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
        stubPath("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(200);

        Enumeration<String> headerNames =
                Collections.enumeration(java.util.List.of("authorization", "cookie", "x-api-key", "content-type"));
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
        assertThat(event.requestHeaders()).containsEntry("authorization", "******");
        assertThat(event.requestHeaders()).containsEntry("cookie", "******");
        assertThat(event.requestHeaders()).containsEntry("x-api-key", "******");
        assertThat(event.requestHeaders()).containsEntry("content-type", "application/json");
    }

    /**
     * Header masking goes through the engine's key-name rules rather than a fixed list,
     * so Proxy-Authorization - which carries a credential just as Authorization does - is
     * masked without being named anywhere.
     */
    @Test
    void shouldMaskProxyAuthorizationHeader() throws Exception {
        setupTraceContext("trace1");
        stubPath("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(200);

        Enumeration<String> headerNames = Collections.enumeration(java.util.List.of("proxy-authorization"));
        when(request.getHeaderNames()).thenReturn(headerNames);
        when(request.getHeader("proxy-authorization")).thenReturn("Basic dXNlcjpwYXNz");

        when(response.getHeaderNames()).thenReturn(Collections.emptyList());
        when(request.getParameterMap()).thenReturn(Map.of());

        filter.doFilter(request, response, chain);

        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        assertThat(captor.getValue().requestHeaders()).containsEntry("proxy-authorization", "******");
    }

    @Test
    void shouldCaptureQueryParameters() throws Exception {
        setupTraceContext("trace1");
        stubPath("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(200);
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(response.getHeaderNames()).thenReturn(Collections.emptyList());

        Map<String, String[]> params = new HashMap<>();
        params.put("page", new String[] {"1"});
        params.put("size", new String[] {"10"});
        when(request.getParameterMap()).thenReturn(params);

        filter.doFilter(request, response, chain);

        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        RequestCompletedEvent event = captor.getValue();
        assertThat(event.queryParams()).containsEntry("page", List.of("1"));
        assertThat(event.queryParams()).containsEntry("size", List.of("10"));
    }

    @Test
    void shouldMaskSensitiveQueryParameterValues() throws Exception {
        setupTraceContext("trace1");
        stubPath("/search");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(200);
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(response.getHeaderNames()).thenReturn(Collections.emptyList());
        when(request.getQueryString()).thenReturn("api_key=xyz&q=widgets");

        Map<String, String[]> params = new HashMap<>();
        params.put("api_key", new String[] {"xyz"});
        params.put("q", new String[] {"widgets"});
        when(request.getParameterMap()).thenReturn(params);

        filter.doFilter(request, response, chain);

        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        RequestCompletedEvent event = captor.getValue();
        assertThat(event.queryParams()).containsEntry("api_key", List.of("******"));
        assertThat(event.queryParams()).containsEntry("q", List.of("widgets"));
    }

    @Test
    void shouldMaskTheRawQueryStringPerParameter() throws Exception {
        setupTraceContext("trace1");
        stubPath("/search");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(200);
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(response.getHeaderNames()).thenReturn(Collections.emptyList());
        when(request.getQueryString()).thenReturn("api_key=xyz&q=widgets");

        Map<String, String[]> params = new HashMap<>();
        params.put("api_key", new String[] {"xyz"});
        params.put("q", new String[] {"widgets"});
        when(request.getParameterMap()).thenReturn(params);

        filter.doFilter(request, response, chain);

        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        assertThat(captor.getValue().queryString()).isEqualTo("api_key=******&q=widgets");
    }

    @Test
    void shouldMaskSensitiveFormParameterValues() throws Exception {
        // getParameterMap() merges query-string and form-body parameters; only actual
        // query-string keys belong in queryParams and only body keys in formParams -
        // "password" here has no query-string counterpart, so it lands in formParams.
        setupTraceContext("trace1");
        stubPath("/login");
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
        when(request.getQueryString()).thenReturn(null);
        when(response.getStatus()).thenReturn(200);
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(response.getHeaderNames()).thenReturn(Collections.emptyList());

        Map<String, String[]> params = new HashMap<>();
        params.put("username", new String[] {"alice"});
        params.put("password", new String[] {"hunter2"});
        when(request.getParameterMap()).thenReturn(params);

        filter.doFilter(request, response, chain);

        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        RequestCompletedEvent event = captor.getValue();
        assertThat(event.formParams()).containsEntry("username", List.of("alice"));
        assertThat(event.formParams()).containsEntry("password", List.of("******"));
    }

    @Test
    void shouldPreserveAQueryStringPairWithNoValue() throws Exception {
        setupTraceContext("trace1");
        stubPath("/search");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(200);
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(response.getHeaderNames()).thenReturn(Collections.emptyList());
        when(request.getQueryString()).thenReturn("debug&q=widgets");

        Map<String, String[]> params = new HashMap<>();
        params.put("debug", new String[] {""});
        params.put("q", new String[] {"widgets"});
        when(request.getParameterMap()).thenReturn(params);

        filter.doFilter(request, response, chain);

        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        assertThat(captor.getValue().queryString()).isEqualTo("debug&q=widgets");
    }

    @Test
    void shouldReturnNullQueryStringWhenThereIsNone() throws Exception {
        setupTraceContext("trace1");
        setupBasicRequestResponse();

        filter.doFilter(request, response, chain);

        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        assertThat(captor.getValue().queryString()).isNull();
    }

    @Test
    void shouldSeparateQueryAndFormParameters() throws Exception {
        // getParameterMap() merges query-string and form-body parameters;
        // only actual query-string keys belong in queryParams and only
        // body keys in formParams
        setupTraceContext("trace1");
        stubPath("/api/users");
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
        when(request.getQueryString()).thenReturn("page=1");
        when(response.getStatus()).thenReturn(201);
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(response.getHeaderNames()).thenReturn(Collections.emptyList());

        Map<String, String[]> params = new HashMap<>();
        params.put("page", new String[] {"1"});
        params.put("firstName", new String[] {"Bob"});
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
        when(request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE))
                .thenReturn(handlerMethod);

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
        stubPath("/api/users");
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
        stubPath("/api/users");
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
        assertThat(event.responseHeaders()).containsEntry("set-cookie", "******");
        assertThat(event.responseHeaders()).containsEntry("content-type", "application/json");
    }

    @Test
    void shouldStillExecuteFilterChainEvenWhenNoTraceId() throws Exception {
        stubPath("/api/users");
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

        verify(response)
                .setHeader("Server-Timing", "trace;desc=\"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01\"");
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
        stubPath("/api/users");
        when(tracer.currentSpan()).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(response, never()).setHeader(eq("Server-Timing"), any());
    }

    @Test
    void shouldLogWarningAndNotPublishEventWhenCaptureFails() throws Exception {
        setupTraceContext("trace1");
        stubPath("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(200);
        when(request.getHeaderNames()).thenThrow(new RuntimeException("boom"));

        try (LogCapture capture = LogCapture.attach(RequestCaptureFilter.class)) {
            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
            verify(eventPublisher, never()).publishEvent(any());
            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo("Failed to capture request details");
                assertThat(event.getThrowableProxy().getMessage()).isEqualTo("boom");
            });
        }
    }

    /**
     * Peekaboot's own endpoints stay excluded behind a context path: the filter matches on
     * the container's mapped path, which is context-relative, not on the raw request URI.
     */
    @Test
    void shouldSkipPeekabootPathsBehindAContextPath() throws Exception {
        when(request.getContextPath()).thenReturn("/app");
        when(request.getRequestURI()).thenReturn("/app/peekaboot/api/traces");
        when(request.getServletPath()).thenReturn("/peekaboot/api/traces");
        setupTraceContext("trace1");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** The captured path is what the browser addressed - context path included. */
    @Test
    void shouldCaptureTheRequestUriWithItsContextPath() throws Exception {
        setupTraceContext("trace1");
        setupBasicRequestResponse();
        when(request.getContextPath()).thenReturn("/app");
        when(request.getRequestURI()).thenReturn("/app/api/users");
        when(request.getServletPath()).thenReturn("/api/users");

        filter.doFilter(request, response, chain);

        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().path()).isEqualTo("/app/api/users");
    }

    /**
     * A DeferredResult/Callable/SseEmitter handler returns from the initial dispatch before
     * its result exists; the status and duration are only known once the async cycle
     * completes, so capture waits for the container's completion callback.
     */
    @Test
    void shouldCaptureAnAsyncRequestOnCompletionRatherThanOnHandOff() throws Exception {
        setupTraceContext("trace1");
        setupBasicRequestResponse();
        when(request.isAsyncStarted()).thenReturn(true);
        AsyncContext asyncContext = mock(AsyncContext.class);
        when(request.getAsyncContext()).thenReturn(asyncContext);

        filter.doFilter(request, response, chain);

        verify(eventPublisher, never()).publishEvent(any());
        ArgumentCaptor<AsyncListener> listener = ArgumentCaptor.forClass(AsyncListener.class);
        verify(asyncContext).addListener(listener.capture());

        when(response.getStatus()).thenReturn(500);
        listener.getValue().onComplete(new AsyncEvent(asyncContext));

        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(500);
    }

    /** The completion callback runs on a container thread with no current span; the id comes from the request thread. */
    @Test
    void asyncCaptureKeepsTheTraceIdResolvedOnTheRequestThread() throws Exception {
        setupTraceContext("trace1");
        setupBasicRequestResponse();
        when(request.isAsyncStarted()).thenReturn(true);
        AsyncContext asyncContext = mock(AsyncContext.class);
        when(request.getAsyncContext()).thenReturn(asyncContext);
        filter.doFilter(request, response, chain);
        ArgumentCaptor<AsyncListener> listener = ArgumentCaptor.forClass(AsyncListener.class);
        verify(asyncContext).addListener(listener.capture());

        when(tracer.currentSpan()).thenReturn(null);
        listener.getValue().onComplete(new AsyncEvent(asyncContext));

        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().traceId()).isEqualTo("trace1");
    }

    /** A listener is dropped when a new async cycle starts unless it re-registers itself. */
    @Test
    void asyncCaptureFollowsARestartedAsyncCycle() throws Exception {
        setupTraceContext("trace1");
        setupBasicRequestResponse();
        when(request.isAsyncStarted()).thenReturn(true);
        AsyncContext asyncContext = mock(AsyncContext.class);
        when(request.getAsyncContext()).thenReturn(asyncContext);
        filter.doFilter(request, response, chain);
        ArgumentCaptor<AsyncListener> listener = ArgumentCaptor.forClass(AsyncListener.class);
        verify(asyncContext).addListener(listener.capture());

        AsyncContext restarted = mock(AsyncContext.class);
        listener.getValue().onStartAsync(new AsyncEvent(restarted));

        verify(restarted).addListener(listener.getValue());
    }

    private void setupBasicRequestResponse() {
        stubPath("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(200);
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(response.getHeaderNames()).thenReturn(Collections.emptyList());
        when(request.getParameterMap()).thenReturn(Map.of());
    }

    /** Without a context path the request URI and the container's mapped path coincide. */
    private void stubPath(String path) {
        when(request.getRequestURI()).thenReturn(path);
        when(request.getServletPath()).thenReturn(path);
    }

    public static class TestController {
        public void getUsers() {}
    }
}

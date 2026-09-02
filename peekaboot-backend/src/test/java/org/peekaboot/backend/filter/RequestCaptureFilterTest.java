package org.peekaboot.backend.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import java.util.Enumeration;
import java.util.List;
import java.util.PrimitiveIterator;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

@ExtendWith(MockitoExtension.class)
class RequestCaptureFilterTest {

    @Mock
    Tracer tracer;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @Mock
    FilterChain chain;

    MockHttpServletRequest request;
    MockHttpServletResponse response;
    RequestCaptureFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestCaptureFilter(tracer, eventPublisher, new MaskingEngine(), PeekabootPaths.defaults());
        request = get("/api/users");
        response = new MockHttpServletResponse();
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
        request = get(path);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldNotPublishEventWithoutTraceId() throws Exception {
        when(tracer.currentSpan()).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldPublishEventWithTraceId() throws Exception {
        setupTraceContext("abc123");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        RequestCompletedEvent event = publishedEvent();
        assertThat(event.traceId()).isEqualTo("abc123");
        assertThat(event.method()).isEqualTo("GET");
        assertThat(event.path()).isEqualTo("/api/users");
        assertThat(event.status()).isEqualTo(200);
    }

    @Test
    void shouldCaptureRequestHeaders() throws Exception {
        setupTraceContext("trace1");
        request.setMethod("POST");
        request.addHeader("Content-Type", "application/json");
        request.addHeader("Accept", "application/json");
        response.setStatus(201);

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.requestHeaders()).containsEntry("Content-Type", "application/json");
        assertThat(event.requestHeaders()).containsEntry("Accept", "application/json");
    }

    @Test
    void shouldMaskSensitiveHeaders() throws Exception {
        setupTraceContext("trace1");
        request.addHeader("Authorization", "Bearer secret-token");
        request.addHeader("Cookie", "session=xyz");
        request.addHeader("X-Api-Key", "api-key-123");
        request.addHeader("Content-Type", "application/json");

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.requestHeaders()).containsEntry("Authorization", "******");
        assertThat(event.requestHeaders()).containsEntry("Cookie", "******");
        assertThat(event.requestHeaders()).containsEntry("X-Api-Key", "******");
        assertThat(event.requestHeaders()).containsEntry("Content-Type", "application/json");
    }

    /**
     * Header masking goes through the engine's key-name rules rather than a fixed list,
     * so Proxy-Authorization - which carries a credential just as Authorization does - is
     * masked without being named anywhere.
     */
    @Test
    void shouldMaskProxyAuthorizationHeader() throws Exception {
        setupTraceContext("trace1");
        request.addHeader("Proxy-Authorization", "Basic dXNlcjpwYXNz");

        filter.doFilter(request, response, chain);

        assertThat(publishedEvent().requestHeaders()).containsEntry("Proxy-Authorization", "******");
    }

    @Test
    void shouldCaptureQueryParameters() throws Exception {
        setupTraceContext("trace1");
        request.setQueryString("page=1&size=10");
        request.setParameter("page", "1");
        request.setParameter("size", "10");

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.queryParams()).containsEntry("page", List.of("1"));
        assertThat(event.queryParams()).containsEntry("size", List.of("10"));
    }

    @Test
    void shouldMaskSensitiveQueryParameterValues() throws Exception {
        setupTraceContext("trace1");
        request = get("/search");
        request.setQueryString("api_key=xyz&q=widgets");
        request.setParameter("api_key", "xyz");
        request.setParameter("q", "widgets");

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.queryParams()).containsEntry("api_key", List.of("******"));
        assertThat(event.queryParams()).containsEntry("q", List.of("widgets"));
    }

    @Test
    void shouldMaskTheRawQueryStringPerParameter() throws Exception {
        setupTraceContext("trace1");
        request = get("/search");
        request.setQueryString("api_key=xyz&q=widgets");
        request.setParameter("api_key", "xyz");
        request.setParameter("q", "widgets");

        filter.doFilter(request, response, chain);

        assertThat(publishedEvent().queryString()).isEqualTo("api_key=******&q=widgets");
    }

    @Test
    void shouldMaskSensitiveFormParameterValues() throws Exception {
        // getParameterMap() merges query-string and form-body parameters; only actual
        // query-string keys belong in queryParams and only body keys in formParams -
        // "password" here has no query-string counterpart, so it lands in formParams.
        setupTraceContext("trace1");
        request = get("/login");
        request.setMethod("POST");
        request.setContentType("application/x-www-form-urlencoded");
        request.setParameter("username", "alice");
        request.setParameter("password", "hunter2");

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.formParams()).containsEntry("username", List.of("alice"));
        assertThat(event.formParams()).containsEntry("password", List.of("******"));
    }

    /** Multipart fields reach getParameterMap() too; a parameter absent from the query string came from the body. */
    @Test
    void shouldReportMultipartFieldsAsFormParameters() throws Exception {
        setupTraceContext("trace1");
        request = get("/upload");
        request.setMethod("POST");
        request.setContentType("multipart/form-data; boundary=----peekaboot");
        request.setParameter("title", "Quarterly report");

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.formParams()).containsEntry("title", List.of("Quarterly report"));
        assertThat(event.queryParams()).isEmpty();
    }

    @Test
    void shouldReportPatchFormFieldsAsFormParameters() throws Exception {
        setupTraceContext("trace1");
        request = get("/persons/1");
        request.setMethod("PATCH");
        request.setContentType("application/x-www-form-urlencoded");
        request.setParameter("firstName", "Bob");

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.formParams()).containsEntry("firstName", List.of("Bob"));
        assertThat(event.queryParams()).isEmpty();
    }

    @Test
    void shouldPreserveAQueryStringPairWithNoValue() throws Exception {
        setupTraceContext("trace1");
        request = get("/search");
        request.setQueryString("debug&q=widgets");
        request.setParameter("debug", "");
        request.setParameter("q", "widgets");

        filter.doFilter(request, response, chain);

        assertThat(publishedEvent().queryString()).isEqualTo("debug&q=widgets");
    }

    @Test
    void shouldReturnNullQueryStringWhenThereIsNone() throws Exception {
        setupTraceContext("trace1");

        filter.doFilter(request, response, chain);

        assertThat(publishedEvent().queryString()).isNull();
    }

    @Test
    void shouldSeparateQueryAndFormParameters() throws Exception {
        // getParameterMap() merges query-string and form-body parameters;
        // only actual query-string keys belong in queryParams and only
        // body keys in formParams
        setupTraceContext("trace1");
        request.setMethod("POST");
        request.setContentType("application/x-www-form-urlencoded");
        request.setQueryString("page=1");
        request.setParameter("page", "1");
        request.setParameter("firstName", "Bob");
        response.setStatus(201);

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.queryParams()).containsOnlyKeys("page");
        assertThat(event.formParams()).containsOnlyKeys("firstName");
        assertThat(event.formParams()).containsEntry("firstName", List.of("Bob"));
    }

    @Test
    void shouldCaptureControllerInfo() throws Exception {
        setupTraceContext("trace1");
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
                new HandlerMethod(new TestController(), TestController.class.getMethod("getUsers")));

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.controllerClass()).endsWith("TestController");
        assertThat(event.controllerMethod()).isEqualTo("getUsers");
    }

    @Test
    void shouldCaptureResponseHeaders() throws Exception {
        setupTraceContext("trace1");
        response.addHeader("Content-Type", "application/json");
        response.addHeader("X-Request-Id", "req-123");

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.responseHeaders()).containsEntry("Content-Type", "application/json");
        assertThat(event.responseHeaders()).containsEntry("X-Request-Id", "req-123");
    }

    @Test
    void shouldMaskSensitiveResponseHeaders() throws Exception {
        setupTraceContext("trace1");
        response.addHeader("Set-Cookie", "session=abc123");
        response.addHeader("Content-Type", "application/json");

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.responseHeaders()).containsEntry("Set-Cookie", "******");
        assertThat(event.responseHeaders()).containsEntry("Content-Type", "application/json");
    }

    /** The duration spans the chain: the clock is read once before it and once after. */
    @Test
    void shouldCalculateDuration() throws Exception {
        setupTraceContext("trace1");
        PrimitiveIterator.OfLong clock = LongStream.of(1_000, 1_250).iterator();
        filter = new RequestCaptureFilter(
                tracer, eventPublisher, new MaskingEngine(), PeekabootPaths.defaults(), clock::nextLong);

        filter.doFilter(request, response, chain);

        assertThat(publishedEvent().durationMs()).isEqualTo(250);
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

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("Server-Timing"))
                .isEqualTo("trace;desc=\"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01\"");
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

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("Server-Timing")).isEqualTo("trace;desc=\"00-abc123-def456-00\"");
    }

    @Test
    void shouldNotSetServerTimingHeaderWhenNoSpan() throws Exception {
        when(tracer.currentSpan()).thenReturn(null);

        filter.doFilter(request, response, chain);

        assertThat(response.containsHeader("Server-Timing")).isFalse();
    }

    @Test
    void shouldLogWarningAndNotPublishEventWhenCaptureFails() throws Exception {
        setupTraceContext("trace1");
        request = new MockHttpServletRequest("GET", "/api/users") {
            @Override
            public Enumeration<String> getHeaderNames() {
                throw new IllegalStateException("boom");
            }
        };
        request.setServletPath("/api/users");

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
        request.setContextPath("/app");
        request.setRequestURI("/app/peekaboot/api/traces");
        request.setServletPath("/peekaboot/api/traces");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** The captured path is what the browser addressed - context path included. */
    @Test
    void shouldCaptureTheRequestUriWithItsContextPath() throws Exception {
        setupTraceContext("trace1");
        request.setContextPath("/app");
        request.setRequestURI("/app/api/users");
        request.setServletPath("/api/users");

        filter.doFilter(request, response, chain);

        assertThat(publishedEvent().path()).isEqualTo("/app/api/users");
    }

    /**
     * A DeferredResult/Callable/SseEmitter handler returns from the initial dispatch before
     * its result exists; the status and duration are only known once the async cycle
     * completes, so capture waits for the container's completion callback.
     */
    @Test
    void shouldCaptureAnAsyncRequestOnCompletionRatherThanOnHandOff() throws Exception {
        setupTraceContext("trace1");
        MockAsyncContext asyncContext = startAsync();

        filter.doFilter(request, response, chain);

        verify(eventPublisher, never()).publishEvent(any());
        assertThat(asyncContext.getListeners()).hasSize(1);

        response.setStatus(500);
        asyncContext.complete();

        assertThat(publishedEvent().status()).isEqualTo(500);
    }

    /** The completion callback runs on a container thread with no current span; the id comes from the request thread. */
    @Test
    void asyncCaptureKeepsTheTraceIdResolvedOnTheRequestThread() throws Exception {
        setupTraceContext("trace1");
        MockAsyncContext asyncContext = startAsync();
        filter.doFilter(request, response, chain);
        clearInvocations(tracer);

        asyncContext.complete();

        verify(tracer, never()).currentSpan();
        assertThat(publishedEvent().traceId()).isEqualTo("trace1");
    }

    /** A listener is dropped when a new async cycle starts unless it re-registers itself. */
    @Test
    void asyncCaptureFollowsARestartedAsyncCycle() throws Exception {
        setupTraceContext("trace1");
        MockAsyncContext asyncContext = startAsync();
        filter.doFilter(request, response, chain);
        AsyncListener listener = asyncContext.getListeners().get(0);

        MockAsyncContext restarted = new MockAsyncContext(request, response);
        listener.onStartAsync(new AsyncEvent(restarted));

        assertThat(restarted.getListeners()).containsExactly(listener);
    }

    private RequestCompletedEvent publishedEvent() {
        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    /** A GET without a context path: the request URI and the container's mapped path coincide. */
    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }

    /** Puts the request into the state a handler leaves it in after handing off asynchronously. */
    private MockAsyncContext startAsync() {
        request.setAsyncSupported(true);
        return (MockAsyncContext) request.startAsync(request, response);
    }

    public static class TestController {
        public void getUsers() {}
    }
}

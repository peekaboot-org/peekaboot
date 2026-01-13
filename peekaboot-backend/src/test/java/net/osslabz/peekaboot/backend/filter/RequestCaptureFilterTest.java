package net.osslabz.peekaboot.backend.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.osslabz.peekaboot.backend.tracing.event.RequestCompletedEvent;
import net.osslabz.peekaboot.backend.tracing.event.TraceEventBus;
import org.junit.jupiter.api.AfterEach;
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
import org.slf4j.MDC;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RequestCaptureFilterTest {

    @Mock
    TraceEventBus eventBus;

    @Mock
    HttpServletRequest request;

    @Mock
    HttpServletResponse response;

    @Mock
    FilterChain chain;

    RequestCaptureFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestCaptureFilter(eventBus);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
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
        verify(eventBus, never()).publish(any());
    }

    @Test
    void shouldNotPublishEventWithoutTraceId() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/users");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(eventBus, never()).publish(any());
    }

    @Test
    void shouldPublishEventWithTraceId() throws Exception {
        MDC.put("traceId", "abc123");
        setupBasicRequestResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventBus).publish(captor.capture());

        RequestCompletedEvent event = captor.getValue();
        assertThat(event.traceId()).isEqualTo("abc123");
        assertThat(event.method()).isEqualTo("GET");
        assertThat(event.path()).isEqualTo("/api/users");
        assertThat(event.status()).isEqualTo(200);
    }

    @Test
    void shouldCaptureRequestHeaders() throws Exception {
        MDC.put("traceId", "trace1");
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
        verify(eventBus).publish(captor.capture());

        RequestCompletedEvent event = captor.getValue();
        assertThat(event.requestHeaders()).containsEntry("content-type", "application/json");
        assertThat(event.requestHeaders()).containsEntry("accept", "application/json");
    }

    @Test
    void shouldMaskSensitiveHeaders() throws Exception {
        MDC.put("traceId", "trace1");
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
        verify(eventBus).publish(captor.capture());

        RequestCompletedEvent event = captor.getValue();
        assertThat(event.requestHeaders()).containsEntry("authorization", "********");
        assertThat(event.requestHeaders()).containsEntry("cookie", "********");
        assertThat(event.requestHeaders()).containsEntry("x-api-key", "********");
        assertThat(event.requestHeaders()).containsEntry("content-type", "application/json");
    }

    @Test
    void shouldCaptureQueryParameters() throws Exception {
        MDC.put("traceId", "trace1");
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
        verify(eventBus).publish(captor.capture());

        RequestCompletedEvent event = captor.getValue();
        assertThat(event.queryParams()).containsEntry("page", "1");
        assertThat(event.queryParams()).containsEntry("size", "10");
    }

    @Test
    void shouldCaptureControllerInfo() throws Exception {
        MDC.put("traceId", "trace1");
        setupBasicRequestResponse();

        // Mock HandlerMethod
        HandlerMethod handlerMethod = mock(HandlerMethod.class);
        when(handlerMethod.getBeanType()).thenReturn((Class) TestController.class);
        when(handlerMethod.getMethod()).thenReturn(TestController.class.getMethod("getUsers"));
        when(request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE)).thenReturn(handlerMethod);

        filter.doFilter(request, response, chain);

        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventBus).publish(captor.capture());

        RequestCompletedEvent event = captor.getValue();
        assertThat(event.controllerClass()).isEqualTo("TestController");
        assertThat(event.controllerMethod()).isEqualTo("getUsers");
    }

    @Test
    void shouldCaptureResponseHeaders() throws Exception {
        MDC.put("traceId", "trace1");
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
        verify(eventBus).publish(captor.capture());

        RequestCompletedEvent event = captor.getValue();
        assertThat(event.responseHeaders()).containsEntry("content-type", "application/json");
        assertThat(event.responseHeaders()).containsEntry("x-request-id", "req-123");
    }

    @Test
    void shouldMaskSensitiveResponseHeaders() throws Exception {
        MDC.put("traceId", "trace1");
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
        verify(eventBus).publish(captor.capture());

        RequestCompletedEvent event = captor.getValue();
        assertThat(event.responseHeaders()).containsEntry("set-cookie", "********");
        assertThat(event.responseHeaders()).containsEntry("content-type", "application/json");
    }

    @Test
    void shouldStillExecuteFilterChainEvenWhenNoTraceId() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/users");
        // No traceId in MDC

        filter.doFilter(request, response, chain);

        // Chain should still be called
        verify(chain).doFilter(request, response);
        // But no event published
        verify(eventBus, never()).publish(any());
    }

    @Test
    void shouldCalculateDuration() throws Exception {
        MDC.put("traceId", "trace1");
        setupBasicRequestResponse();

        filter.doFilter(request, response, chain);

        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventBus).publish(captor.capture());

        RequestCompletedEvent event = captor.getValue();
        assertThat(event.durationMs()).isGreaterThanOrEqualTo(0);
    }

    private void setupBasicRequestResponse() {
        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(response.getStatus()).thenReturn(200);
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(response.getHeaderNames()).thenReturn(Collections.emptyList());
        when(request.getParameterMap()).thenReturn(Map.of());
    }

    // Test helper class
    public static class TestController {
        public void getUsers() {
        }
    }
}

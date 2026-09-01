package org.peekaboot.backend.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import org.peekaboot.backend.testsupport.LogCapture;

@ExtendWith(MockitoExtension.class)
class DevToolbarFilterTest {

    ToolbarDataProvider toolbarDataProvider = new ToolbarDataProvider();

    @Mock
    Tracer tracer;

    @Mock
    HttpServletRequest request;

    @Mock
    HttpServletResponse response;

    @Mock
    FilterChain chain;

    DevToolbarFilter filter;

    @BeforeEach
    void setUp() {
        filter = new DevToolbarFilter(toolbarDataProvider, tracer);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/static/app.js",
                "/static/style.css",
                "/webjars/jquery/jquery.min.js",
                "/actuator/health",
                "/actuator/info",
                "/peekaboot/api/traces",
                "/peekaboot/ui/dashboard/index.html",
                "/error"
            })
    void shouldSkipExcludedPaths(String path) throws Exception {
        when(request.getRequestURI()).thenReturn(path);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {".css", ".js", ".ico", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".woff", ".woff2", ".ttf", ".eot"
            })
    void shouldSkipStaticFileExtensions(String extension) throws Exception {
        when(request.getRequestURI()).thenReturn("/app/file" + extension);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldSkipAjaxRequests() throws Exception {
        when(request.getRequestURI()).thenReturn("/users/123");
        when(request.getHeader("X-Requested-With")).thenReturn("XMLHttpRequest");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldSkipNonHtmlResponses() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Requested-With")).thenReturn(null);

        ByteArrayOutputStream originalOutput = stubResponseOutputStream();

        doAnswer(invocation -> {
                    ContentBufferingResponseWrapper wrapper =
                            (ContentBufferingResponseWrapper) invocation.getArgument(1);
                    wrapper.setContentType("application/json");
                    wrapper.getWriter().write("{\"id\":1}");
                    return null;
                })
                .when(chain)
                .doFilter(eq(request), any());

        filter.doFilter(request, response, chain);

        assertThat(originalOutput.toString(StandardCharsets.UTF_8)).isEqualTo("{\"id\":1}");
    }

    @Test
    void shouldInjectToolbarIntoHtmlResponse() throws Exception {
        when(request.getRequestURI()).thenReturn("/users/123");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Requested-With")).thenReturn(null);
        when(response.getStatus()).thenReturn(200);

        ByteArrayOutputStream originalOutput = stubResponseOutputStream();

        String htmlContent = "<html><body><h1>Hello</h1></body></html>";
        stubHtmlResponse(htmlContent);

        filter.doFilter(request, response, chain);

        String result = originalOutput.toString(StandardCharsets.UTF_8);
        assertThat(result).contains("<!-- Peekaboot Dev Toolbar -->");
        assertThat(result).contains("peekaboot-toolbar-data");
        assertThat(result).contains("<h1>Hello</h1>");
        assertThat(result).endsWith("</body></html>");
    }

    @Test
    void shouldResolveTraceIdFromCurrentSpanWhenPresent() throws Exception {
        when(request.getRequestURI()).thenReturn("/users/123");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Requested-With")).thenReturn(null);
        when(response.getStatus()).thenReturn(200);

        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(context.traceId()).thenReturn("abc123traceid");
        when(span.context()).thenReturn(context);
        when(tracer.currentSpan()).thenReturn(span);

        ByteArrayOutputStream originalOutput = stubResponseOutputStream();

        String htmlContent = "<html><body><h1>Hello</h1></body></html>";
        stubHtmlResponse(htmlContent);

        filter.doFilter(request, response, chain);

        // resolved traceId must reach the real, injected toolbar JSON
        String result = originalOutput.toString(StandardCharsets.UTF_8);
        assertThat(result).contains("abc123traceid");
    }

    @Test
    void shouldInjectBeforeBodyTagDespiteLengthChangingLowercase() throws Exception {
        // 'İ' (U+0130) lowercases to two characters; the </body> index must be
        // computed on the original string, not a lowercased copy
        when(request.getRequestURI()).thenReturn("/users/123");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Requested-With")).thenReturn(null);
        when(response.getStatus()).thenReturn(200);

        ByteArrayOutputStream originalOutput = stubResponseOutputStream();

        String htmlContent = "<html><BODY>İİİ</BODY></html>";
        stubHtmlResponse(htmlContent);

        filter.doFilter(request, response, chain);

        String result = originalOutput.toString(StandardCharsets.UTF_8);
        assertThat(result).contains("<!-- Peekaboot Dev Toolbar -->");
        assertThat(result.indexOf("<!-- Peekaboot Dev Toolbar -->"))
                .isLessThan(result.toLowerCase(java.util.Locale.ROOT).indexOf("</body>"));
        assertThat(result).endsWith("</BODY></html>");
    }

    @Test
    void shouldPreserveResponseCharsetWhenInjecting() throws Exception {
        when(request.getRequestURI()).thenReturn("/users/123");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Requested-With")).thenReturn(null);
        when(response.getStatus()).thenReturn(200);
        when(response.getCharacterEncoding()).thenReturn("ISO-8859-1");

        ByteArrayOutputStream originalOutput = stubResponseOutputStream();

        String htmlContent = "<html><body>Käse</body></html>";
        stubResponse("text/html;charset=ISO-8859-1", htmlContent);

        filter.doFilter(request, response, chain);

        // the declared charset stays ISO-8859-1, so the body must be encoded with it
        String result = originalOutput.toString(java.nio.charset.Charset.forName("ISO-8859-1"));
        assertThat(result).contains("Käse");
        assertThat(result).contains("<!-- Peekaboot Dev Toolbar -->");
    }

    @Test
    void shouldHandleResponseWithoutBodyTag() throws Exception {
        when(request.getRequestURI()).thenReturn("/fragment");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Requested-With")).thenReturn(null);

        ByteArrayOutputStream originalOutput = stubResponseOutputStream();

        String htmlFragment = "<div>Just a fragment</div>";
        stubHtmlResponse(htmlFragment);

        filter.doFilter(request, response, chain);

        String result = originalOutput.toString(StandardCharsets.UTF_8);
        assertThat(result).isEqualTo(htmlFragment);
        assertThat(result).doesNotContain("Peekaboot");
    }

    @Test
    void shouldHandleToolbarGenerationError() throws Exception {
        when(request.getRequestURI()).thenReturn("/users/123");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Requested-With")).thenReturn(null);
        when(response.getStatus()).thenReturn(200);

        ByteArrayOutputStream originalOutput = stubResponseOutputStream();

        // ToolbarDataProvider is a plain, real class with no injectable failure point;
        // a locally-scoped mock is needed here to force the error path this test targets.
        ToolbarDataProvider throwingProvider = mock(ToolbarDataProvider.class);
        when(throwingProvider.getToolbarSummaryJson(any(), any(), any(Integer.class), any()))
                .thenThrow(new RuntimeException("Provider error"));
        DevToolbarFilter throwingFilter = new DevToolbarFilter(throwingProvider, tracer);

        String htmlContent = "<html><body><h1>Hello</h1></body></html>";
        stubHtmlResponse(htmlContent);

        try (LogCapture capture = LogCapture.attach(DevToolbarFilter.class)) {
            throwingFilter.doFilter(request, response, chain);

            String result = originalOutput.toString(StandardCharsets.UTF_8);
            assertThat(result).isEqualTo(htmlContent);
            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo("Failed to generate toolbar HTML");
                assertThat(event.getThrowableProxy().getMessage()).isEqualTo("Provider error");
            });
        }
    }

    /**
     * A template engine writes progressively, so a template that throws halfway leaves a
     * partial page in the buffer. Committing that partial page as a 200 would take the
     * container's error page - the one thing that tells the developer what broke - off the
     * table; the buffer is dropped and the exception left to the container instead.
     */
    @Test
    void shouldNotCommitAPartialPageWhenTheChainThrows() throws Exception {
        when(request.getRequestURI()).thenReturn("/users/123");
        when(request.getHeader("X-Requested-With")).thenReturn(null);

        doAnswer(invocation -> {
                    ContentBufferingResponseWrapper wrapper =
                            (ContentBufferingResponseWrapper) invocation.getArgument(1);
                    wrapper.setContentType("text/html");
                    wrapper.getWriter().write("<html><body><h1>Half a page");
                    throw new ServletException("template blew up");
                })
                .when(chain)
                .doFilter(eq(request), any());

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(ServletException.class)
                .hasMessage("template blew up");

        verify(response, never()).getOutputStream();
        verify(response, never()).setContentLength(anyInt());
    }

    @Test
    void shouldPassthroughAsyncResponsesWithoutInjection() throws Exception {
        when(request.getRequestURI()).thenReturn("/sse/stream");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Requested-With")).thenReturn(null);
        when(request.isAsyncStarted()).thenReturn(true);

        ByteArrayOutputStream originalOutput = stubResponseOutputStream();

        filter.doFilter(request, response, chain);

        // The async handler keeps writing through the wrapper after doFilter
        // returned; the bytes must reach the real response.
        org.mockito.ArgumentCaptor<jakarta.servlet.ServletResponse> captor =
                org.mockito.ArgumentCaptor.forClass(jakarta.servlet.ServletResponse.class);
        verify(chain).doFilter(eq(request), captor.capture());
        ContentBufferingResponseWrapper wrapper = (ContentBufferingResponseWrapper) captor.getValue();
        wrapper.getOutputStream().write("async data".getBytes(StandardCharsets.UTF_8));

        assertThat(originalOutput.toString(StandardCharsets.UTF_8)).isEqualTo("async data");
    }

    @Test
    void shouldStreamNonHtmlResponsesDuringRequest() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/stream");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Requested-With")).thenReturn(null);

        ByteArrayOutputStream originalOutput = stubResponseOutputStream();

        doAnswer(invocation -> {
                    ContentBufferingResponseWrapper wrapper =
                            (ContentBufferingResponseWrapper) invocation.getArgument(1);
                    wrapper.setContentType("text/event-stream");
                    wrapper.getOutputStream().write("data: tick\n\n".getBytes(StandardCharsets.UTF_8));
                    wrapper.flushBuffer();
                    // must be visible to the client while the handler is still running
                    assertThat(originalOutput.toString(StandardCharsets.UTF_8)).isEqualTo("data: tick\n\n");
                    return null;
                })
                .when(chain)
                .doFilter(eq(request), any());

        filter.doFilter(request, response, chain);

        assertThat(originalOutput.toString(StandardCharsets.UTF_8)).isEqualTo("data: tick\n\n");
    }

    @Test
    void shouldInjectExternalToolbarScriptLoader() throws Exception {
        when(request.getRequestURI()).thenReturn("/users/123");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Requested-With")).thenReturn(null);
        when(response.getStatus()).thenReturn(200);

        ByteArrayOutputStream originalOutput = stubResponseOutputStream();
        stubHtmlResponse("<html><body></body></html>");

        filter.doFilter(request, response, chain);

        String result = originalOutput.toString(StandardCharsets.UTF_8);
        assertThat(result).contains("<script src=\"/peekaboot/ui/toolbar/toolbar.js\" type=\"module\"></script>");
        assertThat(result).contains("id=\"peekaboot-toolbar-data\"");
    }

    @Test
    void shouldInjectIdleModeToolbarForSwaggerUi() throws Exception {
        when(request.getRequestURI()).thenReturn("/swagger-ui/index.html");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Requested-With")).thenReturn(null);

        ByteArrayOutputStream originalOutput = stubResponseOutputStream();
        stubHtmlResponse("<html><body><div id=\"swagger-ui\"></div></body></html>");

        filter.doFilter(request, response, chain);

        String result = originalOutput.toString(StandardCharsets.UTF_8);
        assertThat(result).contains("<!-- Peekaboot Dev Toolbar -->");
        assertThat(result).contains("\"idle\":true");
        assertThat(result).contains("<script src=\"/peekaboot/ui/toolbar/toolbar.js\" type=\"module\"></script>");
    }

    @Test
    void shouldNotUseIdleModeForRegularPages() throws Exception {
        when(request.getRequestURI()).thenReturn("/users/123");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Requested-With")).thenReturn(null);
        when(response.getStatus()).thenReturn(200);

        ByteArrayOutputStream originalOutput = stubResponseOutputStream();
        stubHtmlResponse("<html><body></body></html>");

        filter.doFilter(request, response, chain);

        String result = originalOutput.toString(StandardCharsets.UTF_8);
        assertThat(result).doesNotContain("\"idle\":true");
    }

    /**
     * Stubs {@code response.getOutputStream()} with a buffer-backed {@link TestServletOutputStream}
     * and returns the buffer so the test can read back whatever the filter ultimately wrote.
     */
    private ByteArrayOutputStream stubResponseOutputStream() throws IOException {
        ByteArrayOutputStream originalOutput = new ByteArrayOutputStream();
        TestServletOutputStream servletOutputStream = new TestServletOutputStream(originalOutput);
        when(response.getOutputStream()).thenReturn(servletOutputStream);
        return originalOutput;
    }

    /** {@code stubResponse("text/html", content)} — the common case for the tests below. */
    private void stubHtmlResponse(String content) throws Exception {
        stubResponse("text/html", content);
    }

    /**
     * Makes {@code chain.doFilter} write {@code content} through the {@link ContentBufferingResponseWrapper}
     * the filter passes down, with the given content type, and stubs {@code response.getContentType()}
     * to match so the filter's own content-type check (whether to inject the toolbar at all) sees it.
     */
    private void stubResponse(String contentType, String content) throws Exception {
        doAnswer(invocation -> {
                    ContentBufferingResponseWrapper wrapper =
                            (ContentBufferingResponseWrapper) invocation.getArgument(1);
                    wrapper.setContentType(contentType);
                    wrapper.getWriter().write(content);
                    when(response.getContentType()).thenReturn(contentType);
                    return null;
                })
                .when(chain)
                .doFilter(eq(request), any());
    }

    private static class TestServletOutputStream extends ServletOutputStream {
        private final ByteArrayOutputStream output;

        TestServletOutputStream(ByteArrayOutputStream output) {
            this.output = output;
        }

        @Override
        public void write(int b) throws IOException {
            output.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            output.write(b, off, len);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {}
    }
}

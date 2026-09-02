package org.peekaboot.backend.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class DevToolbarFilterTest {

    ToolbarDataProvider toolbarDataProvider = new ToolbarDataProvider();

    @Mock
    Tracer tracer;

    @Mock
    FilterChain chain;

    MockHttpServletRequest request;
    MockHttpServletResponse response;
    DevToolbarFilter filter;

    @BeforeEach
    void setUp() {
        filter = new DevToolbarFilter(toolbarDataProvider, tracer);
        request = get("/users/123");
        response = new MockHttpServletResponse();
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
        request = get(path);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {".css", ".js", ".ico", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".woff", ".woff2", ".ttf", ".eot"
            })
    void shouldSkipStaticFileExtensions(String extension) throws Exception {
        request = get("/app/file" + extension);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldSkipAjaxRequests() throws Exception {
        request.addHeader("X-Requested-With", "XMLHttpRequest");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    /**
     * Peekaboot's own endpoints stay excluded behind a context path: the filter matches on
     * the container's mapped, context-relative path rather than the raw request URI - which
     * is also what keeps a {@code /x/../peekaboot/...} spelling from slipping past.
     */
    @Test
    void shouldSkipPeekabootPathsBehindAContextPath() throws Exception {
        request.setContextPath("/app");
        request.setRequestURI("/app/peekaboot/ui/dashboard/index.html");
        request.setServletPath("/peekaboot/ui/dashboard/index.html");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    /** Every URL the bar carries - script, sheets, links, API base - has to sit behind the context path. */
    @Test
    void shouldPrefixTheBarsUrlsWithTheContextPath() throws Exception {
        request.setContextPath("/app");
        request.setRequestURI("/app/users/123");
        request.setServletPath("/users/123");
        chainWritesHtml("<html><body></body></html>");

        filter.doFilter(request, response, chain);

        String result = response.getContentAsString();
        assertThat(result).contains("<script src=\"/app/peekaboot/ui/toolbar/toolbar.js\" type=\"module\"></script>");
        assertThat(result).contains("href=\"/app/peekaboot/ui/assets/tokens.css\"");
        assertThat(result).contains("\"basePath\":\"/app/peekaboot\"");
        assertThat(result).contains("\"path\":\"/app/users/123\"");
    }

    @Test
    void shouldSkipNonHtmlResponses() throws Exception {
        request = get("/api/users");
        chainWrites("application/json", "{\"id\":1}");

        filter.doFilter(request, response, chain);

        assertThat(response.getContentAsString()).isEqualTo("{\"id\":1}");
    }

    @Test
    void shouldInjectToolbarIntoHtmlResponse() throws Exception {
        chainWritesHtml("<html><body><h1>Hello</h1></body></html>");

        filter.doFilter(request, response, chain);

        String result = response.getContentAsString();
        assertThat(result).contains("<!-- Peekaboot Dev Toolbar -->");
        assertThat(result).contains("peekaboot-toolbar-data");
        assertThat(result).contains("<h1>Hello</h1>");
        assertThat(result).endsWith("</body></html>");
    }

    @Test
    void shouldResolveTraceIdFromCurrentSpanWhenPresent() throws Exception {
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(context.traceId()).thenReturn("abc123traceid");
        when(span.context()).thenReturn(context);
        when(tracer.currentSpan()).thenReturn(span);
        chainWritesHtml("<html><body><h1>Hello</h1></body></html>");

        filter.doFilter(request, response, chain);

        // resolved traceId must reach the real, injected toolbar JSON
        assertThat(response.getContentAsString()).contains("abc123traceid");
    }

    @Test
    void shouldInjectBeforeBodyTagDespiteLengthChangingLowercase() throws Exception {
        // 'İ' (U+0130) lowercases to two characters; the </body> index must be
        // computed on the original string, not a lowercased copy
        chainWrites("text/html;charset=UTF-8", "<html><BODY>İİİ</BODY></html>");

        filter.doFilter(request, response, chain);

        String result = response.getContentAsString();
        assertThat(result).contains("<!-- Peekaboot Dev Toolbar -->");
        assertThat(result.indexOf("<!-- Peekaboot Dev Toolbar -->"))
                .isLessThan(result.toLowerCase(Locale.ROOT).indexOf("</body>"));
        assertThat(result).endsWith("</BODY></html>");
    }

    @Test
    void shouldPreserveResponseCharsetWhenInjecting() throws Exception {
        chainWrites("text/html;charset=ISO-8859-1", "<html><body>Käse</body></html>");

        filter.doFilter(request, response, chain);

        // the declared charset stays ISO-8859-1, so the body must be encoded with it
        String result = new String(response.getContentAsByteArray(), StandardCharsets.ISO_8859_1);
        assertThat(result).contains("Käse");
        assertThat(result).contains("<!-- Peekaboot Dev Toolbar -->");
    }

    @Test
    void shouldHandleResponseWithoutBodyTag() throws Exception {
        request = get("/fragment");
        String htmlFragment = "<div>Just a fragment</div>";
        chainWritesHtml(htmlFragment);

        filter.doFilter(request, response, chain);

        String result = response.getContentAsString();
        assertThat(result).isEqualTo(htmlFragment);
        assertThat(result).doesNotContain("Peekaboot");
    }

    @Test
    void shouldHandleToolbarGenerationError() throws Exception {
        // ToolbarDataProvider is a plain, real class with no injectable failure point;
        // a locally-scoped mock is needed here to force the error path this test targets.
        ToolbarDataProvider throwingProvider = mock(ToolbarDataProvider.class);
        when(throwingProvider.getToolbarSummaryJson(any(), any(), any(), any(Integer.class), any()))
                .thenThrow(new RuntimeException("Provider error"));
        DevToolbarFilter throwingFilter = new DevToolbarFilter(throwingProvider, tracer);
        String htmlContent = "<html><body><h1>Hello</h1></body></html>";
        chainWritesHtml(htmlContent);

        try (LogCapture capture = LogCapture.attach(DevToolbarFilter.class)) {
            throwingFilter.doFilter(request, response, chain);

            assertThat(response.getContentAsString()).isEqualTo(htmlContent);
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
        doAnswer(invocation -> {
                    ContentBufferingResponseWrapper wrapper = invocation.getArgument(1);
                    wrapper.setContentType("text/html");
                    wrapper.getWriter().write("<html><body><h1>Half a page");
                    throw new ServletException("template blew up");
                })
                .when(chain)
                .doFilter(eq(request), any());

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(ServletException.class)
                .hasMessage("template blew up");

        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.containsHeader("Content-Length")).isFalse();
    }

    /**
     * A browser that navigates away mid-response closes the socket; the container surfaces
     * that as an IOException from the write. Nothing went wrong on the server, so it is a
     * DEBUG line without a stack trace - and no second write onto a dead connection.
     */
    @Test
    void aClientAbortWhileWritingTheInjectedPageIsNotAFailure() throws Exception {
        FailingWriteResponse aborted = new FailingWriteResponse(new ClientAbortException(), false);
        response = aborted;
        chainWritesHtml("<html><body></body></html>");

        try (LogCapture capture = LogCapture.attach(DevToolbarFilter.class, Level.DEBUG)) {
            filter.doFilter(request, response, chain);

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Client closed the connection before the toolbar could be written: GET /users/123");
                assertThat(event.getThrowableProxy()).isNull();
            });
        }
        assertThat(aborted.writeAttempts).isEqualTo(1);
    }

    /** Jetty's EofException carries no message; it is recognised by class name, like Tomcat's. */
    @Test
    void aJettyEofExceptionIsAClientAbortToo() throws Exception {
        FailingWriteResponse aborted = new FailingWriteResponse(new org.eclipse.jetty.io.EofException(), false);
        response = aborted;
        chainWritesHtml("<html><body></body></html>");

        try (LogCapture capture = LogCapture.attach(DevToolbarFilter.class, Level.DEBUG)) {
            filter.doFilter(request, response, chain);

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getThrowableProxy()).isNull();
            });
        }
        assertThat(aborted.writeAttempts).isEqualTo(1);
    }

    /** ClientAbortException is Tomcat's; another container reports the same thing as a plain IOException. */
    @Test
    void aBrokenPipeFromAnotherContainerIsAClientAbortToo() throws Exception {
        FailingWriteResponse aborted = new FailingWriteResponse(new IOException("Broken pipe"), false);
        response = aborted;
        chainWritesHtml("<html><body></body></html>");

        try (LogCapture capture = LogCapture.attach(DevToolbarFilter.class, Level.DEBUG)) {
            filter.doFilter(request, response, chain);

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getThrowableProxy()).isNull();
            });
        }
        assertThat(aborted.writeAttempts).isEqualTo(1);
    }

    /** Once bytes have gone out there is no response left to fall back to; the failure is reported once. */
    @Test
    void aWriteFailureOnACommittedResponseIsWarnedAboutOnceAndNotRetried() throws Exception {
        FailingWriteResponse committed = new FailingWriteResponse(new IOException("disk full"), true);
        response = committed;
        chainWritesHtml("<html><body></body></html>");

        try (LogCapture capture = LogCapture.attach(DevToolbarFilter.class)) {
            filter.doFilter(request, response, chain);

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Failed to inject dev toolbar, returning original response");
                assertThat(event.getThrowableProxy().getMessage()).isEqualTo("disk full");
            });
        }
        assertThat(committed.writeAttempts).isEqualTo(1);
    }

    /** With nothing committed yet, the page the handler produced still gets out, minus the toolbar. */
    @Test
    void aWriteFailureBeforeAnythingWasCommittedFallsBackToTheOriginalPage() throws Exception {
        FailingWriteResponse hiccup = new FailingWriteResponse(new IOException("hiccup"), false);
        response = hiccup;
        String htmlContent = "<html><body></body></html>";
        chainWritesHtml(htmlContent);

        try (LogCapture capture = LogCapture.attach(DevToolbarFilter.class)) {
            filter.doFilter(request, response, chain);

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getThrowableProxy().getMessage()).isEqualTo("hiccup");
            });
        }
        assertThat(hiccup.writeAttempts).isEqualTo(2);
        assertThat(response.getContentAsString()).isEqualTo(htmlContent);
    }

    @Test
    void shouldPassthroughAsyncResponsesWithoutInjection() throws Exception {
        request = get("/sse/stream");
        request.setAsyncStarted(true);

        filter.doFilter(request, response, chain);

        // The async handler keeps writing through the wrapper after doFilter
        // returned; the bytes must reach the real response.
        ArgumentCaptor<ServletResponse> captor = ArgumentCaptor.forClass(ServletResponse.class);
        verify(chain).doFilter(eq(request), captor.capture());
        ContentBufferingResponseWrapper wrapper = (ContentBufferingResponseWrapper) captor.getValue();
        wrapper.getOutputStream().write("async data".getBytes(StandardCharsets.UTF_8));

        assertThat(response.getContentAsString()).isEqualTo("async data");
    }

    @Test
    void shouldStreamNonHtmlResponsesDuringRequest() throws Exception {
        request = get("/api/stream");
        doAnswer(invocation -> {
                    ContentBufferingResponseWrapper wrapper = invocation.getArgument(1);
                    wrapper.setContentType("text/event-stream");
                    wrapper.getOutputStream().write("data: tick\n\n".getBytes(StandardCharsets.UTF_8));
                    wrapper.flushBuffer();
                    // must be visible to the client while the handler is still running
                    assertThat(response.getContentAsString()).isEqualTo("data: tick\n\n");
                    return null;
                })
                .when(chain)
                .doFilter(eq(request), any());

        filter.doFilter(request, response, chain);

        assertThat(response.getContentAsString()).isEqualTo("data: tick\n\n");
    }

    @Test
    void shouldInjectExternalToolbarScriptLoader() throws Exception {
        chainWritesHtml("<html><body></body></html>");

        filter.doFilter(request, response, chain);

        String result = response.getContentAsString();
        assertThat(result).contains("<script src=\"/peekaboot/ui/toolbar/toolbar.js\" type=\"module\"></script>");
        assertThat(result).contains("id=\"peekaboot-toolbar-data\"");
    }

    @Test
    void shouldInjectIdleModeToolbarForSwaggerUi() throws Exception {
        request = get("/swagger-ui/index.html");
        chainWritesHtml("<html><body><div id=\"swagger-ui\"></div></body></html>");

        filter.doFilter(request, response, chain);

        String result = response.getContentAsString();
        assertThat(result).contains("<!-- Peekaboot Dev Toolbar -->");
        assertThat(result).contains("\"idle\":true");
        assertThat(result).contains("<script src=\"/peekaboot/ui/toolbar/toolbar.js\" type=\"module\"></script>");
    }

    @Test
    void shouldNotUseIdleModeForRegularPages() throws Exception {
        chainWritesHtml("<html><body></body></html>");

        filter.doFilter(request, response, chain);

        assertThat(response.getContentAsString()).doesNotContain("\"idle\":true");
    }

    /**
     * A response whose first write fails the way a container's does: with {@code failure},
     * and - when {@code commitsOnFailure} - with the response committed by the bytes that
     * were already on the wire. Later writes succeed.
     */
    private static final class FailingWriteResponse extends MockHttpServletResponse {

        private final IOException failure;
        private final boolean commitsOnFailure;
        int writeAttempts;

        FailingWriteResponse(IOException failure, boolean commitsOnFailure) {
            this.failure = failure;
            this.commitsOnFailure = commitsOnFailure;
        }

        @Override
        public ServletOutputStream getOutputStream() {
            ServletOutputStream real = super.getOutputStream();
            return new ServletOutputStream() {
                @Override
                public void write(int b) throws IOException {
                    attempt();
                    real.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    attempt();
                    real.write(b, off, len);
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {}
            };
        }

        private void attempt() throws IOException {
            if (++writeAttempts == 1) {
                setCommitted(commitsOnFailure);
                throw failure;
            }
        }
    }

    /** A GET without a context path: the request URI and the container's mapped path coincide. */
    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }

    /** {@code chainWrites("text/html", content)} — the common case for the tests above. */
    private void chainWritesHtml(String content) throws Exception {
        chainWrites("text/html", content);
    }

    /**
     * Makes {@code chain.doFilter} write {@code content} with the given content type through
     * the {@link ContentBufferingResponseWrapper} the filter passes down.
     */
    private void chainWrites(String contentType, String content) throws Exception {
        doAnswer(invocation -> {
                    ContentBufferingResponseWrapper wrapper = invocation.getArgument(1);
                    wrapper.setContentType(contentType);
                    wrapper.getWriter().write(content);
                    return null;
                })
                .when(chain)
                .doFilter(eq(request), any());
    }
}

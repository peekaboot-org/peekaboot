package net.osslabz.peekaboot.backend.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.osslabz.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DevToolbarFilterTest {

    @Mock
    ToolbarDataProvider toolbarDataProvider;

    @Mock
    HttpServletRequest request;

    @Mock
    HttpServletResponse response;

    @Mock
    FilterChain chain;

    DevToolbarFilter filter;

    @BeforeEach
    void setUp() {
        filter = new DevToolbarFilter(toolbarDataProvider, "/peekaboot");
    }

    @ParameterizedTest
    @ValueSource(strings = {
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
        when(request.getMethod()).thenReturn("GET");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @ParameterizedTest
    @ValueSource(strings = {".css", ".js", ".ico", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".woff", ".woff2", ".ttf", ".eot"})
    void shouldSkipStaticFileExtensions(String extension) throws Exception {
        when(request.getRequestURI()).thenReturn("/app/file" + extension);
        when(request.getMethod()).thenReturn("GET");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldSkipAjaxRequests() throws Exception {
        when(request.getRequestURI()).thenReturn("/users/123");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Requested-With")).thenReturn("XMLHttpRequest");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldSkipNonHtmlResponses() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Requested-With")).thenReturn(null);

        ByteArrayOutputStream originalOutput = new ByteArrayOutputStream();
        TestServletOutputStream servletOutputStream = new TestServletOutputStream(originalOutput);
        when(response.getOutputStream()).thenReturn(servletOutputStream);

        doAnswer(invocation -> {
            ContentBufferingResponseWrapper wrapper =
                    (ContentBufferingResponseWrapper) invocation.getArgument(1);
            wrapper.setContentType("application/json");
            wrapper.getWriter().write("{\"id\":1}");
            return null;
        }).when(chain).doFilter(eq(request), any());

        filter.doFilter(request, response, chain);

        assertThat(originalOutput.toString(StandardCharsets.UTF_8)).isEqualTo("{\"id\":1}");
    }

    @Test
    void shouldInjectToolbarIntoHtmlResponse() throws Exception {
        when(request.getRequestURI()).thenReturn("/users/123");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Requested-With")).thenReturn(null);
        when(response.getStatus()).thenReturn(200);

        ByteArrayOutputStream originalOutput = new ByteArrayOutputStream();
        TestServletOutputStream servletOutputStream = new TestServletOutputStream(originalOutput);
        when(response.getOutputStream()).thenReturn(servletOutputStream);
        when(toolbarDataProvider.getToolbarSummaryJson(any(), any(), any(Integer.class), any()))
                .thenReturn("{\"method\":\"GET\",\"path\":\"/users/123\",\"status\":200}");

        String htmlContent = "<html><body><h1>Hello</h1></body></html>";

        doAnswer(invocation -> {
            ContentBufferingResponseWrapper wrapper =
                    (ContentBufferingResponseWrapper) invocation.getArgument(1);
            wrapper.setContentType("text/html");
            wrapper.getWriter().write(htmlContent);
            // Also stub the mock to return the content type
            when(response.getContentType()).thenReturn("text/html");
            return null;
        }).when(chain).doFilter(eq(request), any());

        filter.doFilter(request, response, chain);

        String result = originalOutput.toString(StandardCharsets.UTF_8);
        assertThat(result).contains("<!-- Peekaboot Dev Toolbar -->");
        assertThat(result).contains("peekaboot-toolbar-data");
        assertThat(result).contains("<h1>Hello</h1>");
        assertThat(result).endsWith("</body></html>");
    }

    @Test
    void shouldHandleResponseWithoutBodyTag() throws Exception {
        when(request.getRequestURI()).thenReturn("/fragment");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Requested-With")).thenReturn(null);

        ByteArrayOutputStream originalOutput = new ByteArrayOutputStream();
        TestServletOutputStream servletOutputStream = new TestServletOutputStream(originalOutput);
        when(response.getOutputStream()).thenReturn(servletOutputStream);

        String htmlFragment = "<div>Just a fragment</div>";

        doAnswer(invocation -> {
            ContentBufferingResponseWrapper wrapper =
                    (ContentBufferingResponseWrapper) invocation.getArgument(1);
            wrapper.setContentType("text/html");
            wrapper.getWriter().write(htmlFragment);
            when(response.getContentType()).thenReturn("text/html");
            return null;
        }).when(chain).doFilter(eq(request), any());

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

        ByteArrayOutputStream originalOutput = new ByteArrayOutputStream();
        TestServletOutputStream servletOutputStream = new TestServletOutputStream(originalOutput);
        when(response.getOutputStream()).thenReturn(servletOutputStream);
        when(toolbarDataProvider.getToolbarSummaryJson(any(), any(), any(Integer.class), any()))
                .thenThrow(new RuntimeException("Provider error"));

        String htmlContent = "<html><body><h1>Hello</h1></body></html>";

        doAnswer(invocation -> {
            ContentBufferingResponseWrapper wrapper =
                    (ContentBufferingResponseWrapper) invocation.getArgument(1);
            wrapper.setContentType("text/html");
            wrapper.getWriter().write(htmlContent);
            when(response.getContentType()).thenReturn("text/html");
            return null;
        }).when(chain).doFilter(eq(request), any());

        filter.doFilter(request, response, chain);

        String result = originalOutput.toString(StandardCharsets.UTF_8);
        assertThat(result).isEqualTo(htmlContent);
    }

    @Test
    void shouldIncludeToolbarJsPath() throws Exception {
        when(request.getRequestURI()).thenReturn("/page");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Requested-With")).thenReturn(null);
        when(response.getStatus()).thenReturn(200);

        ByteArrayOutputStream originalOutput = new ByteArrayOutputStream();
        TestServletOutputStream servletOutputStream = new TestServletOutputStream(originalOutput);
        when(response.getOutputStream()).thenReturn(servletOutputStream);
        when(toolbarDataProvider.getToolbarSummaryJson(any(), any(), any(Integer.class), any()))
                .thenReturn("{}");

        doAnswer(invocation -> {
            ContentBufferingResponseWrapper wrapper =
                    (ContentBufferingResponseWrapper) invocation.getArgument(1);
            wrapper.setContentType("text/html");
            wrapper.getWriter().write("<html><body></body></html>");
            when(response.getContentType()).thenReturn("text/html");
            return null;
        }).when(chain).doFilter(eq(request), any());

        filter.doFilter(request, response, chain);

        String result = originalOutput.toString(StandardCharsets.UTF_8);
        assertThat(result).contains("/peekaboot/ui/trace-detail/trace-detail.js");
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
        public void setWriteListener(WriteListener listener) {
        }
    }
}

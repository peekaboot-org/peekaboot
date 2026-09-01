package org.peekaboot.backend.filter;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import org.peekaboot.backend.devtoolbar.ToolbarShell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DevToolbarFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(DevToolbarFilter.class);

    private static final String CONTENT_TYPE_HTML = "text/html";
    private static final String BODY_END_TAG = "</body>";
    private static final String SWAGGER_UI_PREFIX = "/swagger-ui/";
    private static final String CLIENT_ABORT_EXCEPTION = "org.apache.catalina.connector.ClientAbortException";
    private static final Set<String> EXCLUDED_EXTENSIONS =
            Set.of(".css", ".js", ".ico", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".woff", ".woff2", ".ttf", ".eot");

    private final ToolbarDataProvider toolbarDataProvider;
    private final Tracer tracer;
    // Reads the bar's stylesheets off the classpath once, at construction, and caches the
    // rendered fragment's CSS; there is one filter instance per application.
    private final ToolbarShell toolbarShell = new ToolbarShell();

    public DevToolbarFilter(ToolbarDataProvider toolbarDataProvider, Tracer tracer) {
        this.toolbarDataProvider = toolbarDataProvider;
        this.tracer = tracer;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            log.trace("Skipping non-HTTP request");
            chain.doFilter(request, response);
            return;
        }

        if (shouldSkip(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        log.trace("DevToolbarFilter processing: {} {}", httpRequest.getMethod(), httpRequest.getRequestURI());
        ContentBufferingResponseWrapper wrappedResponse = new ContentBufferingResponseWrapper(httpResponse);

        // Deliberately not a finally: a handler that throws mid-render leaves a partial page
        // in the buffer, and committing it as a 200 would take the container's error page
        // away from the developer. The buffer is dropped and the exception propagates.
        chain.doFilter(request, wrappedResponse);

        try {
            if (httpRequest.isAsyncStarted()) {
                // async handlers keep writing after this filter returns;
                // hand the response over and skip injection
                wrappedResponse.enablePassthrough();
            } else if (!wrappedResponse.isPassthrough()) {
                processResponse(httpRequest, wrappedResponse, httpResponse);
            }
        } catch (Exception e) {
            if (isClientAbort(e)) {
                log.debug(
                        "Client closed the connection before the toolbar could be written: {} {}",
                        httpRequest.getMethod(),
                        httpRequest.getRequestURI());
            } else {
                log.warn("Failed to inject dev toolbar, returning original response", e);
                if (!httpResponse.isCommitted()) {
                    wrappedResponse.copyBodyToResponse();
                }
            }
        }
    }

    /**
     * Whether the write failed because the client hung up - a browser navigating away
     * mid-response - rather than because of anything on this side. Tomcat wraps that in its
     * own ClientAbortException (recognised by name, since this module does not depend on
     * Tomcat); other containers surface the socket error itself.
     */
    private static boolean isClientAbort(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof IOException
                    && (CLIENT_ABORT_EXCEPTION.equals(t.getClass().getName()) || saysClientWentAway(t.getMessage()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean saysClientWentAway(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("broken pipe") || lower.contains("connection reset");
    }

    private boolean shouldSkip(HttpServletRequest request) {
        String path = PeekabootPaths.pathWithinApplication(request);

        // Skip excluded prefixes
        if (PeekabootPaths.isExcluded(path)) {
            return true;
        }

        // Skip static file extensions
        String lowerPath = path.toLowerCase(Locale.ROOT);
        for (String ext : EXCLUDED_EXTENSIONS) {
            if (lowerPath.endsWith(ext)) {
                return true;
            }
        }

        // Skip AJAX requests
        String xRequestedWith = request.getHeader("X-Requested-With");
        return "XMLHttpRequest".equalsIgnoreCase(xRequestedWith);
    }

    private void processResponse(
            HttpServletRequest request,
            ContentBufferingResponseWrapper wrappedResponse,
            HttpServletResponse originalResponse)
            throws IOException {

        wrappedResponse.flushBuffer();

        String contentType = wrappedResponse.getContentType();
        log.trace("Response content-type: {} for {}", contentType, request.getRequestURI());

        if (contentType == null || !contentType.contains(CONTENT_TYPE_HTML)) {
            log.trace("Skipping toolbar injection - not HTML: {}", contentType);
            wrappedResponse.copyBodyToResponse();
            return;
        }

        String content = wrappedResponse.getContentAsString();
        log.trace("Response content length: {} chars", content.length());

        int bodyEndIndex = lastIndexOfIgnoreCase(content, BODY_END_TAG);

        if (bodyEndIndex == -1) {
            log.trace("Skipping toolbar injection - no </body> tag found");
            wrappedResponse.copyBodyToResponse();
            return;
        }

        String traceId = null;
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            traceId = currentSpan.context().traceId();
        }
        log.trace("Injecting toolbar at position {} with traceId: {}", bodyEndIndex, traceId);

        String toolbarHtml;
        try {
            if (isSwaggerUi(request)) {
                toolbarHtml = generateSwaggerToolbarHtml(request);
            } else {
                toolbarHtml = generateToolbarHtml(request, wrappedResponse, traceId);
            }
        } catch (Exception e) {
            log.warn("Failed to generate toolbar HTML", e);
            wrappedResponse.copyBodyToResponse();
            return;
        }

        String modifiedContent = content.substring(0, bodyEndIndex) + toolbarHtml + content.substring(bodyEndIndex);

        // encode with the response's declared charset, not blindly UTF-8
        String encoding = wrappedResponse.getCharacterEncoding();
        Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        byte[] modifiedBytes = modifiedContent.getBytes(charset);
        originalResponse.setContentType(contentType);
        wrappedResponse.copyBodyToResponse(modifiedBytes);

        log.trace("Toolbar injected successfully for {}", request.getRequestURI());
    }

    /**
     * Case-insensitive lastIndexOf on the original string; a lowercased copy
     * is not length-preserving for all characters (e.g. U+0130).
     */
    private static int lastIndexOfIgnoreCase(String content, String search) {
        for (int i = content.length() - search.length(); i >= 0; i--) {
            if (content.regionMatches(true, i, search, 0, search.length())) {
                return i;
            }
        }
        return -1;
    }

    private String generateToolbarHtml(
            HttpServletRequest request, ContentBufferingResponseWrapper response, String traceId) {
        String basePath = PeekabootPaths.basePath(request);
        String summaryJson = toolbarDataProvider.getToolbarSummaryJson(
                basePath, request.getMethod(), request.getRequestURI(), response.getStatus(), traceId);
        return toolbarShell.render(basePath, summaryJson);
    }

    private boolean isSwaggerUi(HttpServletRequest request) {
        return PeekabootPaths.pathWithinApplication(request).startsWith(SWAGGER_UI_PREFIX);
    }

    private String generateSwaggerToolbarHtml(HttpServletRequest request) {
        String basePath = PeekabootPaths.basePath(request);
        return toolbarShell.render(basePath, toolbarDataProvider.getIdleModeJson(basePath));
    }
}

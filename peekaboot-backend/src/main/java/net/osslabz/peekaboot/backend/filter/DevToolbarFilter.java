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
import net.osslabz.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public class DevToolbarFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(DevToolbarFilter.class);

    private static final String CONTENT_TYPE_HTML = "text/html";
    private static final String BODY_END_TAG = "</body>";
    private static final String SWAGGER_UI_PREFIX = "/swagger-ui/";
    private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
            ".css", ".js", ".ico", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".woff", ".woff2", ".ttf", ".eot"
    );

    private final ToolbarDataProvider toolbarDataProvider;
    private final Tracer tracer;

    public DevToolbarFilter(ToolbarDataProvider toolbarDataProvider, Tracer tracer) {
        this.toolbarDataProvider = toolbarDataProvider;
        this.tracer = tracer;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest) ||
            !(response instanceof HttpServletResponse httpResponse)) {
            log.trace("Skipping non-HTTP request");
            chain.doFilter(request, response);
            return;
        }

        String uri = httpRequest.getRequestURI();

        if (shouldSkip(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        log.trace("DevToolbarFilter processing: {} {}", httpRequest.getMethod(), uri);
        ContentBufferingResponseWrapper wrappedResponse = new ContentBufferingResponseWrapper(httpResponse);

        try {
            chain.doFilter(request, wrappedResponse);
        } finally {
            try {
                processResponse(httpRequest, wrappedResponse, httpResponse);
            } catch (Exception e) {
                log.warn("Failed to inject dev toolbar, returning original response: {}", e.getMessage());
                wrappedResponse.copyBodyToResponse();
            }
        }
    }

    private boolean shouldSkip(HttpServletRequest request) {
        String path = request.getRequestURI();

        // Skip excluded prefixes
        if (FilterPathMatcher.shouldSkip(path)) {
            return true;
        }

        // Skip static file extensions
        String lowerPath = path.toLowerCase();
        for (String ext : EXCLUDED_EXTENSIONS) {
            if (lowerPath.endsWith(ext)) {
                return true;
            }
        }

        // Skip AJAX requests
        String xRequestedWith = request.getHeader("X-Requested-With");
        if ("XMLHttpRequest".equalsIgnoreCase(xRequestedWith)) {
            return true;
        }

        return false;
    }

    private void processResponse(HttpServletRequest request, ContentBufferingResponseWrapper wrappedResponse,
                                  HttpServletResponse originalResponse) throws IOException {

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

        int bodyEndIndex = content.toLowerCase().lastIndexOf(BODY_END_TAG);

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
            if (isSwaggerUi(request.getRequestURI())) {
                toolbarHtml = generateSwaggerToolbarHtml();
            } else {
                toolbarHtml = generateToolbarHtml(request, wrappedResponse, traceId);
            }
        } catch (Exception e) {
            log.warn("Failed to generate toolbar HTML: {}", e.getMessage());
            wrappedResponse.copyBodyToResponse();
            return;
        }

        String modifiedContent = content.substring(0, bodyEndIndex)
                + toolbarHtml
                + content.substring(bodyEndIndex);

        byte[] modifiedBytes = modifiedContent.getBytes(StandardCharsets.UTF_8);
        originalResponse.setContentType(contentType);
        wrappedResponse.copyBodyToResponse(modifiedBytes);

        log.trace("Toolbar injected successfully for {}", request.getRequestURI());
    }

    private String generateToolbarHtml(HttpServletRequest request, ContentBufferingResponseWrapper response,
                                        String traceId) {
        String summaryJson = toolbarDataProvider.getToolbarSummaryJson(
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                traceId
        );
        return toolbarBootstrapHtml(summaryJson);
    }

    /**
     * Injects the toolbar data plus a loader for the external toolbar script;
     * all toolbar logic lives in /peekaboot/ui/toolbar/toolbar.js.
     */
    private String toolbarBootstrapHtml(String dataJson) {
        return """
            <!-- Peekaboot Dev Toolbar -->
            <script id="peekaboot-toolbar-data" type="application/json">{{SUMMARY_JSON}}</script>
            <script src="/peekaboot/ui/toolbar/toolbar.js" defer></script>
            """.replace("{{SUMMARY_JSON}}", dataJson);
    }


    private boolean isSwaggerUi(String path) {
        return path.startsWith(SWAGGER_UI_PREFIX);
    }

    private String generateSwaggerToolbarHtml() {
        return toolbarBootstrapHtml(toolbarDataProvider.getIdleModeJson());
    }
}

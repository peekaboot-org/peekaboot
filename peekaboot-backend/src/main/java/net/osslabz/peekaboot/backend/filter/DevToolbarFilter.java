package net.osslabz.peekaboot.backend.filter;

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
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public class DevToolbarFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(DevToolbarFilter.class);

    private static final String CONTENT_TYPE_HTML = "text/html";
    private static final String BODY_END_TAG = "</body>";
    private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
            ".css", ".js", ".ico", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".woff", ".woff2", ".ttf", ".eot"
    );

    private final ToolbarDataProvider toolbarDataProvider;
    private final String basePath;

    public DevToolbarFilter(ToolbarDataProvider toolbarDataProvider, String basePath) {
        this.toolbarDataProvider = toolbarDataProvider;
        this.basePath = basePath;
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
        log.trace("DevToolbarFilter processing: {} {}", httpRequest.getMethod(), uri);

        if (shouldSkip(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        log.trace("Wrapping response for: {}", uri);
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
            log.trace("Skipping {} - excluded prefix", path);
            return true;
        }

        // Skip static file extensions
        String lowerPath = path.toLowerCase();
        for (String ext : EXCLUDED_EXTENSIONS) {
            if (lowerPath.endsWith(ext)) {
                log.trace("Skipping {} - static extension: {}", path, ext);
                return true;
            }
        }

        // Skip AJAX requests
        String xRequestedWith = request.getHeader("X-Requested-With");
        if ("XMLHttpRequest".equalsIgnoreCase(xRequestedWith)) {
            log.trace("Skipping {} - AJAX request", path);
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

        String traceId = MDC.get("traceId");
        log.trace("Injecting toolbar at position {} with traceId: {}", bodyEndIndex, traceId);

        String toolbarHtml;
        try {
            toolbarHtml = generateToolbarHtml(request, wrappedResponse, traceId);
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

        String template = """
            <!-- Peekaboot Dev Toolbar -->
            <script id="peekaboot-toolbar-data" type="application/json">{{SUMMARY_JSON}}</script>
            <script>
            (function() {
                var data = JSON.parse(document.getElementById('peekaboot-toolbar-data').textContent);
                var host = document.createElement('div');
                host.id = 'peekaboot-toolbar-host';
                host.style.cssText = 'position:fixed;bottom:0;left:0;right:0;z-index:2147483647;';
                document.body.appendChild(host);
                var shadow = host.attachShadow({mode:'open'});

                var style = document.createElement('style');
                style.textContent = `%s`;
                shadow.appendChild(style);

                %s
            })();
            </script>
            """.formatted(generateToolbarCss(), generateToolbarScript());

        return template
                .replace("{{SUMMARY_JSON}}", summaryJson)
                .replace("{{BASE_PATH}}", basePath);
    }

    private String generateToolbarCss() {
        return """
            .peekaboot-bar{display:flex;align-items:center;justify-content:space-between;background:#0d1117;color:#c9d1d9;font:12px/1.4 system-ui,-apple-system,sans-serif;padding:6px 12px;gap:16px;border-top:1px solid #30363d;cursor:pointer}
            .peekaboot-bar:hover{background:#161b22}
            .peekaboot-bar a{color:#58a6ff;text-decoration:none}
            .peekaboot-bar a:hover{text-decoration:underline}
            .peekaboot-left{display:flex;align-items:center;gap:12px}
            .peekaboot-right{display:flex;align-items:center;gap:12px}
            .peekaboot-status{font-weight:600;padding:2px 6px;border-radius:6px}
            .peekaboot-status.s2xx{background:#3fb950;color:#0d1117}
            .peekaboot-status.s3xx{background:#d29922;color:#0d1117}
            .peekaboot-status.s4xx,.peekaboot-status.s5xx{background:#f85149;color:#0d1117}
            .peekaboot-method{color:#8b949e}
            .peekaboot-path{color:#f0f6fc;max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
            .peekaboot-metric{display:flex;align-items:center;gap:4px;color:#8b949e}
            .peekaboot-metric .val{color:#c9d1d9}
            .peekaboot-metric.warn .val{color:#d29922}
            .peekaboot-metric.error .val{color:#f85149}
            .peekaboot-metric.ok .val{color:#3fb950}
            .peekaboot-trace{font-family:ui-monospace,monospace;font-size:11px;color:#8b949e}
            .peekaboot-health{display:flex;align-items:center;gap:4px;font-size:11px}
            .peekaboot-health .dot{width:8px;height:8px;border-radius:50%}
            .peekaboot-health .dot.up{background:#3fb950}
            .peekaboot-health .dot.down{background:#f85149}
            .peekaboot-health .dot.unknown{background:#8b949e}
            .peekaboot-memory{font-size:11px;color:#8b949e}
            .peekaboot-memory.warn{color:#d29922}
            .peekaboot-memory.error{color:#f85149}
            """;
    }

    private String generateToolbarScript() {
        return """
            var bar = document.createElement('div');
            bar.className = 'peekaboot-bar';

            var statusClass = 's' + Math.floor(data.status / 100) + 'xx';
            var durationClass = data.duration > 500 ? 'error' : (data.duration > 100 ? 'warn' : '');
            var queryClass = data.queryCount > 10 ? 'error' : (data.queryCount > 5 ? 'warn' : '');
            var healthClass = data.health === 'UP' ? 'up' : (data.health === 'DOWN' ? 'down' : 'unknown');
            var memoryClass = data.memoryPercent > 90 ? 'error' : (data.memoryPercent > 70 ? 'warn' : '');

            bar.innerHTML = `
                <div class="peekaboot-left">
                    <span class="peekaboot-status ${statusClass}">${data.status}</span>
                    <span class="peekaboot-method">${data.method}</span>
                    <span class="peekaboot-path" title="${data.path}">${data.path}</span>
                    <span class="peekaboot-metric ${durationClass}">\\u23F1<span class="val">${data.duration}ms</span></span>
                    ${data.queryCount >= 0 ? '<span class="peekaboot-metric ' + queryClass + '">\\u{1F5C4}<span class="val">' + data.queryCount + '</span></span>' : ''}
                    ${data.errorCount > 0 ? '<span class="peekaboot-metric error">\\u26A0<span class="val">' + data.errorCount + '</span></span>' : ''}
                </div>
                <div class="peekaboot-right">
                    <span class="peekaboot-health" title="Health: ${data.health}"><span class="dot ${healthClass}"></span>${data.health}</span>
                    ${data.memoryPercent >= 0 ? '<span class="peekaboot-memory ' + memoryClass + '" title="Heap memory">\\u{1F4BE}' + data.memoryPercent + '%</span>' : ''}
                    <span class="peekaboot-trace">${data.traceId ? data.traceId.substring(0, 16) + '...' : '-'}</span>
                    <a href="${data.dashboardUrl}" target="_blank" title="Open Dashboard" onclick="event.stopPropagation()">\\u{1F4CA}</a>
                </div>
            `;
            shadow.appendChild(bar);

            bar.addEventListener('click', function(e) {
                if (e.target.tagName === 'A') return;
                if (!data.traceId) return;
                if (!window.PeekabootTraceDetail) {
                    var script = document.createElement('script');
                    script.src = '{{BASE_PATH}}/ui/trace-detail/trace-detail.js';
                    script.onload = function() {
                        window.PeekabootTraceDetail.open(data.traceId, { basePath: '{{BASE_PATH}}' });
                    };
                    document.head.appendChild(script);
                } else {
                    window.PeekabootTraceDetail.open(data.traceId, { basePath: '{{BASE_PATH}}' });
                }
            });
            """;
    }
}

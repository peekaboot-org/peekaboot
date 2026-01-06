package net.osslabz.peekaboot.backend.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.osslabz.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public class DevToolbarFilter implements Filter {

    private static final String CONTENT_TYPE_HTML = "text/html";
    private static final String BODY_END_TAG = "</body>";
    private static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "/static/",
            "/webjars/",
            "/actuator/",
            "/peekaboot/",
            "/error"
    );
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
            chain.doFilter(request, response);
            return;
        }

        if (shouldSkip(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        ContentBufferingResponseWrapper wrappedResponse = new ContentBufferingResponseWrapper(httpResponse);

        try {
            chain.doFilter(request, wrappedResponse);
        } finally {
            processResponse(httpRequest, wrappedResponse, httpResponse);
        }
    }

    private boolean shouldSkip(HttpServletRequest request) {
        String path = request.getRequestURI();

        // Skip excluded prefixes
        for (String prefix : EXCLUDED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
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
        if (contentType == null || !contentType.contains(CONTENT_TYPE_HTML)) {
            wrappedResponse.copyBodyToResponse();
            return;
        }

        String content = wrappedResponse.getContentAsString();
        int bodyEndIndex = content.toLowerCase().lastIndexOf(BODY_END_TAG);

        if (bodyEndIndex == -1) {
            wrappedResponse.copyBodyToResponse();
            return;
        }

        String traceId = MDC.get("traceId");
        String toolbarHtml = generateToolbarHtml(request, wrappedResponse, traceId);

        String modifiedContent = content.substring(0, bodyEndIndex)
                + toolbarHtml
                + content.substring(bodyEndIndex);

        byte[] modifiedBytes = modifiedContent.getBytes(StandardCharsets.UTF_8);
        originalResponse.setContentType(contentType);
        wrappedResponse.copyBodyToResponse(modifiedBytes);
    }

    private String generateToolbarHtml(HttpServletRequest request, ContentBufferingResponseWrapper response,
                                        String traceId) {
        String summaryJson = toolbarDataProvider.getToolbarSummaryJson(
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                traceId
        );

        return String.format("""
            <!-- Peekaboot Dev Toolbar -->
            <script id="peekaboot-toolbar-data" type="application/json">%s</script>
            <script>
            (function() {
                var data = JSON.parse(document.getElementById('peekaboot-toolbar-data').textContent);
                var host = document.createElement('div');
                host.id = 'peekaboot-toolbar-host';
                host.style.cssText = 'position:fixed;bottom:0;left:0;right:0;z-index:2147483647;';
                document.body.appendChild(host);
                var shadow = host.attachShadow({mode:'closed'});

                var style = document.createElement('style');
                style.textContent = `
                    .peekaboot-bar{display:flex;align-items:center;justify-content:space-between;background:#1a1a2e;color:#eee;font:12px/1.4 system-ui,sans-serif;padding:6px 12px;gap:16px}
                    .peekaboot-bar a{color:#58a6ff;text-decoration:none}
                    .peekaboot-bar a:hover{text-decoration:underline}
                    .peekaboot-left{display:flex;align-items:center;gap:12px}
                    .peekaboot-right{display:flex;align-items:center;gap:12px}
                    .peekaboot-status{font-weight:600;padding:2px 6px;border-radius:3px}
                    .peekaboot-status.s2xx{background:#238636;color:#fff}
                    .peekaboot-status.s3xx{background:#9e6a03;color:#fff}
                    .peekaboot-status.s4xx,.peekaboot-status.s5xx{background:#da3633;color:#fff}
                    .peekaboot-method{color:#8b949e}
                    .peekaboot-path{color:#c9d1d9;max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
                    .peekaboot-metric{display:flex;align-items:center;gap:4px;color:#8b949e}
                    .peekaboot-metric .val{color:#c9d1d9}
                    .peekaboot-metric.warn .val{color:#d29922}
                    .peekaboot-metric.error .val{color:#f85149}
                    .peekaboot-metric.ok .val{color:#3fb950}
                    .peekaboot-trace{font-family:monospace;font-size:11px;color:#8b949e}
                    .peekaboot-health{display:flex;align-items:center;gap:4px;font-size:11px}
                    .peekaboot-health .dot{width:8px;height:8px;border-radius:50%}
                    .peekaboot-health .dot.up{background:#3fb950}
                    .peekaboot-health .dot.down{background:#f85149}
                    .peekaboot-health .dot.unknown{background:#8b949e}
                    .peekaboot-memory{font-size:11px;color:#8b949e}
                    .peekaboot-memory.warn{color:#d29922}
                    .peekaboot-memory.error{color:#f85149}
                    .peekaboot-expand{background:#30363d;border:1px solid #484f58;color:#c9d1d9;padding:4px 8px;border-radius:3px;cursor:pointer;font-size:11px}
                    .peekaboot-expand:hover{background:#484f58}
                `;
                shadow.appendChild(style);

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
                        <a href="${data.dashboardUrl}" target="_blank" title="Open Dashboard">\\u{1F4CA}</a>
                        <button class="peekaboot-expand" title="Expand toolbar">\\u25B2</button>
                    </div>
                `;
                shadow.appendChild(bar);

                // Expand button - lazy load full toolbar
                bar.querySelector('.peekaboot-expand').addEventListener('click', function() {
                    var script = document.createElement('script');
                    script.src = '${basePath}/toolbar/toolbar.js';
                    document.head.appendChild(script);
                });
            })();
            </script>
            """, summaryJson, basePath);
    }
}

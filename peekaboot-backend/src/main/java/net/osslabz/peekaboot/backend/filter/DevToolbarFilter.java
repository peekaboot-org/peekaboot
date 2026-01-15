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

        String traceId = null;
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            traceId = currentSpan.context().traceId();
        }
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

        return template.replace("{{SUMMARY_JSON}}", summaryJson);
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
            .peekaboot-controller{color:#58a6ff;font-size:11px}
            .peekaboot-metrics{display:flex;align-items:center;gap:12px}
            .peekaboot-metric{display:flex;align-items:center;gap:4px;color:#8b949e}
            .peekaboot-metric .val{color:#c9d1d9}
            .peekaboot-metric.warn .val{color:#d29922}
            .peekaboot-metric.error .val{color:#f85149}
            .peekaboot-trace{font-family:ui-monospace,monospace;font-size:11px;color:#8b949e}
            .peekaboot-loading{color:#8b949e;font-size:11px}
            .peekaboot-loading::after{content:'';animation:dots 1.5s infinite}
            @keyframes dots{0%,20%{content:'.'}40%{content:'..'}60%,100%{content:'...'}}
            .peekaboot-pending{color:#8b949e}
            """;
    }

    private String generateToolbarScript() {
        return """
            var bar = document.createElement('div');
            bar.className = 'peekaboot-bar';

            var statusClass = 's' + Math.floor(data.status / 100) + 'xx';

            bar.innerHTML = `
                <div class="peekaboot-left">
                    <span class="peekaboot-status ${statusClass}">${data.status}</span>
                    <span class="peekaboot-method">${data.method}</span>
                    <span class="peekaboot-path" title="${data.path}">${data.path}</span>
                    <span class="peekaboot-controller" id="pb-controller"></span>
                    <span class="peekaboot-metrics" id="pb-metrics">
                        <span class="peekaboot-loading">loading</span>
                    </span>
                </div>
                <div class="peekaboot-right">
                    <span class="peekaboot-trace">${data.traceId ? data.traceId.substring(0, 16) + '...' : '-'}</span>
                    <a href="${data.basePath}/" target="_blank" title="Open Dashboard" onclick="event.stopPropagation()">\\u{1F4CA}</a>
                </div>
            `;
            shadow.appendChild(bar);

            if (data.traceId) {
                var delays = [100, 200, 500];
                var attempt = 0;

                function fetchTrace() {
                    fetch(data.basePath + '/api/traces/' + data.traceId + '/insights')
                        .then(function(resp) {
                            if (resp.ok) return resp.json();
                            if (resp.status === 404 && attempt < delays.length) {
                                setTimeout(fetchTrace, delays[attempt++]);
                                return null;
                            }
                            throw new Error('Not found');
                        })
                        .then(function(trace) {
                            if (!trace) return;
                            updateToolbar(trace);
                        })
                        .catch(function() {
                            showPending();
                        });
                }

                function updateToolbar(trace) {
                    var metrics = trace.metrics || {};
                    var req = trace.request || {};
                    var metricsEl = shadow.getElementById('pb-metrics');
                    var controllerEl = shadow.getElementById('pb-controller');

                    if (req.controllerClass && req.controllerMethod) {
                        var className = req.controllerClass.split('.').pop();
                        controllerEl.textContent = '\\u2192 ' + className + '.' + req.controllerMethod;
                    }

                    var html = '';
                    var durationClass = trace.durationMs > 500 ? 'error' : (trace.durationMs > 100 ? 'warn' : '');
                    html += '<span class="peekaboot-metric ' + durationClass + '">\\u23F1<span class="val">' + trace.durationMs + 'ms</span></span>';

                    if (metrics.dbQueryCount > 0) {
                        var qClass = metrics.dbQueryCount > 10 ? 'error' : (metrics.dbQueryCount > 5 ? 'warn' : '');
                        html += '<span class="peekaboot-metric ' + qClass + '">\\u{1F5C4}<span class="val">' + metrics.dbQueryCount + '\\u00B7' + metrics.dbTotalDurationMs + 'ms</span></span>';
                    }

                    if (metrics.httpCallCount > 0) {
                        html += '<span class="peekaboot-metric">\\u{1F310}<span class="val">' + metrics.httpCallCount + '\\u00B7' + metrics.httpTotalDurationMs + 'ms</span></span>';
                    }

                    if (metrics.errorCount > 0) {
                        html += '<span class="peekaboot-metric error">\\u26A0<span class="val">' + metrics.errorCount + '</span></span>';
                    }

                    metricsEl.innerHTML = html;
                }

                function showPending() {
                    var metricsEl = shadow.getElementById('pb-metrics');
                    metricsEl.innerHTML = '<span class="peekaboot-pending">[\\u23F1 ?] [\\u{1F5C4} ?] [\\u{1F310} ?]</span>';
                }

                setTimeout(fetchTrace, 50);
            }

            bar.addEventListener('click', function(e) {
                if (e.target.tagName === 'A') return;
                if (!data.traceId) return;
                if (!window.PeekabootTraceDetail) {
                    var script = document.createElement('script');
                    script.src = data.basePath + '/ui/trace-detail/trace-detail.js';
                    script.onload = function() {
                        window.PeekabootTraceDetail.open(data.traceId, { basePath: data.basePath });
                    };
                    document.head.appendChild(script);
                } else {
                    window.PeekabootTraceDetail.open(data.traceId, { basePath: data.basePath });
                }
            });
            """;
    }
}

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
            .peekaboot-metrics{display:flex;align-items:center;gap:16px}
            .peekaboot-stat{display:flex;align-items:center;gap:4px;color:#c9d1d9;font-size:11px}
            .peekaboot-stat .sep{color:#8b949e;opacity:0.6}
            .peekaboot-stat .dur{font-family:ui-monospace,monospace}
            .peekaboot-stat.warn .dur{color:#d29922}
            .peekaboot-stat.error .dur{color:#f85149}
            .peekaboot-log-counts{display:flex;align-items:center;gap:8px;font-size:11px}
            .peekaboot-log-count.error{color:#f85149}
            .peekaboot-log-count.warn{color:#d29922}
            .peekaboot-trace{font-family:ui-monospace,monospace;font-size:11px;color:#8b949e}
            .peekaboot-loading{color:#8b949e;font-size:11px}
            .peekaboot-loading::after{content:'';animation:dots 1.5s infinite}
            @keyframes dots{0%,20%{content:'.'}40%{content:'..'}60%,100%{content:'...'}}
            .peekaboot-pending{color:#8b949e}
            """;
    }

    private String generateToolbarScript() {
        return """
            function escapeHtml(s) {
                if (!s) return '';
                return String(s).replace(/[&<>"']/g, function(c) {
                    return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
                });
            }

            var bar = document.createElement('div');
            bar.className = 'peekaboot-bar';

            bar.innerHTML = `
                <div class="peekaboot-left">
                    <span class="peekaboot-status" id="pb-status"></span>
                    <span class="peekaboot-method" id="pb-method"></span>
                    <span class="peekaboot-path" id="pb-path"></span>
                    <span class="peekaboot-controller" id="pb-controller"></span>
                    <span class="peekaboot-metrics" id="pb-metrics">
                        <span class="peekaboot-pending">Waiting for request\\u2026</span>
                    </span>
                </div>
                <div class="peekaboot-right">
                    <span class="peekaboot-trace" id="pb-trace">-</span>
                    <a href="${data.basePath}/" target="_blank" title="Open Dashboard" onclick="event.stopPropagation()">\\u{1F4CA}</a>
                </div>
            `;
            shadow.appendChild(bar);

            var currentTraceId = null;

            function loadTrace(traceId, method, path, status) {
                currentTraceId = traceId;
                var statusClass = 's' + Math.floor(status / 100) + 'xx';
                var safePath = escapeHtml(path);
                var safeMethod = escapeHtml(method);
                var safeTraceId = escapeHtml(traceId);

                var statusEl = shadow.getElementById('pb-status');
                statusEl.textContent = status;
                statusEl.className = 'peekaboot-status ' + statusClass;

                shadow.getElementById('pb-method').textContent = safeMethod;
                var pathEl = shadow.getElementById('pb-path');
                pathEl.textContent = safePath;
                pathEl.title = safePath;

                shadow.getElementById('pb-controller').textContent = '';
                shadow.getElementById('pb-trace').textContent = safeTraceId ? safeTraceId.substring(0, 16) + '...' : '-';

                var metricsEl = shadow.getElementById('pb-metrics');
                metricsEl.innerHTML = '<span class="peekaboot-loading">loading</span>';

                if (traceId) {
                    var retryDelay = 250;
                    var maxTotalDelay = 32000;
                    var totalDelay = 0;

                    function isTraceComplete(trace) {
                        return trace && trace.rootSpan && trace.summary && trace.summary.spans && trace.summary.spans.count > 0;
                    }

                    function fetchTrace() {
                        if (currentTraceId !== traceId) return;
                        fetch(data.basePath + '/api/traces/' + traceId + '/insights')
                            .then(function(resp) {
                                if (currentTraceId !== traceId) return null;
                                if (resp.ok) return resp.json();
                                if (resp.status === 404 && totalDelay < maxTotalDelay) {
                                    totalDelay += retryDelay;
                                    setTimeout(fetchTrace, retryDelay);
                                    retryDelay *= 2;
                                    return null;
                                }
                                throw new Error('Not found');
                            })
                            .then(function(trace) {
                                if (!trace || currentTraceId !== traceId) return;
                                if (!isTraceComplete(trace) && totalDelay < maxTotalDelay) {
                                    totalDelay += retryDelay;
                                    setTimeout(fetchTrace, retryDelay);
                                    retryDelay *= 2;
                                    return;
                                }
                                updateToolbar(trace);
                            })
                            .catch(function() {
                                if (currentTraceId === traceId) showPending();
                            });
                    }

                    function updateToolbar(trace) {
                        if (currentTraceId !== traceId) return;
                        var summary = trace.summary || {};
                        var httpExchange = trace.httpExchange || {};
                        var controller = httpExchange.request && httpExchange.request.controller || {};
                        var metricsEl = shadow.getElementById('pb-metrics');
                        var controllerEl = shadow.getElementById('pb-controller');

                        if (controller.class && controller.method) {
                            var className = controller.class.split('.').pop();
                            controllerEl.textContent = '\\u2192 ' + className + '.' + controller.method;
                        }

                        var html = '';

                        var duration = trace.durationMs || 0;
                        var durationClass = duration > 500 ? 'error' : (duration > 100 ? 'warn' : '');
                        html += '<span class="peekaboot-stat ' + durationClass + '">\\u23F1<span class="dur">' + duration + 'ms</span></span>';

                        var queryCount = trace.queries ? trace.queries.length : (summary.queries ? summary.queries.count : 0);
                        var queryDuration = summary.queries ? summary.queries.totalDurationMs : 0;
                        if (queryCount > 0) {
                            var qClass = queryDuration > 100 ? 'warn' : '';
                            html += '<span class="peekaboot-stat ' + qClass + '">' + queryCount + ' queries<span class="sep"> | </span><span class="dur">' + queryDuration + 'ms</span></span>';
                        }

                        var errorCount = summary.logs ? summary.logs.errorCount : 0;
                        var warnCount = summary.logs ? summary.logs.warnCount : 0;
                        if (errorCount > 0 || warnCount > 0) {
                            html += '<span class="peekaboot-log-counts">';
                            if (errorCount > 0) {
                                html += '<span class="peekaboot-log-count error">\\u2757' + errorCount + ' err</span>';
                            }
                            if (warnCount > 0) {
                                html += '<span class="peekaboot-log-count warn">\\u26A0' + warnCount + ' warn</span>';
                            }
                            html += '</span>';
                        }

                        metricsEl.innerHTML = html;
                    }

                    function showPending() {
                        var metricsEl = shadow.getElementById('pb-metrics');
                        metricsEl.innerHTML = '<span class="peekaboot-pending">[\\u23F1 ?] [\\u{1F4C4} ?] [\\u{1F5C4} ?] [\\u{1F4DD} ?]</span>';
                    }

                    setTimeout(fetchTrace, 50);
                }
            }

            window.__peekaboot = { loadTrace: loadTrace, basePath: data.basePath };

            if (!data.idle && data.traceId) {
                loadTrace(data.traceId, data.method, data.path, data.status);
            }

            bar.addEventListener('click', function(e) {
                if (e.target.tagName === 'A') return;
                if (!currentTraceId) return;
                if (!window.PeekabootTraceDetail) {
                    var script = document.createElement('script');
                    script.src = data.basePath + '/ui/trace-detail/trace-detail.js';
                    script.onload = function() {
                        window.PeekabootTraceDetail.open(currentTraceId, { basePath: data.basePath });
                    };
                    document.head.appendChild(script);
                } else {
                    window.PeekabootTraceDetail.open(currentTraceId, { basePath: data.basePath });
                }
            });
            """;
    }

    private boolean isSwaggerUi(String path) {
        return path.startsWith(SWAGGER_UI_PREFIX);
    }

    private String generateSwaggerToolbarHtml() {
        String idleJson = toolbarDataProvider.getIdleModeJson();

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

                %s
            })();
            </script>
            """.formatted(generateToolbarCss(), generateToolbarScript(), generateFetchInterceptorScript());

        return template.replace("{{SUMMARY_JSON}}", idleJson);
    }

    private String generateFetchInterceptorScript() {
        return """
            // Fetch interceptor for Swagger UI
            (function() {
                var skipPrefixes = ['/v3/api-docs', '/swagger-ui/', '/peekaboot/', '/webjars/', '/actuator/'];
                var originalFetch = window.fetch;

                window.fetch = function(input, init) {
                    var url = (typeof input === 'string') ? input : (input instanceof Request ? input.url : String(input));
                    var method = (init && init.method) ? init.method.toUpperCase() : 'GET';

                    var path;
                    try {
                        var parsed = new URL(url, window.location.origin);
                        path = parsed.pathname;
                    } catch(e) {
                        path = url;
                    }

                    var skip = skipPrefixes.some(function(prefix) { return path.startsWith(prefix); });

                    return originalFetch.apply(this, arguments).then(function(response) {
                        if (skip) return response;

                        var serverTiming = response.headers.get('Server-Timing');
                        if (serverTiming && window.__peekaboot) {
                            var match = serverTiming.match(/trace;desc="00-([a-f0-9]+)-([a-f0-9]+)-([a-f0-9]+)"/);
                            if (match) {
                                var traceId = match[1];
                                window.__peekaboot.loadTrace(traceId, method, path, response.status);
                            }
                        }
                        return response;
                    });
                };
            })();
            """;
    }
}

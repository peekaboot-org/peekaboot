/**
 * Peekaboot dev toolbar (collapsed bar).
 *
 * Loaded by DevToolbarFilter via a small bootstrap:
 *   <script id="peekaboot-toolbar-data" type="application/json">{...}</script>
 *   <script src="/peekaboot/ui/toolbar/toolbar.js" defer></script>
 *
 * The data JSON carries method/path/status/traceId/basePath for regular pages
 * or {idle:true, basePath} for Swagger UI, where a fetch interceptor picks up
 * trace ids from Server-Timing headers instead. Clicking the bar lazy-loads
 * the trace-detail overlay.
 */
(function() {
    'use strict';

    const dataEl = document.getElementById('peekaboot-toolbar-data');
    if (!dataEl || document.getElementById('peekaboot-toolbar-host')) return;
    const data = JSON.parse(dataEl.textContent);

    const TOOLBAR_CSS = `
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
    `;

    const host = document.createElement('div');
    host.id = 'peekaboot-toolbar-host';
    host.style.cssText = 'position:fixed;bottom:0;left:0;right:0;z-index:2147483647;';
    document.body.appendChild(host);
    const shadow = host.attachShadow({mode: 'open'});

    const style = document.createElement('style');
    style.textContent = TOOLBAR_CSS;
    shadow.appendChild(style);

    function escapeHtml(s) {
        if (s == null) return '';
        return String(s).replace(/[&<>"']/g, function(c) {
            return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c];
        });
    }

    const bar = document.createElement('div');
    bar.className = 'peekaboot-bar';

    bar.innerHTML = `
        <div class="peekaboot-left">
            <span class="peekaboot-status" id="pb-status"></span>
            <span class="peekaboot-method" id="pb-method"></span>
            <span class="peekaboot-path" id="pb-path"></span>
            <span class="peekaboot-controller" id="pb-controller"></span>
            <span class="peekaboot-metrics" id="pb-metrics">
                <span class="peekaboot-pending">Waiting for request…</span>
            </span>
        </div>
        <div class="peekaboot-right">
            <span class="peekaboot-trace" id="pb-trace">-</span>
            <a href="${data.basePath}/" target="_blank" title="Open Dashboard" onclick="event.stopPropagation()">\u{1F4CA}</a>
        </div>
    `;
    shadow.appendChild(bar);

    let currentTraceId = null;

    function loadTrace(traceId, method, path, status) {
        currentTraceId = traceId;
        const statusClass = 's' + Math.floor(status / 100) + 'xx';
        const safePath = escapeHtml(path);
        const safeMethod = escapeHtml(method);
        const safeTraceId = escapeHtml(traceId);

        const statusEl = shadow.getElementById('pb-status');
        statusEl.textContent = status;
        statusEl.className = 'peekaboot-status ' + statusClass;

        shadow.getElementById('pb-method').textContent = safeMethod;
        const pathEl = shadow.getElementById('pb-path');
        pathEl.textContent = safePath;
        pathEl.title = safePath;

        shadow.getElementById('pb-controller').textContent = '';
        shadow.getElementById('pb-trace').textContent = safeTraceId ? safeTraceId.substring(0, 16) + '...' : '-';

        const metricsEl = shadow.getElementById('pb-metrics');
        metricsEl.innerHTML = '<span class="peekaboot-loading">loading</span>';

        if (traceId) {
            let retryDelay = 250;
            const maxTotalDelay = 32000;
            let totalDelay = 0;

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
                const summary = trace.summary || {};
                const httpExchange = trace.httpExchange || {};
                const controller = httpExchange.request && httpExchange.request.controller || {};
                const metricsEl = shadow.getElementById('pb-metrics');
                const controllerEl = shadow.getElementById('pb-controller');

                if (controller.class && controller.method) {
                    const className = controller.class.split('.').pop();
                    controllerEl.textContent = '→ ' + className + '.' + controller.method;
                }

                let html = '';

                const duration = trace.durationMs || 0;
                const durationClass = duration > 500 ? 'error' : (duration > 100 ? 'warn' : '');
                html += '<span class="peekaboot-stat ' + durationClass + '">⏱<span class="dur">' + duration + 'ms</span></span>';

                const queryCount = trace.queries ? trace.queries.length : (summary.queries ? summary.queries.count : 0);
                const queryDuration = summary.queries ? summary.queries.totalDurationMs : 0;
                if (queryCount > 0) {
                    const qClass = queryDuration > 100 ? 'warn' : '';
                    html += '<span class="peekaboot-stat ' + qClass + '">' + queryCount + ' queries<span class="sep"> | </span><span class="dur">' + queryDuration + 'ms</span></span>';
                }

                const errorCount = summary.logs ? summary.logs.errorCount : 0;
                const warnCount = summary.logs ? summary.logs.warnCount : 0;
                if (errorCount > 0 || warnCount > 0) {
                    html += '<span class="peekaboot-log-counts">';
                    if (errorCount > 0) {
                        html += '<span class="peekaboot-log-count error">❗' + errorCount + ' err</span>';
                    }
                    if (warnCount > 0) {
                        html += '<span class="peekaboot-log-count warn">⚠' + warnCount + ' warn</span>';
                    }
                    html += '</span>';
                }

                metricsEl.innerHTML = html;
            }

            function showPending() {
                const metricsEl = shadow.getElementById('pb-metrics');
                metricsEl.innerHTML = '<span class="peekaboot-pending">[⏱ ?] [\u{1F4C4} ?] [\u{1F5C4} ?] [\u{1F4DD} ?]</span>';
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
            const script = document.createElement('script');
            script.src = data.basePath + '/ui/trace-detail/trace-detail.js';
            script.onload = function() {
                window.PeekabootTraceDetail.open(currentTraceId, { basePath: data.basePath });
            };
            document.head.appendChild(script);
        } else {
            window.PeekabootTraceDetail.open(currentTraceId, { basePath: data.basePath });
        }
    });

    // Idle mode (Swagger UI): no request of its own - intercept fetch calls and
    // pick up the trace id from the Server-Timing header of API responses.
    if (data.idle) {
        const skipPrefixes = ['/v3/api-docs', '/swagger-ui/', '/peekaboot/', '/webjars/', '/actuator/'];
        const originalFetch = window.fetch;

        window.fetch = function(input, init) {
            const url = (typeof input === 'string') ? input : (input instanceof Request ? input.url : String(input));
            const method = (init && init.method) ? init.method.toUpperCase() : 'GET';

            let path;
            try {
                path = new URL(url, window.location.origin).pathname;
            } catch (e) {
                path = url;
            }

            const skip = skipPrefixes.some(function(prefix) { return path.startsWith(prefix); });

            return originalFetch.apply(this, arguments).then(function(response) {
                if (skip) return response;

                const serverTiming = response.headers.get('Server-Timing');
                if (serverTiming && window.__peekaboot) {
                    const match = serverTiming.match(/trace;desc="00-([a-f0-9]+)-([a-f0-9]+)-([a-f0-9]+)"/);
                    if (match) {
                        window.__peekaboot.loadTrace(match[1], method, path, response.status);
                    }
                }
                return response;
            });
        };
    }
})();

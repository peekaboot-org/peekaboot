/**
 * Peekaboot dev toolbar (collapsed bar).
 *
 * Loaded by DevToolbarFilter via a small bootstrap:
 *   <script id="peekaboot-toolbar-data" type="application/json">{...}</script>
 *   <script src="/peekaboot/ui/toolbar/toolbar.js" type="module"></script>
 *
 * The data JSON carries method/path/status/traceId/basePath for regular pages
 * or {idle:true, basePath} for Swagger UI, where a fetch interceptor picks up
 * trace ids from Server-Timing headers instead. Clicking the bar (or pressing
 * Enter/Space while it has focus) lazy-imports the trace-detail overlay.
 */
import {formatDurationMs} from '../shared/format.js';
import {durationSeverity} from '../shared/severity.js';
import {resolveTheme, applyTheme, watchTheme} from '../shared/theme.js';
import {attachSharedStyles} from '../shared/shadow-styles.js';
import {copyableIdHtml, bindCopyables} from '../shared/copyable.js';

const dataEl = document.getElementById('peekaboot-toolbar-data');
if (dataEl && !document.getElementById('peekaboot-toolbar-host')) {
    initToolbar(JSON.parse(dataEl.textContent));
}

function initToolbar(data) {
    const host = document.createElement('div');
    host.id = 'peekaboot-toolbar-host';
    host.style.cssText = 'position:fixed;bottom:0;left:0;right:0;z-index:2147483647;';
    document.body.appendChild(host);

    const shadow = host.attachShadow({mode: 'open'});
    applyTheme(host, resolveTheme());
    watchTheme(theme => applyTheme(host, theme));
    attachSharedStyles(shadow, host, data.basePath, `${data.basePath}/ui/toolbar/toolbar.css`);

    const bar = document.createElement('div');
    bar.className = 'pk-toolbar';

    // A real <button> carries the "open trace details" action so keyboard users get it for
    // free (Enter/Space -> a real click event, natively) and assistive tech gets a proper
    // control - not a role="button" div, which ARIA defines as children-presentational and
    // could flatten the dashboard link right out of the accessibility tree. The link and the
    // copyable trace id are siblings, not descendants of the button, so both stay
    // independently reachable.
    bar.innerHTML = `
        <button type="button" class="pk-toolbar__open" aria-label="Open request trace details" aria-disabled="true">
            <span class="pk-toolbar__side">
                <span class="pk-badge" id="pk-status"></span>
                <span class="pk-toolbar__method" id="pk-method"></span>
                <span class="pk-toolbar__path" id="pk-path"></span>
                <span class="pk-toolbar__controller" id="pk-controller"></span>
                <span class="pk-toolbar__metrics" id="pk-metrics">
                    <span class="pk-toolbar__pending">Waiting for request…</span>
                </span>
            </span>
        </button>
        <span class="pk-toolbar__trace" id="pk-trace">-</span>
        <a class="pk-toolbar__link" href="${data.basePath}/" target="_blank" title="Open Dashboard" aria-label="Open Peekaboot dashboard"></a>
    `;
    shadow.appendChild(bar);

    const openButton = shadow.querySelector('.pk-toolbar__open');
    // A dedicated listener (rather than the inline onclick CSP would block on host pages
    // whose script-src disallows 'unsafe-inline') keeps the link's own click from also
    // triggering the bar's open-overlay handler below.
    shadow.querySelector('.pk-toolbar__link').addEventListener('click', e => e.stopPropagation());

    let currentTraceId = null;

    function loadTrace(traceId, method, path, status) {
        currentTraceId = traceId;
        openButton.setAttribute('aria-disabled', traceId ? 'false' : 'true');

        const statusFamily = Math.floor(status / 100);
        const statusVariant = statusFamily === 2 ? 'ok' : (statusFamily === 3 ? 'warn' : 'error');

        const statusEl = shadow.getElementById('pk-status');
        statusEl.textContent = status;
        statusEl.className = 'pk-badge pk-badge--' + statusVariant;

        // textContent/title are safe sinks on their own; escaping before assigning to them
        // would double-escape (e.g. a literal "&" in the path would render as "&amp;").
        shadow.getElementById('pk-method').textContent = method;
        const pathEl = shadow.getElementById('pk-path');
        pathEl.textContent = path;
        pathEl.title = path;

        shadow.getElementById('pk-controller').textContent = '';
        // full id, labelled and copyable - a truncated id cannot be pasted into a log
        // search, which is the only reason to show it on the bar at all
        shadow.getElementById('pk-trace').innerHTML = copyableIdHtml(traceId, {label: 'traceId'});
        bindCopyables(shadow);

        const metricsEl = shadow.getElementById('pk-metrics');
        metricsEl.innerHTML = '<span class="pk-toolbar__loading">loading</span>';

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
                const metricsEl = shadow.getElementById('pk-metrics');
                const controllerEl = shadow.getElementById('pk-controller');

                if (controller.class && controller.method) {
                    const className = controller.class.split('.').pop();
                    controllerEl.textContent = '→ ' + className + '.' + controller.method;
                }

                let html = '';

                const duration = trace.durationMs || 0;
                const durationClass = durationSeverity(duration);
                html += '<span class="pk-stat' + (durationClass ? ' pk-stat--' + durationClass : '')
                    + '">⏱<span class="pk-stat__duration">' + formatDurationMs(duration) + '</span></span>';

                const queryCount = trace.queries ? trace.queries.length : (summary.queries ? summary.queries.count : 0);
                const queryDuration = summary.queries ? summary.queries.totalDurationMs : 0;
                if (queryCount > 0) {
                    const queryClass = durationSeverity(queryDuration);
                    html += '<span class="pk-stat' + (queryClass ? ' pk-stat--' + queryClass : '')
                        + '">' + queryCount + ' queries<span class="pk-stat__separator"> | </span><span class="pk-stat__duration">'
                        + formatDurationMs(queryDuration) + '</span></span>';
                }

                const errorCount = summary.logs ? summary.logs.errorCount : 0;
                const warnCount = summary.logs ? summary.logs.warnCount : 0;
                if (errorCount > 0) {
                    html += '<span class="pk-badge pk-badge--error">❗' + errorCount + ' err</span>';
                }
                if (warnCount > 0) {
                    html += '<span class="pk-badge pk-badge--warn">⚠' + warnCount + ' warn</span>';
                }

                metricsEl.innerHTML = html;
            }

            function showPending() {
                const metricsEl = shadow.getElementById('pk-metrics');
                metricsEl.innerHTML = '<span class="pk-toolbar__pending">[⏱ ?] [\u{1F4C4} ?] [\u{1F5C4} ?] [\u{1F4DD} ?]</span>';
            }

            setTimeout(fetchTrace, 50);
        }
    }

    async function openOverlay() {
        const overlay = await import('../trace-detail/trace-detail.js');
        overlay.open(currentTraceId, {basePath: data.basePath});
    }

    if (!data.idle && data.traceId) {
        loadTrace(data.traceId, data.method, data.path, data.status);
    }

    // Attached to the outer bar (not just the button) so a click anywhere on it - other
    // than the dashboard link, which stops its own propagation above - opens the overlay,
    // matching the pre-migration behaviour. A real <button> click (mouse or native
    // Enter/Space activation) bubbles up to this listener like any other click.
    bar.addEventListener('click', function(e) {
        if (e.target.closest('a')) return;
        if (!currentTraceId) return;
        // openOverlay()'s dynamic import can reject (404, offline, a host page's CSP
        // blocking the module) - toolbar.js runs inside pages Peekaboot does not own, so
        // an unhandled rejection here would surface as *their* error on any host wired to
        // Sentry/Datadog/etc. Catch and log instead of letting it escape; the bar itself
        // needs no state cleanup since nothing above sets one before the import settles.
        openOverlay().catch(function(error) {
            console.warn('Peekaboot: failed to open trace overlay', error);
        });
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
                if (serverTiming) {
                    const match = serverTiming.match(/trace;desc="00-([a-f0-9]+)-([a-f0-9]+)-([a-f0-9]+)"/);
                    if (match) {
                        loadTrace(match[1], method, path, response.status);
                    }
                }
                return response;
            });
        };
    }
}

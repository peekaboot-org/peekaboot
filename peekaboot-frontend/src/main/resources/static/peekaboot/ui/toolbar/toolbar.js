/**
 * Peekaboot dev toolbar (collapsed bar).
 *
 * The bar itself is rendered by DevToolbarFilter (see ToolbarShell) into a declarative
 * shadow root, together with its stylesheets and this data blob:
 *   <div id="peekaboot-toolbar-host"><template shadowrootmode="open">...</template></div>
 *   <script id="peekaboot-toolbar-data" type="application/json">{...}</script>
 *   <script src="/peekaboot/ui/toolbar/toolbar.js" type="module"></script>
 * This module enhances that markup rather than building it, so a page whose authorization
 * gate refuses this script still gets a bar - carrying the notice removed in initToolbar()
 * below, which links to the dashboard so the reader can authenticate.
 *
 * The data JSON carries method/path/status/traceId/basePath for regular pages
 * or {idle:true, basePath} for Swagger UI, where a fetch interceptor picks up
 * trace ids from Server-Timing headers instead. Clicking the bar (or pressing
 * Enter/Space while it has focus) lazy-imports the trace-detail overlay.
 */
import {formatDurationMs} from '../shared/format.js';
import {durationSeverity} from '../shared/severity.js';
import {statusVariant} from '../shared/http-status.js';
import {resolveTheme, applyTheme, watchTheme} from '../shared/theme.js';
import {copyableIdHtml, bindCopyables} from '../shared/copyable.js';

const dataEl = document.getElementById('peekaboot-toolbar-data');
const hostEl = document.getElementById('peekaboot-toolbar-host');
// shadowRoot is absent if the browser did not honour <template shadowrootmode>; there is
// nothing to enhance in that case, and the unenhanced bar is still on the page.
if (dataEl && hostEl && hostEl.shadowRoot && !hostEl.dataset.pkReady) {
    hostEl.dataset.pkReady = 'true';
    initToolbar(hostEl, JSON.parse(dataEl.textContent));
}

function initToolbar(host, data) {
    const shadow = host.shadowRoot;
    applyTheme(host, resolveTheme());
    watchTheme(theme => applyTheme(host, theme));

    // Reaching this line is itself the proof that /peekaboot/** is readable by whoever is
    // looking, so the notice the server rendered for the opposite case has served its
    // purpose. Removing it rather than hiding it keeps it out of the accessibility tree.
    const authNotice = shadow.getElementById('pk-auth');
    if (authNotice) authNotice.remove();

    const bar = shadow.querySelector('.pk-toolbar');

    const openButton = shadow.querySelector('.pk-toolbar__open');
    // A dedicated listener (rather than the inline onclick CSP would block on host pages
    // whose script-src disallows 'unsafe-inline') keeps the link's own click from also
    // triggering the bar's open-overlay handler below.
    shadow.querySelector('.pk-toolbar__link').addEventListener('click', e => e.stopPropagation());

    let currentTraceId = null;

    function loadTrace(traceId, method, path, status) {
        currentTraceId = traceId;
        openButton.setAttribute('aria-disabled', traceId ? 'false' : 'true');

        // The bar has room for the number alone; the overlay's own pill spells the
        // status out. The colouring is shared, so a 404 reads the same in both places.
        const statusEl = shadow.getElementById('pk-status');
        statusEl.textContent = status;
        statusEl.className = 'pk-badge pk-badge--' + statusVariant(status);

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
            // Four fixed attempts rather than backoff-until-complete: every one runs, so a
            // span that ends after the root - an @Async continuation, a streamed body - still
            // reaches the bar instead of being missed by a loop that stops the first time a
            // trace looks finished. Waits are measured from the previous attempt, so the last
            // lands at 4.75s; with peekaboot's 200ms span export delay, a trace absent by then
            // is not coming.
            const attemptDelays = [250, 500, 1000, 3000];
            let rendered = false;

            function attempt(index) {
                if (currentTraceId !== traceId || index >= attemptDelays.length) return;
                setTimeout(function() {
                    if (currentTraceId !== traceId) return;
                    fetch(data.basePath + '/api/traces/' + traceId + '/insights')
                        .then(function(resp) {
                            if (currentTraceId !== traceId) return null;
                            return resp.ok ? resp.json() : null;
                        })
                        .then(function(trace) {
                            if (currentTraceId !== traceId) return;
                            // a 404 or an empty result leaves the previous render standing
                            if (trace && trace.rootSpan) updateToolbar(trace);
                            else if (isLastAttempt(index)) showPendingIfUnrendered();
                        })
                        .catch(function() {
                            if (currentTraceId === traceId && isLastAttempt(index)) {
                                showPendingIfUnrendered();
                            }
                        })
                        .finally(function() { attempt(index + 1); });
                }, attemptDelays[index]);
            }

            function isLastAttempt(index) {
                return index === attemptDelays.length - 1;
            }

            // Nothing ever arrived: replace "loading" with the placeholder row rather than
            // leaving a spinner up forever. A bar that did render keeps what it has.
            function showPendingIfUnrendered() {
                if (!rendered) showPending();
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
                    + '"><span aria-hidden="true">⏱</span><span class="pk-stat__duration">' + formatDurationMs(duration) + '</span></span>';

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
                    html += '<span class="pk-badge pk-badge--error"><span aria-hidden="true">❗</span>' + errorCount + ' err</span>';
                }
                if (warnCount > 0) {
                    html += '<span class="pk-badge pk-badge--warn"><span aria-hidden="true">⚠</span>' + warnCount + ' warn</span>';
                }

                metricsEl.innerHTML = html;
                rendered = true;
            }

            function showPending() {
                const metricsEl = shadow.getElementById('pk-metrics');
                metricsEl.innerHTML = '<span class="pk-toolbar__pending">[⏱ ?] [\u{1F4C4} ?] [\u{1F5C4} ?] [\u{1F4DD} ?]</span>';
            }

            attempt(0);
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
    // than the dashboard link, which stops its own propagation above - opens the overlay.
    // A real <button> click (mouse or native Enter/Space activation) bubbles up to this
    // listener like any other click.
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
        // basePath is <context-path>/peekaboot; fetched paths carry the same context path, so
        // the prefixes to ignore have to be put behind it too.
        const contextPath = data.basePath.slice(0, data.basePath.lastIndexOf('/'));
        const skipPrefixes = ['/v3/api-docs', '/swagger-ui/', '/peekaboot/', '/webjars/', '/actuator/']
            .map(function(prefix) { return contextPath + prefix; });
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

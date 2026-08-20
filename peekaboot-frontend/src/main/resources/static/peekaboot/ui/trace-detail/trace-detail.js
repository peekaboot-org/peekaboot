/**
 * Peekaboot trace-detail overlay.
 *
 * Loaded two ways:
 *  - lazily, via toolbar.js's `await import('../trace-detail/trace-detail.js')`, calling
 *    the named `open`/`close` exports;
 *  - eagerly, via a `<script type="module">` tag in dashboard/index.html. The dashboard's
 *    own peekaboot.js is still a classic `defer` script with no imports, so it cannot see
 *    the module exports - it calls `window.PeekabootTraceDetail` instead. That global stays
 *    assigned alongside the exports until peekaboot.js itself becomes a module.
 */
import {escapeHtml} from '../shared/markup.js';
import {formatTimeOfDay} from '../shared/format.js';
import {durationSeverity} from '../shared/severity.js';
import {rootActionIcon} from '../shared/root-actions.js';
import {resolveTheme, applyTheme, watchTheme} from '../shared/theme.js';
import {attachSharedStyles} from '../shared/shadow-styles.js';
import {createClient} from '../shared/api.js';

let escHandler = null;
let onCloseCallback = null;
let themeUnwatch = null;
let previouslyFocusedElement = null;
let currentSession = 0;

/**
 * The truly-focused element, descending through open shadow roots. document.activeElement
 * only ever returns the outermost shadow host on the path to focus - both the toolbar and
 * this overlay live in their own shadow roots, so restoring focus to the overlay's invoker
 * (usually the toolbar's button) requires walking down to the actual focused leaf.
 */
function deepActiveElement() {
    let element = document.activeElement;
    while (element && element.shadowRoot && element.shadowRoot.activeElement) {
        element = element.shadowRoot.activeElement;
    }
    return element;
}

function buildSpanNames(rootSpan) {
    const names = new Map();
    (function walk(span) {
        if (!span) return;
        names.set(span.spanId, span.name);
        (span.children || []).forEach(walk);
    })(rootSpan);
    return names;
}

export function openTraceDetail(traceId, options = {}) {
    // Captured before closeTraceDetail() below, which restores focus to whatever a prior
    // open() left behind - that would otherwise clobber the element we want to return to.
    const invokingElement = deepActiveElement();
    closeTraceDetail();

    previouslyFocusedElement = invokingElement;
    onCloseCallback = options.onClose || null;
    currentSession += 1;
    const session = currentSession;

    const basePath = options.basePath || '/peekaboot';

    const overlayHost = document.createElement('div');
    overlayHost.id = 'peekaboot-trace-overlay';
    // Decision: size the host with an inline style set synchronously here (matching
    // toolbar.js's host), not a `:host{position:fixed;inset:0}` rule in trace-detail.css.
    // attachSharedStyles() links that stylesheet asynchronously, so a CSS-only :host rule
    // would leave the host collapsed to zero size - and Playwright's isVisible() reporting
    // false - for the whole load window. An inline style applies the instant this element
    // exists, no network round trip required.
    overlayHost.style.cssText = 'position:fixed;inset:0;';
    document.body.appendChild(overlayHost);

    const shadow = overlayHost.attachShadow({mode: 'open'});
    applyTheme(overlayHost, resolveTheme());
    themeUnwatch = watchTheme(theme => applyTheme(overlayHost, theme));
    // attachSharedStyles keeps the host visibility:hidden until its <link> sheets settle;
    // an element under a visibility:hidden ancestor cannot take focus, so the eventual
    // render() -> container.focus() call must wait for this to resolve too, not just the
    // trace fetch - see the Promise.all in fetchAndRender.
    const styleReady = attachSharedStyles(shadow, overlayHost, basePath, `${basePath}/ui/trace-detail/trace-detail.css`);

    const content = document.createElement('div');
    shadow.appendChild(content);
    content.innerHTML = '<div class="pk-overlay"><div class="pk-overlay__loading">Loading trace data...</div></div>';

    fetchAndRender(content, traceId, {basePath, session, styleReady});
}

export function closeTraceDetail() {
    const existing = document.getElementById('peekaboot-trace-overlay');
    if (existing) {
        existing.remove();
    }
    if (escHandler) {
        document.removeEventListener('keydown', escHandler);
        escHandler = null;
    }
    if (themeUnwatch) {
        themeUnwatch();
        themeUnwatch = null;
    }
    if (previouslyFocusedElement && typeof previouslyFocusedElement.focus === 'function') {
        previouslyFocusedElement.focus();
    }
    previouslyFocusedElement = null;
    if (onCloseCallback) {
        const callback = onCloseCallback;
        onCloseCallback = null;
        callback();
    }
}

async function fetchAndRender(content, traceId, {basePath, session, styleReady}) {
    const client = createClient({basePath});
    try {
        const [trace] = await Promise.all([client.get(`/api/traces/${traceId}/insights`), styleReady]);
        // A newer open() superseded this one while the request was in flight.
        if (session !== currentSession) return;
        render(content, trace);
    } catch (error) {
        if (session !== currentSession) return;
        content.innerHTML = `<div class="pk-overlay"><div class="pk-overlay__error">`
            + `Failed to load trace: ${escapeHtml(error.message)}<br><br>`
            + `<button type="button" class="pk-btn">Close</button></div></div>`;
        content.querySelector('.pk-overlay__error button').addEventListener('click', closeTraceDetail);
    }
}

function statusBadgeVariant(statusNum) {
    if (!Number.isFinite(statusNum)) return 'muted';
    const family = Math.floor(statusNum / 100);
    if (family === 2) return 'ok';
    if (family === 3) return 'warn';
    return 'error';
}

function render(content, trace) {
    const rootSpan = trace.rootSpan || {};
    const tags = rootSpan.tags || {};
    const httpExchange = trace.httpExchange || {};
    const req = httpExchange.request || {};
    const res = httpExchange.response || {};
    // Prefer httpExchange data, fall back to span tags
    const method = req.method || tags['http.method'] || tags['http.request.method'] || 'UNKNOWN';
    const path = req.path || tags['http.target'] || tags['url.path'] || rootSpan.name || '-';
    const status = res.status || tags['http.status_code'] || tags['http.response.status_code'] || '-';
    const statusNum = parseInt(status);
    const durationClass = durationSeverity(trace.durationMs);

    const queryCount = (trace.queries || []).length;
    const logCount = (trace.logs || []).length;
    const spanCount = trace.summary?.spans?.count || countSpans(trace.rootSpan);
    const spanNames = buildSpanNames(trace.rootSpan);

    content.innerHTML = `
        <div class="pk-overlay" role="dialog" aria-modal="true" aria-labelledby="pk-overlay-title" tabindex="-1">
            <div class="pk-overlay__container">
                <div class="pk-overlay__header">
                    <button type="button" class="pk-overlay__back" title="Back">&#8592;</button>
                    <div class="pk-overlay__title" id="pk-overlay-title">
                        <span class="pk-overlay__title-icon"></span>
                        <span class="pk-overlay__title-method">${escapeHtml(method)}</span>
                        <span class="pk-overlay__title-path" title="${escapeHtml(path)}">${escapeHtml(path)}</span>
                        <span class="pk-overlay__title-traceid" title="${escapeHtml(trace.traceId || '')}">${escapeHtml(trace.traceId || '-')}</span>
                    </div>
                    <div class="pk-overlay__meta">
                        <span class="pk-overlay__duration${durationClass ? ' pk-overlay__duration--' + durationClass : ''}">${trace.durationMs}ms</span>
                        <span class="pk-badge pk-badge--${statusBadgeVariant(statusNum)}">${escapeHtml(String(status))}</span>
                        <span>${spanCount} spans</span>
                        <span>${queryCount} queries</span>
                        <span>${logCount} logs</span>
                    </div>
                    <button type="button" class="pk-overlay__close" title="Close">&times;</button>
                </div>
                <div class="pk-tabs" role="tablist">
                    <button type="button" class="pk-tab" role="tab" data-tab="request" aria-selected="false">Request</button>
                    <button type="button" class="pk-tab" role="tab" data-tab="spans" aria-selected="true">Spans</button>
                    <button type="button" class="pk-tab" role="tab" data-tab="queries" aria-selected="false">Queries <span class="pk-tab__count">${queryCount}</span></button>
                    <button type="button" class="pk-tab" role="tab" data-tab="logs" aria-selected="false">Logs <span class="pk-tab__count">${logCount}</span></button>
                </div>
                <div class="pk-overlay__content" id="pk-tab-content"></div>
                <div id="pk-logs-popup" class="pk-logs-popup hidden"></div>
            </div>
        </div>
    `;

    const container = content.querySelector('.pk-overlay');
    container.querySelector('.pk-overlay__title-icon').textContent = rootActionIcon(trace.rootActionType);

    container.querySelector('.pk-overlay__back').addEventListener('click', closeTraceDetail);
    container.querySelector('.pk-overlay__close').addEventListener('click', closeTraceDetail);
    container.addEventListener('click', (e) => {
        if (e.target === container) closeTraceDetail();
    });

    let activeTab = 'spans';
    const tabs = container.querySelectorAll('.pk-tab');
    const tabContent = container.querySelector('#pk-tab-content');

    tabs.forEach(tab => {
        tab.addEventListener('click', () => {
            tabs.forEach(t => t.setAttribute('aria-selected', 'false'));
            tab.setAttribute('aria-selected', 'true');
            activeTab = tab.dataset.tab;
            renderTabContent(tabContent, activeTab, trace, spanNames);
        });
    });

    renderTabContent(tabContent, activeTab, trace, spanNames);

    // ESC key to close; closeTraceDetail removes the listener however
    // the overlay is dismissed (buttons, overlay click, ESC)
    if (escHandler) {
        document.removeEventListener('keydown', escHandler);
    }
    escHandler = (e) => {
        if (e.key === 'Escape') {
            closeTraceDetail();
        }
    };
    document.addEventListener('keydown', escHandler);

    // Move focus into the dialog. No single interior control is the obvious "first" one
    // given the tab strip + header controls, so the dialog itself (a real ARIA APG
    // fallback) takes focus; closeTraceDetail() restores it to the invoker.
    container.focus();
}

function renderTabContent(container, tab, trace, spanNames) {
    switch (tab) {
        case 'request': renderRequest(container, trace); break;
        case 'spans': renderSpans(container, trace, spanNames); break;
        case 'queries': renderQueries(container, trace); break;
        case 'logs': renderLogs(container, trace, spanNames); break;
    }
}

function renderSpans(container, trace, spanNames) {
    const totalDuration = trace.durationMs || 1;
    const traceStart = trace.startTimeMs || 0;
    const traceId = trace.traceId || '';
    const allTraceLogs = trace.logs || [];
    const markers = [0, 0.25, 0.5, 0.75, 1].map(p => Math.round(totalDuration * p) + 'ms');

    let html = '<div class="pk-gantt">';
    html += '<div class="pk-gantt-header">';
    html += '<div class="pk-gantt-header-name">Span</div>';
    html += '<div class="pk-gantt-header-timeline">' + markers.map(m => `<span>${m}</span>`).join('') + '</div>';
    html += '<div style="width:60px"></div>';
    html += '</div>';
    html += '<div id="pk-gantt-rows"></div>';
    html += '</div>';
    container.innerHTML = html;

    const rowsContainer = container.querySelector('#pk-gantt-rows');
    renderSpanRows(rowsContainer, trace.rootSpan, 0, traceStart, totalDuration, null);

    // Add click handlers for expand/collapse
    rowsContainer.addEventListener('click', (e) => {
        // Handle logs toggle clicks
        const logsToggle = e.target.closest('.pk-span-logs-toggle');
        if (logsToggle) {
            const spanId = logsToggle.dataset.spanId;
            const logsBase64 = logsToggle.dataset.logs;
            // Decode base64 JSON (handles UTF-8 properly)
            const spanLogs = logsBase64 ? JSON.parse(decodeURIComponent(escape(atob(logsBase64)))) : [];
            showSpanLogsPopup(container, traceId, spanId, spanLogs, allTraceLogs, spanNames);
            return;
        }

        // Handle SQL toggle clicks
        const sqlToggle = e.target.closest('.pk-span-query-toggle');
        if (sqlToggle) {
            const spanId = sqlToggle.dataset.spanId;
            const queryDetail = rowsContainer.querySelector(`.pk-span-query-detail[data-span-id="${spanId}"]`);
            if (queryDetail) {
                queryDetail.classList.toggle('expanded');
                sqlToggle.title = queryDetail.classList.contains('expanded') ? 'Hide SQL' : 'Show SQL';
            }
            return;
        }

        // Handle row expand/collapse
        const toggle = e.target.closest('.pk-gantt-toggle');
        if (!toggle) return;
        const row = toggle.closest('.pk-gantt-row');
        const spanId = row.dataset.spanId;
        const isCollapsed = toggle.textContent === '+';

        toggle.textContent = isCollapsed ? '-' : '+';

        // Show/hide descendant rows
        let sibling = row.nextElementSibling;
        const rowDepth = parseInt(row.dataset.depth);
        while (sibling && parseInt(sibling.dataset.depth) > rowDepth) {
            if (isCollapsed) {
                sibling.style.display = '';
                // If this row has a collapsed toggle, skip its children
                const sibToggle = sibling.querySelector('.pk-gantt-toggle');
                if (sibToggle && sibToggle.textContent === '+') {
                    const sibDepth = parseInt(sibling.dataset.depth);
                    sibling = sibling.nextElementSibling;
                    while (sibling && parseInt(sibling.dataset.depth) > sibDepth) {
                        sibling = sibling.nextElementSibling;
                    }
                    continue;
                }
            } else {
                sibling.style.display = 'none';
            }
            sibling = sibling.nextElementSibling;
        }
    });
}

/**
 * Renders log rows. Pass showSpanColumn when rows come from more than one span,
 * so the span id is worth a column of its own.
 */
function renderLogRows(logs, {showSpanColumn = false, spanNames} = {}) {
    return logs.map(log => {
        const spanId = log.spanId || '';
        const spanCell = showSpanColumn
            ? `<span class="pk-log__span" data-span-id="${escapeHtml(spanId)}" title="${escapeHtml(spanId)}">`
              + `${escapeHtml(spanNames?.get(spanId) || spanId)}</span>`
            : '';
        return `<div class="pk-log" data-level="${escapeHtml(log.level)}" data-span-id="${escapeHtml(spanId)}">`
             + `<span class="pk-log__time">${escapeHtml(formatTimeOfDay(log.timestamp))}</span>`
             + spanCell
             + `<span class="pk-log__level pk-log__level--${escapeHtml(String(log.level).toLowerCase())}">${escapeHtml(log.level)}</span>`
             + `<span class="pk-log__message">${escapeHtml(log.message)}</span>`
             + `</div>`;
    }).join('');
}

function showSpanLogsPopup(container, traceId, initialSpanId, initialSpanLogs, allTraceLogs, spanNames) {
    // Find popup in the trace container (parent of tab content)
    const traceContainer = container.closest('.pk-overlay__container');
    const popup = traceContainer ? traceContainer.querySelector('#pk-logs-popup') : null;
    if (!popup) return;

    // Renders either a single span's logs (with a "show all" link) or every log in the
    // trace (with a span column, each cell re-entering this function filtered to that span).
    function renderPopupView(showAllLogs, spanId, logs) {
        if (logs.length === 0) {
            popup.classList.add('hidden');
            return;
        }

        const titleHtml = showAllLogs
            ? `Logs for Trace <code>${escapeHtml(traceId)}</code>`
            : `Logs for Span <code>${escapeHtml(spanId)}</code> (Part of trace <code>${escapeHtml(traceId)}</code>). `
              + `<span class="pk-logs-popup-link" id="pk-show-all-logs">Show logs for all spans.</span>`;

        popup.innerHTML = `
            <div class="pk-logs-popup-header">
                <button class="pk-logs-popup-back" title="Back">&#8592;</button>
                <span class="pk-logs-popup-title">${titleHtml}</span>
                <button class="pk-logs-popup-close" title="Close">&times;</button>
            </div>
            <div class="pk-logs-popup-content">${renderLogRows(logs, {showSpanColumn: showAllLogs, spanNames})}</div>
        `;
        popup.classList.remove('hidden');

        // Close handlers (both back and close buttons)
        const closePopup = () => popup.classList.add('hidden');
        popup.querySelector('.pk-logs-popup-back').addEventListener('click', closePopup);
        popup.querySelector('.pk-logs-popup-close').addEventListener('click', closePopup);

        // "Show logs for all spans" link handler
        const showAllLink = popup.querySelector('#pk-show-all-logs');
        if (showAllLink) {
            showAllLink.addEventListener('click', () => renderPopupView(true, null, allTraceLogs));
        }

        // SpanId click handler to filter to that span's logs
        if (showAllLogs) {
            popup.querySelectorAll('.pk-log__span').forEach(el => {
                el.addEventListener('click', () => {
                    const clickedSpanId = el.dataset.spanId;
                    if (clickedSpanId) {
                        renderPopupView(false, clickedSpanId, allTraceLogs.filter(l => l.spanId === clickedSpanId));
                    }
                });
            });
        }
    }

    // Click outside to close - bound once; the popup element persists
    // across re-renders while its children are replaced
    if (!popup.dataset.dismissBound) {
        popup.dataset.dismissBound = 'true';
        popup.addEventListener('click', (e) => {
            if (e.target === popup) {
                popup.classList.add('hidden');
            }
        });
    }

    // Initial render showing span-specific logs
    renderPopupView(false, initialSpanId, initialSpanLogs);
}

function renderSpanRows(container, span, depth, traceStart, totalDuration, parentId) {
    if (!span) return;

    const indent = depth * 20;
    const spanStart = span.startTimeMs || traceStart;
    const spanDuration = span.durationMs || 0;
    const left = Math.max(0, ((spanStart - traceStart) / totalDuration) * 100);
    const width = Math.max((spanDuration / totalDuration) * 100, 0.5);
    const kindRaw = span.kind || 'internal';
    const kind = kindRaw.toLowerCase();
    const hasError = span.status === 'ERROR' || span.errorMessage;
    const hasChildren = span.children && span.children.length > 0;
    const events = span.events || [];
    const tags = span.tags || {};

    // Check for query spans and result-set spans
    const spanName = (span.name || '').toLowerCase();
    const isResultSetSpan = spanName === 'result-set' || spanName.includes('result-set');
    const queryTags = Object.entries(tags).filter(([k]) => k.startsWith('jdbc.query'));
    const hasQuery = queryTags.length > 0;
    const rowCount = tags['jdbc.row-count'];

    // Check for logs (now attached to span by backend)
    const spanLogs = span.logs || [];
    const hasLogs = spanLogs.length > 0;

    const row = document.createElement('div');
    row.className = 'pk-gantt-row';
    row.dataset.spanId = span.spanId;
    row.dataset.depth = depth;
    if (parentId) row.dataset.parentId = parentId;

    let nameHtml = `<div class="pk-gantt-name" style="padding-left: ${indent}px">`;
    if (hasChildren) {
        nameHtml += `<span class="pk-gantt-toggle">-</span>`;
    } else {
        nameHtml += `<span style="width:16px"></span>`;
    }
    if (kind !== 'internal' && kind !== 'unknown' && kind !== 'null') {
        nameHtml += `<span class="pk-gantt-kind ${kind}">${kind}</span>`;
    }
    nameHtml += `<span class="pk-gantt-name-text" title="${escapeHtml(span.name || 'unknown')}">${escapeHtml(span.name || 'unknown')}</span>`;

    // Add row count badge for result-set spans
    if (isResultSetSpan && rowCount !== undefined) {
        nameHtml += `<span class="pk-span-row-count">${escapeHtml(String(rowCount))} rows</span>`;
    }

    // Add query toggle for query spans with SQL
    if (hasQuery) {
        nameHtml += `<span class="pk-span-query-toggle" data-span-id="${span.spanId}" title="Show SQL">&#128196;</span>`;
    }

    // Add logs toggle for spans with logs
    if (hasLogs) {
        // Store logs as base64-encoded JSON to avoid HTML attribute escaping issues
        const logsBase64 = btoa(unescape(encodeURIComponent(JSON.stringify(spanLogs))));
        nameHtml += `<span class="pk-span-logs-toggle" data-span-id="${span.spanId}" data-logs="${logsBase64}">${spanLogs.length} logs</span>`;
    }

    nameHtml += `</div>`;

    // Build track HTML with bar and event markers
    let trackHtml = `<div class="pk-gantt-track">`;
    trackHtml += `<div class="pk-gantt-bar kind-${kind}${hasError ? ' has-error' : ''}" style="left: ${left}%; width: ${width}%"></div>`;

    // Add event markers on the timeline
    events.forEach(event => {
        if (event.timestamp) {
            const eventTimeMs = new Date(event.timestamp).getTime();
            // Position relative to the entire trace timeline
            const eventLeft = Math.max(0, Math.min(100, ((eventTimeMs - traceStart) / totalDuration) * 100));
            trackHtml += `<div class="pk-gantt-event-marker" style="left: ${eventLeft}%"><span class="pk-gantt-event-tooltip">${escapeHtml(event.name)}</span></div>`;
        }
    });
    trackHtml += `</div>`;

    row.innerHTML = nameHtml + trackHtml + `<span class="pk-gantt-duration">${spanDuration}ms</span>`;

    container.appendChild(row);

    // Add query detail row (hidden by default) for query spans
    if (hasQuery) {
        const queryDetail = document.createElement('div');
        queryDetail.className = 'pk-span-query-detail';
        queryDetail.dataset.spanId = span.spanId;
        // depth + 1: counts as collapsible content of this span's row, so the
        // expand/collapse walker (which stops at depth <= row depth) passes it
        queryDetail.dataset.depth = depth + 1;
        queryDetail.style.marginLeft = (indent + 20) + 'px';

        let queryHtml = '';
        queryTags.forEach(([key, value]) => {
            const label = key.replace('jdbc.query', 'Query').replace('[', ' ').replace(']', '');
            queryHtml += `<div class="pk-query-label">${escapeHtml(label)}</div>`;
            queryHtml += `<div class="pk-query-text">${escapeHtml(value)}</div>`;
        });
        queryDetail.innerHTML = queryHtml;
        container.appendChild(queryDetail);
    }

    // Add tags row if present (events are now shown as markers on the timeline)
    const tagEntries = Object.entries(tags).filter(([k]) => !k.startsWith('jdbc.query'));
    const hasTags = tagEntries.length > 0;

    if (hasTags) {
        const badgesRow = document.createElement('div');
        badgesRow.className = 'pk-gantt-badges';
        badgesRow.style.paddingLeft = (indent + 20) + 'px';
        badgesRow.dataset.depth = depth + 1;
        if (parentId) badgesRow.dataset.parentId = parentId;

        let badgesHtml = '';
        // Render all tags
        tagEntries.forEach(([key, value]) => {
            const shortKey = key.split('.').pop();
            const shortVal = String(value).length > 50 ? String(value).substring(0, 50) + '...' : String(value);
            badgesHtml += `<span class="pk-tag-badge" title="${escapeHtml(key)}: ${escapeHtml(value)}"><span class="key">${escapeHtml(shortKey)}</span>=<span class="value">${escapeHtml(shortVal)}</span></span>`;
        });

        badgesRow.innerHTML = badgesHtml;
        container.appendChild(badgesRow);
    }

    if (hasChildren) {
        span.children.forEach(child => {
            renderSpanRows(container, child, depth + 1, traceStart, totalDuration, span.spanId);
        });
    }
}

function renderRequest(container, trace) {
    const httpExchange = trace.httpExchange;
    const req = httpExchange?.request;
    const res = httpExchange?.response;

    // Build sub-tab navigation - reuses the shared .pk-tabs/.pk-tab primitive, scoped
    // to margin-bottom by trace-detail.css's ".pk-overlay__content .pk-tabs" rule.
    let html = '<div class="pk-tabs" role="tablist">';
    html += '<button type="button" class="pk-tab" role="tab" data-subtab="overview" aria-selected="true">Overview</button>';
    html += '<button type="button" class="pk-tab" role="tab" data-subtab="request-headers" aria-selected="false">Request Headers</button>';
    html += '<button type="button" class="pk-tab" role="tab" data-subtab="response-headers" aria-selected="false">Response Headers</button>';
    html += '</div>';
    html += '<div id="pk-request-subtab-content"></div>';

    container.innerHTML = html;

    // Add sub-tab click handlers
    const subtabs = container.querySelectorAll('.pk-tab');
    const subtabContent = container.querySelector('#pk-request-subtab-content');

    subtabs.forEach(tab => {
        tab.addEventListener('click', () => {
            subtabs.forEach(t => t.setAttribute('aria-selected', 'false'));
            tab.setAttribute('aria-selected', 'true');
            renderRequestSubtab(subtabContent, tab.dataset.subtab, req, res, trace);
        });
    });

    // Render default sub-tab
    renderRequestSubtab(subtabContent, 'overview', req, res, trace);
}

function renderRequestSubtab(container, subtab, req, res, trace) {
    switch (subtab) {
        case 'overview': renderRequestOverview(container, req, res, trace); break;
        case 'request-headers': renderRequestHeaders(container, req); break;
        case 'response-headers': renderResponseHeaders(container, res); break;
    }
}

function renderRequestOverview(container, req, res, trace) {
    let html = '';

    // Request info table
    html += '<div class="pk-request-section">';
    html += '<h3>Request</h3>';
    html += '<table class="pk-request-table">';
    html += `<tr><td>Method</td><td>${escapeHtml(req?.method || '-')}</td></tr>`;
    html += `<tr><td>Path</td><td>${escapeHtml(req?.path || '-')}</td></tr>`;
    if (req?.query) {
        html += `<tr><td>Query String</td><td>${escapeHtml(req.query)}</td></tr>`;
    }
    html += `<tr><td>Status</td><td>${escapeHtml(String(res?.status || '-'))}</td></tr>`;
    const contentType = req?.headers?.['content-type'] || req?.headers?.['Content-Type'] || '-';
    html += `<tr><td>Content-Type</td><td>${escapeHtml(contentType)}</td></tr>`;
    html += `<tr><td>Duration</td><td>${trace.durationMs || '-'}ms</td></tr>`;
    html += '</table></div>';

    // Controller info (API returns 'class' and 'method' fields)
    if (req?.controller?.class || req?.controller?.method) {
        html += '<div class="pk-request-section">';
        html += '<h3>Controller</h3>';
        html += `<div class="pk-controller-info">${escapeHtml(req.controller.class || 'Unknown')}.${escapeHtml(req.controller.method || 'unknown')}()</div>`;
        html += '</div>';
    }

    // Query Parameters
    const queryParams = req?.params?.query || {};
    if (Object.keys(queryParams).length > 0) {
        html += '<div class="pk-request-section">';
        html += '<h3>Query Parameters</h3>';
        html += '<table class="pk-request-table">';
        Object.entries(queryParams).sort().forEach(([k, v]) => {
            const displayValue = Array.isArray(v) ? v.join(', ') : v;
            html += `<tr><td>${escapeHtml(k)}</td><td>${escapeHtml(displayValue)}</td></tr>`;
        });
        html += '</table></div>';
    }

    // Form Parameters
    const formParams = req?.params?.form || {};
    if (Object.keys(formParams).length > 0) {
        html += '<div class="pk-request-section">';
        html += '<h3>Form Parameters</h3>';
        html += '<table class="pk-request-table">';
        Object.entries(formParams).sort().forEach(([k, v]) => {
            const displayValue = Array.isArray(v) ? v.join(', ') : v;
            html += `<tr><td>${escapeHtml(k)}</td><td>${escapeHtml(displayValue)}</td></tr>`;
        });
        html += '</table></div>';
    }

    // Uploaded Files
    const files = req?.params?.upload || [];
    if (files.length > 0) {
        html += '<div class="pk-request-section">';
        html += '<h3>Uploaded Files</h3>';
        html += '<table class="pk-request-table">';
        files.forEach(file => {
            const filename = file.originalFilename || file.name || 'unknown';
            html += `<tr><td>${escapeHtml(filename)}</td><td>${escapeHtml(file.contentType || '-')} (${escapeHtml(String(file.size || 0))} bytes)</td></tr>`;
        });
        html += '</table></div>';
    }

    // Request Body
    if (req?.body?.content) {
        html += '<div class="pk-request-section">';
        html += '<h3>Request Body' + (req.body.truncated ? ' <span class="pk-request-masked">(truncated)</span>' : '') + '</h3>';
        html += `<div class="pk-query-sql">${escapeHtml(req.body.content)}</div>`;
        html += '</div>';
    }

    container.innerHTML = html || '<div class="pk-empty">No request details available</div>';
}

function renderRequestHeaders(container, req) {
    let html = '<div class="pk-request-section">';
    html += '<h3>Request Headers</h3>';
    html += '<table class="pk-request-table">';
    const reqHeaders = req?.headers || {};
    if (Object.keys(reqHeaders).length > 0) {
        Object.entries(reqHeaders).sort().forEach(([k, v]) => {
            const isMasked = v === '********';
            html += `<tr><td>${escapeHtml(k)}</td><td class="${isMasked ? 'pk-request-masked' : ''}">${escapeHtml(v)}</td></tr>`;
        });
    } else {
        html += '<tr><td colspan="2" class="pk-request-masked">No headers captured</td></tr>';
    }
    html += '</table></div>';
    container.innerHTML = html;
}

function renderResponseHeaders(container, res) {
    let html = '<div class="pk-request-section">';
    html += '<h3>Response Headers</h3>';
    html += '<table class="pk-request-table">';
    const resHeaders = res?.headers || {};
    if (Object.keys(resHeaders).length > 0) {
        Object.entries(resHeaders).sort().forEach(([k, v]) => {
            html += `<tr><td>${escapeHtml(k)}</td><td>${escapeHtml(v)}</td></tr>`;
        });
    } else {
        html += '<tr><td colspan="2" class="pk-request-masked">No headers captured</td></tr>';
    }
    html += '</table></div>';
    container.innerHTML = html;
}

function renderQueries(container, trace) {
    const queries = trace.queries || [];

    if (queries.length === 0) {
        container.innerHTML = '<div class="pk-empty">No database queries recorded</div>';
        return;
    }

    let html = '';
    queries.forEach((query, idx) => {
        const sql = query.sql || 'Unknown query';
        const duration = query.durationMs || 0;
        const durationClass = durationSeverity(duration);
        const system = query.dbSystem || 'SQL';
        const rowCount = query.rowCount;

        html += '<div class="pk-query-item">';
        html += '<div class="pk-query-header">';
        html += `<span class="pk-query-system">${idx + 1}. ${escapeHtml(system.toUpperCase())}</span>`;
        html += '<span class="pk-query-meta">';
        html += `<span class="pk-query__duration${durationClass ? ' pk-query__duration--' + durationClass : ''}">${duration}ms${duration > 100 ? ' SLOW' : ''}</span>`;
        if (rowCount !== null && rowCount !== undefined) {
            html += `<span class="pk-query-rows">${escapeHtml(String(rowCount))} rows</span>`;
        }
        html += '</span>';
        html += '</div>';
        html += `<div class="pk-query-sql">${escapeHtml(sql)}</div>`;
        html += '</div>';
    });

    container.innerHTML = html;
}

function renderLogs(container, trace, spanNames) {
    const logs = trace.logs || [];

    if (logs.length === 0) {
        container.innerHTML = '<div class="pk-empty">No logs recorded for this trace</div>';
        return;
    }

    let currentSpanFilter = null;

    function render() {
        let html = '<div class="pk-logs-filter">';
        html += '<input type="text" placeholder="Filter logs..." id="pk-log-filter">';
        html += '<select id="pk-log-level"><option value="">All Levels</option><option>ERROR</option><option>WARN</option><option>INFO</option><option>DEBUG</option></select>';
        if (currentSpanFilter) {
            const spanName = spanNames.get(currentSpanFilter) || currentSpanFilter;
            const shortName = spanName.length > 20 ? spanName.substring(0, 20) + '...' : spanName;
            html += `<span class="pk-logs-filter-span">Span: ${escapeHtml(shortName)} <span class="pk-logs-filter-span-clear" id="pk-clear-span-filter">&times;</span></span>`;
        }
        html += '</div>';
        html += `<div id="pk-logs-list">${renderLogRows(logs, {showSpanColumn: true, spanNames})}</div>`;
        container.innerHTML = html;

        // Filter controls
        const filterInput = container.querySelector('#pk-log-filter');
        const levelSelect = container.querySelector('#pk-log-level');
        const clearSpanFilter = container.querySelector('#pk-clear-span-filter');

        function applyFilters() {
            const text = filterInput.value.toLowerCase();
            const level = levelSelect.value;
            container.querySelectorAll('.pk-log').forEach(item => {
                const message = item.querySelector('.pk-log__message').textContent.toLowerCase();
                const itemLevel = item.dataset.level;
                const itemSpanId = item.dataset.spanId;
                const matchText = !text || message.includes(text);
                const matchLevel = !level || itemLevel === level;
                const matchSpan = !currentSpanFilter || itemSpanId === currentSpanFilter;
                item.classList.toggle('pk-log--hidden', !(matchText && matchLevel && matchSpan));
            });
        }

        // Establishes the initial visibility (a span filter set before this render, if any)
        applyFilters();

        filterInput.addEventListener('input', applyFilters);
        levelSelect.addEventListener('change', applyFilters);

        // Clear span filter
        if (clearSpanFilter) {
            clearSpanFilter.addEventListener('click', () => {
                currentSpanFilter = null;
                render();
            });
        }

        // Span click to filter
        container.querySelectorAll('.pk-log__span').forEach(el => {
            el.addEventListener('click', () => {
                const spanId = el.dataset.spanId;
                if (spanId) {
                    currentSpanFilter = spanId;
                    render();
                }
            });
        });
    }

    render();
}

function countSpans(span) {
    if (!span) return 0;
    let count = 1;
    (span.children || []).forEach(child => {
        count += countSpans(child);
    });
    return count;
}

// dashboard/peekaboot.js is a classic script with no imports (see file header); it calls
// this global directly. Task 13 converts peekaboot.js to a module and removes this.
window.PeekabootTraceDetail = {
    open: openTraceDetail,
    close: closeTraceDetail
};

export const open = openTraceDetail;
export const close = closeTraceDetail;

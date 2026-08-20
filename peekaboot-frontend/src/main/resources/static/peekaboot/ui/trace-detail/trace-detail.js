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
 *
 * This file holds only the shell (open/close, the chrome, tab wiring); each tab's
 * rendering lives in its own module under tabs/ - adding a tab means adding one file.
 */
import {escapeHtml} from '../shared/markup.js';
import {durationSeverity} from '../shared/severity.js';
import {rootActionIcon} from '../shared/root-actions.js';
import {resolveTheme, applyTheme, watchTheme} from '../shared/theme.js';
import {attachSharedStyles} from '../shared/shadow-styles.js';
import {createClient} from '../shared/api.js';
import * as request from './tabs/request.js';
import * as spans from './tabs/spans.js';
import * as queries from './tabs/queries.js';
import * as logs from './tabs/logs.js';

const TABS = [
    {id: 'request', label: 'Request', render: request.render},
    {id: 'spans',   label: 'Spans',   render: spans.render},
    {id: 'queries', label: 'Queries', render: queries.render, count: t => (t.queries || []).length},
    {id: 'logs',    label: 'Logs',    render: logs.render,    count: t => (t.logs || []).length}
];

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

export function openTraceDetail(traceId, options = {}) {
    // Captured before closeTraceDetail() below, which restores focus to whatever a prior
    // open() left behind - that would otherwise clobber the element we want to return to.
    const invokingElement = deepActiveElement();
    closeTraceDetail();

    previouslyFocusedElement = invokingElement;
    onCloseCallback = options.onClose || null;
    const onSelectSpan = options.onSelectSpan || null;
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

    fetchAndRender(content, traceId, {basePath, session, styleReady, onSelectSpan});
}

export function closeTraceDetail() {
    // Invalidates any fetchAndRender() still in flight from the overlay just removed, so
    // it cannot re-render into (or re-register an ESC listener for) a detached node.
    currentSession += 1;
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

async function fetchAndRender(content, traceId, {basePath, session, styleReady, onSelectSpan}) {
    const client = createClient({basePath});
    try {
        const [trace] = await Promise.all([client.get(`/api/traces/${traceId}/insights`), styleReady]);
        // A newer open() superseded this one while the request was in flight.
        if (session !== currentSession) return;
        render(content, trace, {basePath, client, onSelectSpan});
    } catch (error) {
        if (session !== currentSession) return;
        // Not a full dialog (no focus-in, no ESC) - consistent with the loading state,
        // which never was one either - but this screen is reachable and has a working
        // control, so it needs a role and a name at minimum for a screen-reader user to
        // know what landed on the page.
        content.innerHTML = `<div class="pk-overlay" role="alertdialog" aria-modal="true" aria-label="Failed to load trace">`
            + `<div class="pk-overlay__error">`
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

function render(content, trace, context) {
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
            renderTabContent(tabContent, activeTab, trace, context);
        });
    });

    renderTabContent(tabContent, activeTab, trace, context);

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

function renderTabContent(container, tabId, trace, context) {
    const tab = TABS.find(t => t.id === tabId);
    if (tab) tab.render(container, trace, context);
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

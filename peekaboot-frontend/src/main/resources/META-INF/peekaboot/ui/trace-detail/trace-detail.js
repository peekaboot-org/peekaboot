/**
 * Peekaboot trace-detail overlay.
 *
 * Loaded two ways:
 *  - lazily, via toolbar.js's `await import('../trace-detail/trace-detail.js')`;
 *  - eagerly, via dashboard/main.js's static import.
 * Both call the named `openTraceDetail`/`closeTraceDetail` exports.
 *
 * This file holds only the shell (open/close, the chrome, tab wiring); each tab's
 * rendering lives in its own module under tabs/ - adding a tab means adding one file.
 */
import {el, button} from '../shared/dom.js';
import {formatCount, formatDurationMs} from '../shared/format.js';
import {severityClass} from '../shared/severity.js';
import {statusLabel, statusVariant} from '../shared/http-status.js';
import {rootActionIcon, rootActionLabel} from '../shared/root-actions.js';
import {bindTheme} from '../shared/theme.js';
import {attachSharedStyles} from '../shared/shadow-styles.js';
import {registerBundledFonts} from '../shared/fonts.js';
import {createClient, BASE_PATH} from '../shared/api.js';
import {badge, tabStrip} from '../shared/components.js';
import {copyableId, bindCopyables} from '../shared/copyable.js';
import {truncatedBadge} from '../shared/trace-stats.js';
import * as request from './tabs/request.js';
import * as spans from './tabs/spans.js';
import * as queries from './tabs/queries.js';
import * as logs from './tabs/logs.js';

// label/count feed the tab strip built in wireTabs() below - label becomes each
// button's text, count (when present) the small badge next to it. The counts are the
// backend's TraceTabSummary, the same numbers the Traces tab and the toolbar show.
const TABS = [
    {id: 'request', label: 'Request', render: request.render},
    {id: 'spans',   label: 'Spans',   render: spans.render,   count: t => t.summary.spans.count},
    {id: 'queries', label: 'Queries', render: queries.render, count: t => t.summary.queries.count},
    {id: 'logs',    label: 'Logs',    render: logs.render,    count: t => t.summary.logs.count}
];

// How long a cross-link jump's highlight stays on its target (see jumpToElement).
const JUMP_FLASH_MS = 2000;

let escHandler = null;
let onCloseCallback = null;
let themeUnwatch = null;
let previouslyFocusedElement = null;
let currentSession = 0;
let inertedSiblings = [];

/**
 * Makes everything except the overlay host inert while the dialog is open.
 *
 * aria-modal="true" already tells assistive tech to ignore the rest of the page, but it
 * does nothing for a sighted keyboard user: Tab would still walk out of the dialog into
 * the page behind it. `inert` removes those subtrees from focus order and the a11y tree
 * for real, which is a focus trap without hand-rolled Tab cycling - and it correctly
 * covers the dev toolbar, which is a sibling host on document.body, not part of the page.
 */
function setBackgroundInert(overlayHost) {
    inertedSiblings = Array.from(document.body.children)
            .filter(child => child !== overlayHost && !child.inert);
    inertedSiblings.forEach(child => { child.inert = true; });
}

function releaseBackgroundInert() {
    inertedSiblings.forEach(child => { child.inert = false; });
    inertedSiblings = [];
}

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

/**
 * options: basePath (the toolbar's server-provided one; the dashboard's own by default),
 * urlState (see main.js's buildTraceUrlState), onClose, and the dashboard's display
 * settings - locale, timeZone and the /api/features payload - which the toolbar has no
 * way to supply and which then fall back to the browser locale and the shared default
 * thresholds.
 */
export function openTraceDetail(traceId, options = {}) {
    // Captured before closeTraceDetail() below, which restores focus to whatever a prior
    // open() left behind - that would otherwise clobber the element we want to return to.
    const invokingElement = deepActiveElement();
    closeTraceDetail();

    previouslyFocusedElement = invokingElement;
    onCloseCallback = options.onClose || null;
    currentSession += 1;
    const session = currentSession;

    const basePath = options.basePath || BASE_PATH;
    registerBundledFonts(basePath);

    const overlayHost = document.createElement('div');
    overlayHost.id = 'peekaboot-trace-overlay';
    // Exposes which trace (if any) is open as plain DOM state, so callers on either of
    // this module's two entry points (main.js's hash routing, traces.js's click-to-open)
    // can check "is this trace already open?" without either of them having to track it.
    overlayHost.dataset.traceId = traceId;
    // An inline style, not a `:host` rule in trace-detail.css: attachSharedStyles() links
    // that sheet asynchronously, and a CSS-only rule would leave the host collapsed to
    // zero size until it lands. The CSSOM write applies the instant the element exists.
    //
    // The z-index is the toolbar's own (toolbar.css `:host`) and is not optional: this host
    // is appended to document.body, so without one it stacks at `auto` and any host page
    // chrome that stacks above the page flow - Bulma's .navbar at 30, Bootstrap's
    // .fixed-top at 1030 - paints over the overlay and takes its clicks. Equal to the
    // toolbar's rather than above it: appended later, it already wins the tie.
    overlayHost.style.cssText = 'position:fixed;inset:0;z-index:2147483647;';
    document.body.appendChild(overlayHost);
    setBackgroundInert(overlayHost);
    // Bound here rather than in render(): from this line on the page behind is inert, and a
    // reader waiting out the trace fetch (or looking at the error state) must be able to
    // leave. closeTraceDetail() unbinds it, however the overlay is dismissed.
    escHandler = event => {
        if (event.key === 'Escape') {
            closeTraceDetail();
        }
    };
    document.addEventListener('keydown', escHandler);

    const shadow = overlayHost.attachShadow({mode: 'open'});
    themeUnwatch = bindTheme(overlayHost);
    // attachSharedStyles keeps the host visibility:hidden until its <link> sheets settle;
    // an element under a visibility:hidden ancestor cannot take focus, so the eventual
    // render() -> container.focus() call must wait for this to resolve too, not just the
    // trace fetch - see the Promise.all in fetchAndRender.
    const styleReady = attachSharedStyles(shadow, overlayHost, basePath, `${basePath}/ui/trace-detail/trace-detail.css`);

    const content = document.createElement('div');
    shadow.appendChild(content);
    content.replaceChildren(el('div', {className: 'pk-overlay'},
        el('div', {className: 'pk-overlay__loading', text: 'Loading trace data...'})));

    const display = {locale: options.locale, timeZone: options.timeZone, features: options.features};
    fetchAndRender(content, traceId, {basePath, session, styleReady, urlState: options.urlState, display});
}

export function closeTraceDetail() {
    // Invalidates any fetchAndRender() still in flight from the overlay just removed, so
    // it cannot re-render into a detached node.
    currentSession += 1;
    const existing = document.getElementById('peekaboot-trace-overlay');
    if (existing) {
        existing.remove();
    }
    // Before restoring focus below: the invoker is out in the page, which is still inert
    // at this point, and focus() on an inert element is a no-op.
    releaseBackgroundInert();
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

async function fetchAndRender(content, traceId, {basePath, session, styleReady, urlState, display}) {
    const client = createClient({basePath});
    try {
        const [trace] = await Promise.all([client.get(`/api/traces/${traceId}/insights`), styleReady]);
        // A newer open() superseded this one while the request was in flight.
        if (session !== currentSession) return;
        render(content, trace, urlState, display);
    } catch (error) {
        if (session !== currentSession) return;
        // Not a full dialog (no focus-in) - consistent with the loading state, which never
        // was one either - but this screen is reachable and has a working control, so it
        // needs a role and a name at minimum for a screen-reader user to know what landed
        // on the page. Escape closes it, bound since the open.
        const close = button({className: 'pk-btn', text: 'Close'});
        close.addEventListener('click', closeTraceDetail);
        content.replaceChildren(el('div', {
            className: 'pk-overlay',
            attrs: {role: 'alertdialog', 'aria-modal': 'true', 'aria-label': 'Failed to load trace'}
        }, el('div', {className: 'pk-overlay__error'}, `Failed to load trace: ${error.message}`, el('br'), el('br'), close)));
    }
}

function render(content, trace, urlState, display) {
    // delegated once on the container, which outlives every re-render below
    bindCopyables(content);

    const container = el('div', {
        className: 'pk-overlay',
        attrs: {role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'pk-overlay-title', tabindex: '-1'}
    }, el('div', {className: 'pk-overlay__container'},
        header(trace, display),
        el('div', {className: 'pk-tabs'}),
        el('div', {className: 'pk-overlay__content', attrs: {id: 'pk-tab-content'}})));
    content.replaceChildren(container);

    container.querySelector('.pk-overlay__close').addEventListener('click', closeTraceDetail);

    wireTabs(container, trace, urlState, display);

    // Move focus into the dialog. No single interior control is the obvious "first" one
    // given the tab strip + header controls, so the dialog itself (a real ARIA APG
    // fallback) takes focus; closeTraceDetail() restores it to the invoker.
    container.focus();
}

function header(trace, display) {
    const rootSpan = trace.rootSpan || {};
    const httpExchange = trace.httpExchange || {};
    const req = httpExchange.request || {};
    const res = httpExchange.response || {};
    // Prefer httpExchange data (toolbar-only), then the summary the server read off the
    // root span's tags - it knows every convention's names, this module does not; null
    // (not a placeholder string) for a trace with no HTTP request at all, so the title
    // falls back to the root-action label.
    const summaryRequest = trace.summary?.request || {};
    const method = req.method || summaryRequest.method || null;
    const path = req.path || summaryRequest.path || rootSpan.name || '-';
    const status = res.status || summaryRequest.statusCode;
    const {spans: spanSummary, queries: querySummary, logs: logSummary} = trace.summary;

    const title = el('h2', {className: 'pk-overlay__title', attrs: {id: 'pk-overlay-title'}},
        el('span', {className: 'pk-overlay__title-icon', text: rootActionIcon(trace.rootActionType), attrs: {'aria-hidden': 'true'}}),
        el('span', {className: 'pk-overlay__title-method', text: method ?? rootActionLabel(trace.rootActionType)}),
        el('span', {className: 'pk-overlay__title-path', text: path, title: path}),
        el('span', {className: 'pk-overlay__title-traceid'}, copyableId(trace.traceId, {label: 'traceId'})));

    // trace.slow is the backend's verdict, the same flag the Traces tab's badge reads:
    // some span carries a SLOW or VERY_SLOW issue. The span thresholds applied to the
    // trace's total would call a 120 ms request slow here while the list did not.
    const meta = el('div', {className: 'pk-overlay__meta'},
        el('span', {
            className: 'pk-overlay__duration' + (trace.slow ? ` ${severityClass('slow')}` : ''),
            text: formatDurationMs(trace.durationMs)
        }),
        badge(statusLabel(status), statusVariant(status)),
        trace.slow ? badge('SLOW', 'warn') : null,
        el('span', {text: formatCount(spanSummary.count, 'span')}),
        el('span', {text: formatCount(querySummary.count, 'query', 'queries')}),
        el('span', {text: formatCount(logSummary.count, 'log')}),
        trace.truncated ? truncatedBadge() : null);

    return el('div', {className: 'pk-overlay__header'},
        el('div', {className: 'pk-overlay__header-main'}, title, meta),
        button({className: 'pk-unbutton pk-overlay__close', text: '×', title: 'Close', attrs: {'aria-label': 'Close trace details'}}));
}

/**
 * Builds the tab strip and renders the initial tab. The three cross-link jumps ride
 * along on the view object every tab receives, so the tabs can reach them without a
 * hand-off channel of their own - as do the display settings (locale, timeZone,
 * features) the tabs format and colour by.
 */
function wireTabs(container, trace, urlState, display) {
    const tabContent = container.querySelector('#pk-tab-content');

    // Deep-linked into a specific subview (e.g. "#traces/<id>/logs?level=WARN") when the
    // hash names one of this overlay's own tabs; falls back to Spans for a bare
    // "#traces/<id>" link and for callers with no urlState at all (the dev toolbar).
    const initialTab = TABS.some(t => t.id === urlState?.initial?.subview) ? urlState.initial.subview : 'spans';

    // Params are scoped to the deepest view (this tab), so a tab switch always starts
    // that tab's filters empty - see url-state.js's push/replace rule. Only the tab
    // restored from the URL at open time seeds its filters from urlState.initial.params.
    const tabView = (tabId, filters) => ({
        ...display,
        filters,
        setFilters: next => urlState?.update(tabId, next),
        goToSpanLogs,
        goToSpan,
        goToQuery
    });

    // tabStrip's click listener re-fires onSelect even when the clicked tab is already
    // selected (it only tracks aria-selected, not "did the tab actually change") - without
    // this guard, re-clicking the active tab would wipe its own filters/URL for nothing.
    let activeTabId = initialTab;

    /**
     * The Spans tab's "N logs" toggle: switches to the Logs tab with that span's filter
     * seeded - the same `span` filter a "?span=..." deep link restores, hence the
     * urlState.update. `focus: true` because the toggle just clicked belongs to the
     * markup the switch replaces; focus would otherwise fall back to the shadow host.
     */
    let tabApi;
    function goToSpanLogs(spanId) {
        activeTabId = 'logs';
        tabApi.select('logs', {silent: true, focus: true});
        urlState?.update('logs', {span: spanId});
        renderTabContent(tabContent, 'logs', trace, tabView('logs', {span: spanId}));
    }

    /**
     * Cross-link jumps between the overlay's own tabs (Spans <-> Queries, Logs -> Spans):
     * switch the tab exactly as the strip would (silent select, urlState.update with empty
     * params - the target tab starts unfiltered, same as a manual switch), then scroll to
     * the target row, move keyboard focus onto it (the clicked link's own markup was just
     * replaced, so focus would otherwise fall back to the shadow host) and mark it with a
     * temporary highlight so the eye lands where focus went.
     */
    function jumpToElement(tabId, selector) {
        activeTabId = tabId;
        tabApi.select(tabId, {silent: true});
        urlState?.update(tabId, {});
        renderTabContent(tabContent, tabId, trace, tabView(tabId, {}));
        const target = tabContent.querySelector(selector);
        if (!target) {
            // nothing to land on (e.g. a truncated trace) - focus the tab button instead
            tabApi.select(tabId, {silent: true, focus: true});
            return;
        }
        target.scrollIntoView({block: 'center'});
        target.tabIndex = -1;
        target.focus({preventScroll: true});
        target.classList.add('pk-jump-flash');
        setTimeout(() => target.classList.remove('pk-jump-flash'), JUMP_FLASH_MS);
    }

    function goToSpan(spanId) {
        jumpToElement('spans', `.pk-gantt-row[data-span-id="${CSS.escape(spanId)}"]`);
    }

    function goToQuery(spanId) {
        jumpToElement('queries', `.pk-query-item[data-span-id="${CSS.escape(spanId)}"]`);
    }

    tabApi = tabStrip(container.querySelector('.pk-tabs'), TABS.map(tab => ({
        id: tab.id,
        label: tab.label,
        count: tab.count ? tab.count(trace) : undefined
    })), {
        onSelect: tabId => {
            if (tabId === activeTabId) return;
            activeTabId = tabId;
            urlState?.update(tabId, {});
            renderTabContent(tabContent, tabId, trace, tabView(tabId, {}));
        },
        initial: initialTab,
        panel: tabContent
    });

    renderTabContent(tabContent, initialTab, trace, tabView(initialTab, urlState?.initial?.params || {}));
}

function renderTabContent(container, tabId, trace, view) {
    const tab = TABS.find(t => t.id === tabId);
    if (tab) tab.render(container, trace, view);
}

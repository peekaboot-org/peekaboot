/**
 * Trace-detail overlay - Spans tab: the gantt chart, its expand/collapse behaviour and each
 * span's three independent panels: attributes (kind, span id, error, tags - opened by the
 * span name or track), a query panel (the statement plus its row count) and a logs panel
 * (that span's own log entries) - each of the latter two opened by its own small button in
 * the row ("N query"/"N logs"), closed until asked for.
 *
 * Each span renders as one entry: its one-line row, then whichever of its three panels exist.
 * Entries are flat siblings carrying their depth, which is what the subtree toggle walks.
 *
 * This module writes only one geometry value of its own: an entry's depth, as the CSS custom
 * property --pk-gantt-depth. The name cell's indent, a panel's margin and the
 * indent guides' width are all calc()'d from it in trace-detail.css, so --pk-gantt-indent and
 * --pk-gantt-toggle there stay the one place those pixel values live. Bar positions and
 * marker offsets have no such shared formula and are written directly. Every one of these
 * writes goes through the CSSOM, never a style attribute in markup: a host page whose CSP
 * omits style-src 'unsafe-inline' drops the attribute form, which would flatten every row to
 * depth 0 and every bar to the left edge.
 */
import {el, button} from '../../shared/dom.js';
import {formatCount, formatDurationMs} from '../../shared/format.js';
import {issueSeverity, severityClass} from '../../shared/severity.js';
import {copyableId} from '../../shared/copyable.js';
import {sqlView} from '../../shared/sql-view.js';
import {logEntry} from '../log-entry.js';

const KIND_LABELS = {server: 'Server', client: 'Client', producer: 'Producer', consumer: 'Consumer', internal: 'Internal'};

export function render(container, trace, context = {}) {
    // "?root=<spanId>" narrows the tab to one subtree. Scoped to this tab like every other
    // tab's params, so a tab round-trip widens it back out again (see url-state.js).
    const scopedRoot = context.filters?.root ? findSpan(trace.rootSpan, context.filters.root) : null;
    const rootSpan = scopedRoot || trace.rootSpan;
    // the 1 keeps a zero-length trace from dividing by zero in every position below
    const totalDuration = (scopedRoot ? subtreeWindowMs(scopedRoot) : trace.durationMs) || 1;
    const traceStart = (scopedRoot ? scopedRoot.startTimeMs : trace.startTimeMs) || 0;
    // the origin is where the trace starts, not a duration anyone measured, so it is
    // spelled out rather than run through formatDurationMs - which calls 0 "<1ms"
    const ticks = ['0ms', ...[0.25, 0.5, 0.75, 1].map(p => formatDurationMs(totalDuration * p))];

    const entries = el('div', {className: 'pk-gantt-rows', attrs: {id: 'pk-gantt-rows'}});
    const allDetailsToggle = button({className: 'pk-btn pk-btn--small pk-gantt-all-details', text: 'Show all details'});
    container.replaceChildren(el('div', {className: 'pk-gantt'},
        el('div', {className: 'pk-gantt-toolbar'}, kindLegend(rootSpan), allDetailsToggle),
        el('div', {className: 'pk-gantt-header'},
            el('div', {className: 'pk-gantt-header__name pk-label', text: 'Span'}),
            el('div', {className: 'pk-gantt-header__timeline'}, ...ticks.map(tick => el('span', {text: tick})))),
        entries));

    const display = {locale: context.locale, timeZone: context.timeZone, logsBySpan: logsBySpan(trace.logs)};
    renderSpanEntries(entries, rootSpan, 0, traceStart, totalDuration, display);

    // Collapsed by default: the subtree's work is not what the caller waited for, so it
    // starts out of the way. Reuses the subtree toggle rather than a second mechanism.
    // Skips the tab's own first entry: when "?root=" scopes the tab to the async entry
    // itself, that entry is what is being rendered, and collapsing it would open the
    // isolated view on nothing.
    const firstEntry = entries.firstElementChild;
    entries.querySelectorAll('.pk-gantt-span--async .pk-gantt-toggle').forEach(toggle => {
        if (toggle.closest('.pk-gantt-span') === firstEntry) return;
        if (toggle.getAttribute('aria-expanded') === 'true') toggleSubtree(toggle);
    });

    allDetailsToggle.addEventListener('click', () => {
        const open = !allDetailsOpen(entries);
        panelToggles(entries).forEach(toggle => setPanelOpen(toggle, open));
        syncAllDetailsToggle(entries, allDetailsToggle);
    });

    entries.addEventListener('click', (e) => {
        const toggle = e.target.closest('.pk-gantt-toggle');
        if (toggle) {
            toggleSubtree(toggle);
            return;
        }

        // The query and logs toggles carry their own aria-controls straight to their own
        // panel; the name button and the track (the row's widest part, an event marker
        // keeps its own hover) share the attributes panel's.
        const directToggle = e.target.closest('.pk-gantt-name__toggle, .pk-span-query-toggle, .pk-span-logs-toggle');
        const trackClick = !directToggle && !e.target.closest('.pk-gantt-event-marker') && e.target.closest('.pk-gantt-track');
        const panelSwitch = directToggle || (trackClick && trackClick.closest('.pk-gantt-span').querySelector('.pk-gantt-name__toggle'));
        if (panelSwitch) {
            setPanelOpen(panelSwitch, panelSwitch.getAttribute('aria-expanded') !== 'true');
            syncAllDetailsToggle(entries, allDetailsToggle);
        }
    });
}

/** Every span's panel-toggle button (attributes, query, logs) under `scope` - not the subtree toggle, which carries no aria-controls. */
function panelToggles(scope) {
    return Array.from(scope.querySelectorAll('.pk-gantt-name [aria-controls]'));
}

/** Opens or closes the panel `toggle` names, and keeps the entry's own open state - the row's hover tint - in step with its panels. */
function setPanelOpen(toggle, open) {
    toggle.setAttribute('aria-expanded', String(open));
    const entry = toggle.closest('.pk-gantt-span');
    entry.querySelector(`#${CSS.escape(toggle.getAttribute('aria-controls'))}`)?.classList.toggle('pk-span-panel--open', open);
    entry.classList.toggle('pk-gantt-span--open', panelToggles(entry).some(t => t.getAttribute('aria-expanded') === 'true'));
}

/** True once every panel that exists, on every span, is open - what "Hide all details" then offers to undo. */
function allDetailsOpen(entries) {
    return !entries.querySelector('.pk-gantt-name [aria-controls][aria-expanded="false"]');
}

/** The label names what the next click does, so it reads true after a panel is opened or closed by hand. */
function syncAllDetailsToggle(entries, allDetailsToggle) {
    allDetailsToggle.textContent = allDetailsOpen(entries) ? 'Hide all details' : 'Show all details';
}

function toggleSubtree(toggle) {
    const expand = toggle.getAttribute('aria-expanded') === 'false';
    toggle.setAttribute('aria-expanded', String(expand));
    toggle.setAttribute('aria-label', expand ? 'Collapse child spans' : 'Expand child spans');
    setSubtreeVisible(toggle.closest('.pk-gantt-span'), expand);
}

/**
 * Shows or hides every entry nested under `entry` - every following entry with a deeper
 * data-depth. Expanding leaves a collapsed descendant's own subtree hidden.
 */
function setSubtreeVisible(entry, expand) {
    const depth = Number(entry.dataset.depth);
    let next = entry.nextElementSibling;
    while (next && Number(next.dataset.depth) > depth) {
        next.style.display = expand ? '' : 'none';
        const collapsed = expand && next.querySelector('.pk-gantt-toggle[aria-expanded="false"]');
        next = collapsed ? nextOutsideSubtree(next) : next.nextElementSibling;
    }
}

function nextOutsideSubtree(entry) {
    const depth = Number(entry.dataset.depth);
    let next = entry.nextElementSibling;
    while (next && Number(next.dataset.depth) > depth) next = next.nextElementSibling;
    return next;
}

function findSpan(span, spanId) {
    if (!span) return null;
    if (span.spanId === spanId) return span;
    for (const child of span.children || []) {
        const found = findSpan(child, spanId);
        if (found) return found;
    }
    return null;
}

/** A subtree's own wall-clock window: a child can outlive its parent, which is why this exists. */
function subtreeWindowMs(span) {
    const end = latestEndMs(span, span.startTimeMs + (span.durationMs || 0));
    return Math.max(end - span.startTimeMs, 0);
}

function latestEndMs(span, latest) {
    let end = Math.max(latest, span.startTimeMs + (span.durationMs || 0));
    for (const child of span.children || []) end = latestEndMs(child, end);
    return end;
}

function spanKind(span) {
    const kind = (span.kind || 'internal').toLowerCase();
    return Object.hasOwn(KIND_LABELS, kind) ? kind : 'internal';
}

/** The kinds this trace has, in KIND_LABELS order: the key to the row dots and bar colours. */
function kindLegend(rootSpan) {
    const present = new Set();
    const collect = span => {
        if (!span) return;
        present.add(spanKind(span));
        (span.children || []).forEach(collect);
    };
    collect(rootSpan);
    return el('ul', {className: 'pk-gantt-legend', attrs: {'aria-label': 'Span kinds'}},
        ...Object.keys(KIND_LABELS).filter(kind => present.has(kind)).map(kind =>
            el('li', {className: `pk-gantt-legend__item pk-gantt-kind--${kind}`}, kindDot(), KIND_LABELS[kind])));
}

function kindDot() {
    return el('span', {className: 'pk-gantt-kind-dot', attrs: {'aria-hidden': 'true'}});
}

function renderSpanEntries(container, span, depth, traceStart, totalDuration, display) {
    if (!span) return;
    const kind = spanKind(span);
    const attrsId = `pk-span-attrs-${span.spanId}`;
    const queryId = `pk-span-query-${span.spanId}`;
    const logsId = `pk-span-logs-${span.spanId}`;
    // the backend's verdict, read once per span: ERROR whenever it recorded an error message
    // or class, and everything that marks the span as erroneous - the name, the bar, its
    // accessible name - reads this one flag rather than re-deriving it.
    const hasError = span.status === 'ERROR';

    const entry = el('div', {className: `pk-gantt-span pk-gantt-kind--${kind}${span.asyncEntry ? ' pk-gantt-span--async' : ''}`});
    entry.dataset.depth = depth;
    entry.style.setProperty('--pk-gantt-depth', depth);

    const row = el('div', {className: 'pk-gantt-row'});
    row.dataset.spanId = span.spanId;
    row.append(
        nameCell(span, kind, attrsId, queryId, logsId, hasError, display.locale),
        track(span, traceStart, totalDuration, hasError),
        durationCell(span, totalDuration));
    const panels = [attrsPanel(span, kind, attrsId), queryPanel(span, queryId, display.locale), logsPanel(span, display, logsId)]
        .filter(panel => panel != null);
    entry.append(row, ...panels);
    container.appendChild(entry);

    // An async subtree is excluded from the trace's duration, so measuring its children
    // against that duration would put them off the end of the chart. They get their own
    // basis - the entry row itself keeps the trace's, or its bar would claim a start time
    // it does not have and report a share its siblings' column cannot be read against.
    const childrenStart = span.asyncEntry ? span.startTimeMs : traceStart;
    const childrenDuration = span.asyncEntry ? (subtreeWindowMs(span) || 1) : totalDuration;
    (span.children || []).forEach(child =>
        renderSpanEntries(container, child, depth + 1, childrenStart, childrenDuration, display));
}

function nameCell(span, kind, attrsId, queryId, logsId, hasError, locale) {
    const hasChildren = span.children && span.children.length > 0;
    const name = span.name || 'unknown';

    const cell = el('div', {className: 'pk-gantt-name'});
    cell.append(hasChildren
        ? button({className: 'pk-unbutton pk-icon-btn pk-gantt-toggle', attrs: {'aria-expanded': 'true', 'aria-label': 'Collapse child spans'}})
        : el('span', {className: 'pk-gantt-toggle-spacer'}));
    cell.append(button({
        className: 'pk-unbutton pk-gantt-name__toggle' + (hasError ? ' pk-gantt-name__toggle--error' : ''),
        title: name,
        attrs: {
            'aria-expanded': 'false',
            'aria-controls': attrsId,
            'aria-label': `${name}, ${kind} span${hasError ? ', error' : ''}${span.asyncEntry ? ', background work' : ''}`
        }
    }, kindDot(), el('span', {className: 'pk-gantt-name__text', text: name})));
    // aria-hidden: the name button's accessible name above already ends in ", error".
    if (hasError) {
        cell.append(el('span', {className: 'pk-span-error-chip', text: 'error', attrs: {'aria-hidden': 'true'}}));
    }
    // aria-hidden: the name button's accessible name above already ends in ", background work".
    if (span.asyncEntry) {
        cell.append(el('span', {
            className: 'pk-span-async-chip',
            text: 'background',
            title: 'Background work, excluded from this trace’s duration',
            attrs: {'aria-hidden': 'true'}
        }));
    }
    // The backend decides what a query span is (DbSpans) and ships its masked statement as
    // span.query - a batch is still one query, same as the Queries tab's own count - and the
    // row count of the result-set span it paired to this one (RowCounts) as span.rowCount.
    if (span.query) {
        cell.append(panelToggleButton('pk-span-query-toggle', queryId, queryLabel(span, locale)));
    }
    const logCount = (span.logs || []).length;
    if (logCount > 0) {
        cell.append(panelToggleButton('pk-span-logs-toggle', logsId, formatCount(logCount, 'log', {locale})));
    }
    return cell;
}

/** "1 query" alone, or with the paired result set's row count - null (unparsed) leaves it off rather than claiming zero. */
function queryLabel(span, locale) {
    const query = formatCount(1, 'query');
    return span.rowCount == null ? query : `${query}, ${formatCount(span.rowCount, 'result row', {locale})}`;
}

/** A row's toggle for its query or logs panel: the label plus a chevron (trace-detail.css) that flips when the panel opens. */
function panelToggleButton(className, panelId, label) {
    return button({
        className: `pk-span-action pk-span-action--expand ${className}`,
        text: label,
        attrs: {'aria-expanded': 'false', 'aria-controls': panelId, 'aria-label': `Show ${label} for this span`}
    });
}

function track(span, traceStart, totalDuration, hasError) {
    const spanStart = span.startTimeMs || traceStart;
    const spanDuration = span.durationMs || 0;
    // Clipped to the track at both ends: an async entry's own bar is measured against a trace
    // duration its work is excluded from, so it can start or end past the right edge, and a bar
    // drawn outside the track claims a place the chart cannot show.
    const left = Math.min(Math.max(((spanStart - traceStart) / totalDuration) * 100, 0), 100);
    // the 0.5% floor only keeps the bar itself visible; the duration cell reports the raw share
    const width = Math.max(Math.min((spanDuration / totalDuration) * 100, 100 - left), 0.5);

    const element = document.createElement('div');
    element.className = 'pk-gantt-track';

    const bar = document.createElement('div');
    bar.className = `pk-gantt-bar${hasError ? ' pk-gantt-bar--error' : ''}`;
    bar.style.left = `${left}%`;
    bar.style.width = `${width}%`;
    element.appendChild(bar);

    (span.events || []).forEach(event => {
        if (event.timestamp) element.appendChild(eventMarker(event, traceStart, totalDuration));
    });
    return element;
}

function eventMarker(event, traceStart, totalDuration) {
    const eventTimeMs = new Date(event.timestamp).getTime();
    const left = Math.max(0, Math.min(100, ((eventTimeMs - traceStart) / totalDuration) * 100));

    const marker = document.createElement('button');
    marker.type = 'button';
    marker.className = 'pk-unbutton pk-gantt-event-marker';
    marker.style.left = `${left}%`;
    marker.setAttribute('aria-label', `Event: ${event.name}`);

    const tooltip = document.createElement('span');
    tooltip.className = 'pk-gantt-event-tooltip';
    tooltip.setAttribute('aria-hidden', 'true');
    tooltip.textContent = event.name;
    marker.appendChild(tooltip);
    return marker;
}

function durationCell(span, totalDuration) {
    const pct = Math.min(100, Math.round(((span.durationMs || 0) / totalDuration) * 100));
    // the backend's own verdict on this span's latency, where it raised an issue
    const severity = issueSeverity(span.issues);

    const cell = document.createElement('span');
    cell.className = 'pk-gantt-duration' + (severity ? ` ${severityClass(severity)}` : '');
    cell.textContent = `${formatDurationMs(span.durationMs)} · ${pct}%`;
    return cell;
}

/** A span's kind, copyable id, error and tags - opened by its name or track. The backend already keeps the statement tags out (they arrive as span.query, shown in queryPanel instead), and events sit on the track. */
function attrsPanel(span, kind, attrsId) {
    return el('div', {className: 'pk-span-panel pk-span-panel--attrs', attrs: {id: attrsId}},
        el('div', {className: 'pk-span-details__head'},
            el('span', {className: 'pk-span-details__kind', text: `${KIND_LABELS[kind]} span`}),
            copyableId(span.spanId, {label: 'spanId'})),
        errorSection(span),
        tagList(span.tags));
}

function errorSection(span) {
    if (!span.errorMessage && !span.errorClass) return null;
    return el('div', {className: 'pk-span-details__error'},
        span.errorClass ? el('div', {className: 'pk-span-details__error-class', text: span.errorClass}) : null,
        span.errorMessage ? el('div', {className: 'pk-span-details__error-message', text: span.errorMessage}) : null);
}

/** The statement (shared/sql-view.js), plus the row count of the result-set span RowCounts paired to it - opened by the row's query toggle. */
function queryPanel(span, queryId, locale) {
    if (!span.query) return null;
    return el('div', {className: 'pk-span-panel pk-span-panel--query', attrs: {id: queryId}},
        span.rowCount != null ? el('span', {className: 'pk-span-row-count', text: formatCount(span.rowCount, 'row', {locale})}) : null,
        sqlView(span.query));
}

/**
 * The span's logs as the Logs tab shows them, trace included. They come from the trace's flat
 * list, since the span tree's own copies (span.logs, what the row's toggle counts) carry no
 * trace. Opened by the row's logs toggle.
 */
function logsPanel(span, display, logsId) {
    const logs = display.logsBySpan.get(span.spanId);
    if (!logs) return null;
    const dateOptions = {locale: display.locale, timeZone: display.timeZone};
    return el('div', {className: 'pk-span-panel pk-span-panel--logs', attrs: {id: logsId}},
        ...logs.map(log => logEntry(log, dateOptions)));
}

function logsBySpan(logs) {
    const bySpan = new Map();
    (logs || []).forEach(log => {
        if (!bySpan.has(log.spanId)) bySpan.set(log.spanId, []);
        bySpan.get(log.spanId).push(log);
    });
    return bySpan;
}

/** Full keys, since short ones collide (db.system.name, jdbc.datasource.name), in key order, since the backend's map has none. */
function tagList(tags) {
    const entries = Object.entries(tags || {}).sort(([a], [b]) => a.localeCompare(b));
    if (entries.length === 0) return null;
    return el('dl', {className: 'pk-span-tags'},
        ...entries.flatMap(([key, value]) => [
            el('dt', {className: 'pk-span-tags__key', text: key}),
            el('dd', {className: 'pk-span-tags__value', text: String(value)})
        ]));
}

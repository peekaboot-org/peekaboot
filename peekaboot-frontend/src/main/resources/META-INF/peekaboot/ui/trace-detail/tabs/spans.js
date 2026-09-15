/**
 * Trace-detail overlay - Spans tab: the gantt chart, its expand/collapse behaviour and each
 * span's details panel. A span's "N logs" toggle does not render anything of its own - it
 * asks trace-detail.js (via context.goToSpanLogs) to switch the overlay to the Logs tab
 * pre-filtered to that span, which is where a span's logs live.
 *
 * Each span renders as one entry: its one-line row, then its details panel (kind, span id,
 * error, SQL, tags), closed until the reader opens it. Entries are flat siblings carrying
 * their depth, which is what the subtree toggle walks.
 *
 * This module writes only one geometry value of its own: an entry's depth, as the CSS custom
 * property --pk-gantt-depth. The name cell's indent, the details panel's margin and the
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

const KIND_LABELS = {server: 'Server', client: 'Client', producer: 'Producer', consumer: 'Consumer', internal: 'Internal'};

export function render(container, trace, context = {}) {
    // the 1 keeps a zero-length trace from dividing by zero in every position below
    const totalDuration = trace.durationMs || 1;
    const traceStart = trace.startTimeMs || 0;
    // the origin is where the trace starts, not a duration anyone measured, so it is
    // spelled out rather than run through formatDurationMs - which calls 0 "<1ms"
    const ticks = ['0ms', ...[0.25, 0.5, 0.75, 1].map(p => formatDurationMs(totalDuration * p))];

    const entries = el('div', {className: 'pk-gantt-rows', attrs: {id: 'pk-gantt-rows'}});
    const allDetailsToggle = button({className: 'pk-btn pk-btn--small pk-gantt-all-details', text: 'Show all details'});
    container.replaceChildren(el('div', {className: 'pk-gantt'},
        el('div', {className: 'pk-gantt-toolbar'}, kindLegend(trace.rootSpan), allDetailsToggle),
        el('div', {className: 'pk-gantt-header'},
            el('div', {className: 'pk-gantt-header__name pk-label', text: 'Span'}),
            el('div', {className: 'pk-gantt-header__timeline'}, ...ticks.map(tick => el('span', {text: tick}))),
            el('div', {className: 'pk-gantt-header__spacer'})),
        entries));

    renderSpanEntries(entries, trace.rootSpan, 0, traceStart, totalDuration);

    allDetailsToggle.addEventListener('click', () => {
        const open = !allDetailsOpen(entries);
        entries.querySelectorAll('.pk-gantt-span').forEach(entry => setDetailsOpen(entry, open));
        syncAllDetailsToggle(entries, allDetailsToggle);
    });

    entries.addEventListener('click', (e) => {
        // Logs toggle: hands off to the Logs tab.
        const logsToggle = e.target.closest('.pk-span-logs-toggle');
        if (logsToggle) {
            context.goToSpanLogs?.(logsToggle.dataset.spanId);
            return;
        }

        // Cross-link: hands off to the Queries tab, scrolled to this span's entry.
        const queryLink = e.target.closest('.pk-span-query-link');
        if (queryLink) {
            context.goToQuery?.(queryLink.dataset.spanId);
            return;
        }

        const toggle = e.target.closest('.pk-gantt-toggle');
        if (toggle) {
            toggleSubtree(toggle);
            return;
        }

        // The name is the keyboard path to the details; the track is the same switch for a
        // pointer, being the widest part of the row. An event marker keeps its own hover.
        const detailsSwitch = e.target.closest('.pk-gantt-name__toggle')
            || (!e.target.closest('.pk-gantt-event-marker') && e.target.closest('.pk-gantt-track'));
        if (detailsSwitch) {
            const entry = detailsSwitch.closest('.pk-gantt-span');
            setDetailsOpen(entry, !entry.classList.contains('pk-gantt-span--open'));
            syncAllDetailsToggle(entries, allDetailsToggle);
        }
    });
}

function setDetailsOpen(entry, open) {
    entry.classList.toggle('pk-gantt-span--open', open);
    entry.querySelector('.pk-gantt-name__toggle').setAttribute('aria-expanded', String(open));
}

function allDetailsOpen(entries) {
    return !entries.querySelector('.pk-gantt-span:not(.pk-gantt-span--open)');
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

function renderSpanEntries(container, span, depth, traceStart, totalDuration) {
    if (!span) return;
    const kind = spanKind(span);
    const detailsId = `pk-span-details-${span.spanId}`;

    const entry = el('div', {className: `pk-gantt-span pk-gantt-kind--${kind}`});
    entry.dataset.depth = depth;
    entry.style.setProperty('--pk-gantt-depth', depth);

    const row = el('div', {className: 'pk-gantt-row'});
    row.dataset.spanId = span.spanId;
    row.append(nameCell(span, kind, detailsId), track(span, traceStart, totalDuration), durationCell(span, totalDuration));
    entry.append(row, detailsPanel(span, kind, detailsId));
    container.appendChild(entry);

    (span.children || []).forEach(child => renderSpanEntries(container, child, depth + 1, traceStart, totalDuration));
}

function nameCell(span, kind, detailsId) {
    const hasChildren = span.children && span.children.length > 0;
    const name = span.name || 'unknown';
    const spanId = span.spanId;
    const logCount = (span.logs || []).length;

    const cell = el('div', {className: 'pk-gantt-name'});
    cell.append(hasChildren
        ? button({className: 'pk-unbutton pk-icon-btn pk-gantt-toggle', attrs: {'aria-expanded': 'true', 'aria-label': 'Collapse child spans'}})
        : el('span', {className: 'pk-gantt-toggle-spacer'}));
    cell.append(button({
        // the backend's verdict, as for the bar: ERROR whenever the span recorded an error message or class
        className: 'pk-unbutton pk-gantt-name__toggle' + (span.status === 'ERROR' ? ' pk-gantt-name__toggle--error' : ''),
        title: name,
        attrs: {'aria-expanded': 'false', 'aria-controls': detailsId, 'aria-label': `${name}, ${kind} span`}
    }, kindDot(), el('span', {className: 'pk-gantt-name__text', text: name})));
    // The backend decides what a query span is (DbSpans) and ships its masked statement as
    // span.query, and the row count of the result-set span it paired to this one (RowCounts)
    // as span.rowCount.
    if (span.rowCount != null) {
        cell.append(el('span', {className: 'pk-span-row-count', text: formatCount(span.rowCount, 'row')}));
    }
    if (logCount > 0) {
        const logs = formatCount(logCount, 'log');
        cell.append(button({
            className: 'pk-span-action pk-span-logs-toggle', text: logs, title: 'View logs for this span',
            attrs: {'data-span-id': spanId, 'aria-label': `View ${logs} for this span in the Logs tab`}
        }));
    }
    return cell;
}

function track(span, traceStart, totalDuration) {
    const spanStart = span.startTimeMs || traceStart;
    const spanDuration = span.durationMs || 0;
    const left = Math.max(0, ((spanStart - traceStart) / totalDuration) * 100);
    // the 0.5% floor only keeps the bar itself visible; the duration cell reports the raw share
    const width = Math.max((spanDuration / totalDuration) * 100, 0.5);
    // the backend's verdict: ERROR whenever the span recorded an error message or class
    const hasError = span.status === 'ERROR';

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

/**
 * Everything about a span that does not fit its one-line row, closed until the name or the
 * track opens it. The backend already keeps the statement tags out (they arrive as
 * span.query), and events sit on the track.
 */
function detailsPanel(span, kind, detailsId) {
    return el('div', {className: 'pk-span-details', attrs: {id: detailsId}},
        el('div', {className: 'pk-span-details__head'},
            el('span', {className: 'pk-span-details__kind', text: `${KIND_LABELS[kind]} span`}),
            copyableId(span.spanId, {label: 'spanId'})),
        errorSection(span),
        querySection(span),
        tagList(span.tags));
}

function errorSection(span) {
    if (!span.errorMessage && !span.errorClass) return null;
    return el('div', {className: 'pk-span-details__error'},
        span.errorClass ? el('div', {className: 'pk-span-details__error-class', text: span.errorClass}) : null,
        span.errorMessage ? el('div', {className: 'pk-span-details__error-message', text: span.errorMessage}) : null);
}

function querySection(span) {
    if (!span.query) return null;
    return el('div', {className: 'pk-span-details__query'},
        el('pre', {className: 'pk-code-block', text: span.query}),
        button({
            className: 'pk-btn pk-btn--small pk-span-query-link', text: 'Show in Queries tab',
            attrs: {'data-span-id': span.spanId}
        }));
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

/**
 * Trace-detail overlay - Spans tab: the gantt chart, its expand/collapse behaviour and each
 * span's details panel. A span's "N logs" toggle does not render anything of its own - it
 * asks trace-detail.js (via context.goToSpanLogs) to switch the overlay to the Logs tab
 * pre-filtered to that span, which is where a span's logs and its full id both live.
 *
 * Each span renders as one entry: its one-line row, then its details panel, closed until
 * the reader opens it. Entries are flat siblings carrying their depth, which is what the
 * subtree toggle walks.
 *
 * Bar positions, marker offsets and indents are set through the CSSOM, never as a style
 * attribute in markup: a host page whose CSP omits style-src 'unsafe-inline' drops the
 * attributes, which would flatten every row to depth 0 and every bar to the left edge.
 */
import {el, button} from '../../shared/dom.js';
import {formatCount, formatDurationMs} from '../../shared/format.js';
import {issueSeverity, severityClass} from '../../shared/severity.js';

const INDENT_PX = 20;
/** The subtree toggle's column; a details panel starts past it, under the span's name. */
const TOGGLE_PX = 24;

const KIND_LABELS = {server: 'Server', client: 'Client', producer: 'Producer', consumer: 'Consumer', internal: 'Internal'};

export function render(container, trace, context = {}) {
    // the 1 keeps a zero-length trace from dividing by zero in every position below
    const totalDuration = trace.durationMs || 1;
    const traceStart = trace.startTimeMs || 0;
    // the origin is where the trace starts, not a duration anyone measured, so it is
    // spelled out rather than run through formatDurationMs - which calls 0 "<1ms"
    const ticks = ['0ms', ...[0.25, 0.5, 0.75, 1].map(p => formatDurationMs(totalDuration * p))];

    const entries = el('div', {attrs: {id: 'pk-gantt-rows'}});
    container.replaceChildren(el('div', {className: 'pk-gantt'},
        el('div', {className: 'pk-gantt-header'},
            el('div', {className: 'pk-gantt-header__name pk-label', text: 'Span'}),
            el('div', {className: 'pk-gantt-header__timeline'}, ...ticks.map(tick => el('span', {text: tick}))),
            el('div', {className: 'pk-gantt-header__spacer'})),
        entries));

    renderSpanEntries(entries, trace.rootSpan, 0, traceStart, totalDuration);

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
        }
    });
}

function setDetailsOpen(entry, open) {
    entry.classList.toggle('pk-gantt-span--open', open);
    entry.querySelector('.pk-gantt-name__toggle').setAttribute('aria-expanded', String(open));
}

function toggleSubtree(toggle) {
    const expand = toggle.getAttribute('aria-expanded') === 'false';
    toggle.setAttribute('aria-expanded', String(expand));
    toggle.setAttribute('aria-label', expand ? 'Collapse child spans' : 'Expand child spans');
    toggle.textContent = expand ? '-' : '+';
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

function renderSpanEntries(container, span, depth, traceStart, totalDuration) {
    if (!span) return;
    const kind = spanKind(span);
    const detailsId = `pk-span-details-${span.spanId}`;

    const entry = el('div', {className: 'pk-gantt-span'});
    entry.dataset.depth = depth;

    const row = el('div', {className: 'pk-gantt-row'});
    row.dataset.spanId = span.spanId;
    row.append(nameCell(span, kind, depth, detailsId), track(span, traceStart, totalDuration), durationCell(span, totalDuration));
    entry.append(row, detailsPanel(span, kind, depth, detailsId));
    container.appendChild(entry);

    (span.children || []).forEach(child => renderSpanEntries(container, child, depth + 1, traceStart, totalDuration));
}

function nameCell(span, kind, depth, detailsId) {
    const hasChildren = span.children && span.children.length > 0;
    const name = span.name || 'unknown';
    const spanId = span.spanId;
    const logCount = (span.logs || []).length;

    const cell = el('div', {className: 'pk-gantt-name'});
    cell.style.paddingLeft = `${depth * INDENT_PX}px`;
    cell.append(hasChildren
        ? button({className: 'pk-unbutton pk-icon-btn pk-gantt-toggle', text: '-', attrs: {'aria-expanded': 'true', 'aria-label': 'Collapse child spans'}})
        : el('span', {className: 'pk-gantt-toggle-spacer'}));
    if (kind !== 'internal') {
        cell.append(el('span', {className: `pk-gantt-kind pk-gantt-kind--${kind}`, text: kind}));
    }
    cell.append(button({
        className: 'pk-unbutton pk-gantt-name__toggle', title: name,
        attrs: {'aria-expanded': 'false', 'aria-controls': detailsId}
    }, el('span', {className: 'pk-gantt-name__text', text: name})));
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
    const kind = (span.kind || 'internal').toLowerCase();
    // the backend's verdict: ERROR whenever the span recorded an error message or class
    const hasError = span.status === 'ERROR';

    const element = document.createElement('div');
    element.className = 'pk-gantt-track';

    const bar = document.createElement('div');
    bar.className = `pk-gantt-bar pk-gantt-bar--${kind}${hasError ? ' pk-gantt-bar--error' : ''}`;
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
function detailsPanel(span, kind, depth, detailsId) {
    const panel = el('div', {className: 'pk-span-details', attrs: {id: detailsId}},
        el('div', {className: 'pk-span-details__head'},
            el('span', {className: 'pk-span-details__kind', text: `${KIND_LABELS[kind]} span`})),
        querySection(span),
        tagList(span.tags));
    panel.style.marginLeft = `${depth * INDENT_PX + TOGGLE_PX}px`;
    return panel;
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

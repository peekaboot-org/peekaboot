/**
 * Trace-detail overlay - Spans tab: the gantt chart and its expand/collapse behaviour.
 * A span's "N logs" toggle does not render anything of its own - it asks trace-detail.js
 * (via context.goToSpanLogs) to switch the overlay to the Logs tab pre-filtered to that
 * span, which is where a span's logs and its full id both live.
 *
 * Bar positions, marker offsets and row indents are set through the CSSOM, never as a
 * style attribute in markup: a host page whose CSP omits style-src 'unsafe-inline' drops
 * the attributes, which would flatten every row to depth 0 and every bar to the left edge.
 */
import {escapeHtml} from '../../shared/markup.js';
import {formatCount, formatDurationMs} from '../../shared/format.js';
import {issueSeverity} from '../../shared/severity.js';

const INDENT_PX = 20;

export function render(container, trace, context = {}) {
    // the 1 keeps a zero-length trace from dividing by zero in every position below
    const totalDuration = trace.durationMs || 1;
    const traceStart = trace.startTimeMs || 0;
    const ticks = [0, 0.25, 0.5, 0.75, 1].map(p => formatDurationMs(totalDuration * p));

    container.innerHTML = '<div class="pk-gantt">'
        + '<div class="pk-gantt-header">'
        + '<div class="pk-gantt-header__name">Span</div>'
        + `<div class="pk-gantt-header__timeline">${ticks.map(tick => `<span>${tick}</span>`).join('')}</div>`
        + '<div class="pk-gantt-header__spacer"></div>'
        + '</div>'
        + '<div id="pk-gantt-rows"></div>'
        + '</div>';

    const rowsContainer = container.querySelector('#pk-gantt-rows');
    renderSpanRows(rowsContainer, trace.rootSpan, 0, traceStart, totalDuration);

    rowsContainer.addEventListener('click', (e) => {
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

        const sqlToggle = e.target.closest('.pk-span-query-toggle');
        if (sqlToggle) {
            toggleQueryDetail(rowsContainer, sqlToggle);
            return;
        }

        const toggle = e.target.closest('.pk-gantt-toggle');
        if (toggle) toggleSubtree(toggle);
    });
}

function toggleQueryDetail(rowsContainer, sqlToggle) {
    const spanId = sqlToggle.dataset.spanId;
    const queryDetail = rowsContainer.querySelector(`.pk-span-query-detail[data-span-id="${CSS.escape(spanId)}"]`);
    if (!queryDetail) return;
    const expanded = queryDetail.classList.toggle('pk-span-query-detail--expanded');
    sqlToggle.title = expanded ? 'Hide SQL' : 'Show SQL';
}

function toggleSubtree(toggle) {
    const expand = toggle.getAttribute('aria-expanded') === 'false';
    toggle.setAttribute('aria-expanded', String(expand));
    toggle.setAttribute('aria-label', expand ? 'Collapse child spans' : 'Expand child spans');
    toggle.textContent = expand ? '-' : '+';
    setSubtreeVisible(toggle.closest('.pk-gantt-row'), expand);
}

/**
 * Shows or hides everything nested under `row` - every following row with a deeper
 * data-depth, query details and tag rows included. Expanding leaves a collapsed
 * descendant's own subtree hidden.
 */
function setSubtreeVisible(row, expand) {
    const rowDepth = Number(row.dataset.depth);
    let sibling = row.nextElementSibling;
    while (sibling && Number(sibling.dataset.depth) > rowDepth) {
        sibling.style.display = expand ? '' : 'none';
        const collapsed = expand && sibling.querySelector('.pk-gantt-toggle[aria-expanded="false"]');
        sibling = collapsed ? nextOutsideSubtree(sibling) : sibling.nextElementSibling;
    }
}

function nextOutsideSubtree(row) {
    const depth = Number(row.dataset.depth);
    let next = row.nextElementSibling;
    while (next && Number(next.dataset.depth) > depth) next = next.nextElementSibling;
    return next;
}

function renderSpanRows(container, span, depth, traceStart, totalDuration) {
    if (!span) return;
    const indent = depth * INDENT_PX;

    const row = document.createElement('div');
    row.className = 'pk-gantt-row';
    row.dataset.spanId = span.spanId;
    row.dataset.depth = depth;
    row.append(nameCell(span, indent), track(span, traceStart, totalDuration), durationCell(span, totalDuration));
    container.appendChild(row);

    if (span.query) container.appendChild(queryDetailRow(span, indent, depth));
    const badges = tagBadgesRow(span, indent, depth);
    if (badges) container.appendChild(badges);

    (span.children || []).forEach(child => renderSpanRows(container, child, depth + 1, traceStart, totalDuration));
}

function nameCell(span, indent) {
    const cell = document.createElement('div');
    cell.className = 'pk-gantt-name';
    cell.style.paddingLeft = `${indent}px`;
    cell.innerHTML = nameCellHtml(span);
    return cell;
}

function nameCellHtml(span) {
    const hasChildren = span.children && span.children.length > 0;
    const kind = (span.kind || 'internal').toLowerCase();
    const name = span.name || 'unknown';
    const spanId = escapeHtml(span.spanId);
    const logCount = (span.logs || []).length;
    // The backend decides what a query span is (DbSpans) and ships its masked statement as
    // span.query; a datasource-proxy result-set span carries the row count as a tag.
    const rowCount = name.toLowerCase().includes('result-set') ? (span.tags || {})['jdbc.row-count'] : undefined;

    let html = hasChildren
        ? '<button type="button" class="pk-gantt-toggle" aria-expanded="true" aria-label="Collapse child spans">-</button>'
        : '<span class="pk-gantt-toggle-spacer"></span>';
    if (kind !== 'internal' && kind !== 'unknown') {
        html += `<span class="pk-gantt-kind pk-gantt-kind--${escapeHtml(kind)}">${escapeHtml(kind)}</span>`;
    }
    html += `<span class="pk-gantt-name__text" title="${escapeHtml(name)}">${escapeHtml(name)}</span>`;
    if (rowCount !== undefined) {
        html += `<span class="pk-span-row-count">${formatCount(Number(rowCount), 'row')}</span>`;
    }
    if (span.query) {
        html += `<button type="button" class="pk-span-action pk-span-query-toggle" data-span-id="${spanId}"`
            + ' title="Show SQL" aria-label="Show SQL for this span">&#128196;</button>'
            + `<button type="button" class="pk-span-action pk-span-query-link" data-span-id="${spanId}"`
            + ' title="Show in Queries tab" aria-label="Show this query in the Queries tab">&#10551;</button>';
    }
    if (logCount > 0) {
        const logs = formatCount(logCount, 'log');
        html += `<button type="button" class="pk-span-action pk-span-logs-toggle" data-span-id="${spanId}"`
            + ` title="View logs for this span" aria-label="View ${logs} for this span in the Logs tab">${logs}</button>`;
    }
    return html;
}

function track(span, traceStart, totalDuration) {
    const spanStart = span.startTimeMs || traceStart;
    const spanDuration = span.durationMs || 0;
    const left = Math.max(0, ((spanStart - traceStart) / totalDuration) * 100);
    // the 0.5% floor only keeps the bar itself visible; the duration cell reports the raw share
    const width = Math.max((spanDuration / totalDuration) * 100, 0.5);
    const kind = (span.kind || 'internal').toLowerCase();
    const hasError = span.status === 'ERROR' || span.errorMessage;

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
    marker.className = 'pk-gantt-event-marker';
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
    cell.className = 'pk-gantt-duration' + (severity ? ` pk-gantt-duration--${severity}` : '');
    cell.textContent = `${formatDurationMs(span.durationMs)} · ${pct}%`;
    return cell;
}

/** The masked statement, hidden until the row's SQL toggle reveals it. */
function queryDetailRow(span, indent, depth) {
    const detail = document.createElement('div');
    detail.className = 'pk-span-query-detail';
    detail.dataset.spanId = span.spanId;
    // depth + 1: counts as collapsible content of this span's row, so the
    // expand/collapse walker (which stops at depth <= row depth) passes it
    detail.dataset.depth = depth + 1;
    detail.style.marginLeft = `${indent + INDENT_PX}px`;
    detail.innerHTML = `<div class="pk-query-label">Query</div><div class="pk-query-text">${escapeHtml(span.query)}</div>`;
    return detail;
}

/**
 * The span's tags as badges under its row, or null when there are none to show: the
 * statement tags already show in the query detail, and events sit on the track.
 */
function tagBadgesRow(span, indent, depth) {
    const entries = Object.entries(span.tags || {}).filter(([key]) => !isStatementTag(key));
    if (entries.length === 0) return null;

    const row = document.createElement('div');
    row.className = 'pk-gantt-badges';
    row.dataset.depth = depth + 1;
    row.style.paddingLeft = `${indent + INDENT_PX}px`;
    row.innerHTML = entries.map(([key, value]) => tagBadgeHtml(key, String(value))).join('');
    return row;
}

function tagBadgeHtml(key, value) {
    const shortKey = key.split('.').pop();
    const shortValue = value.length > 50 ? value.substring(0, 50) + '...' : value;
    return `<span class="pk-tag-badge" title="${escapeHtml(key)}: ${escapeHtml(value)}">`
        + `<span class="pk-tag-badge__key">${escapeHtml(shortKey)}</span>=`
        + `<span class="pk-tag-badge__value">${escapeHtml(shortValue)}</span></span>`;
}

/** The tags DbSpans.sql reads the statement from - shown once, in the query detail, not again as a badge. */
function isStatementTag(key) {
    return key.startsWith('jdbc.query') || key === 'db.query.text' || key === 'db.statement';
}

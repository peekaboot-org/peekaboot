/**
 * Trace-detail overlay - Spans tab: the gantt chart and its expand/collapse behaviour.
 * A span's "N logs" toggle does not render anything of its own - it asks trace-detail.js
 * (via context.goToSpanLogs) to switch the overlay to the Logs tab pre-filtered to that
 * span, which is where a span's logs and its full id both live.
 */
import {escapeHtml} from '../../shared/markup.js';
import {formatCount} from '../../shared/format.js';

export function render(container, trace, context = {}) {
    const totalDuration = trace.durationMs || 1;
    const traceStart = trace.startTimeMs || 0;
    const markers = [0, 0.25, 0.5, 0.75, 1].map(p => Math.round(totalDuration * p) + 'ms');

    let html = '<div class="pk-gantt">';
    html += '<div class="pk-gantt-header">';
    html += '<div class="pk-gantt-header-name">Span</div>';
    html += '<div class="pk-gantt-header-timeline">' + markers.map(m => `<span>${m}</span>`).join('') + '</div>';
    html += '<div class="pk-gantt-header-spacer"></div>';
    html += '</div>';
    html += '<div id="pk-gantt-rows"></div>';
    html += '</div>';
    container.innerHTML = html;

    const rowsContainer = container.querySelector('#pk-gantt-rows');
    renderSpanRows(rowsContainer, trace.rootSpan, 0, traceStart, totalDuration, null);

    // Add click handlers for expand/collapse
    rowsContainer.addEventListener('click', (e) => {
        // Logs toggle: hands off to the Logs tab.
        const logsToggle = e.target.closest('.pk-span-logs-toggle');
        if (logsToggle) {
            context.goToSpanLogs?.(logsToggle.dataset.spanId);
            return;
        }

        // Handle SQL toggle clicks
        const sqlToggle = e.target.closest('.pk-span-query-toggle');
        if (sqlToggle) {
            const spanId = sqlToggle.dataset.spanId;
            const queryDetail = rowsContainer.querySelector(`.pk-span-query-detail[data-span-id="${CSS.escape(spanId)}"]`);
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
        const isCollapsed = toggle.getAttribute('aria-expanded') === 'false';

        toggle.setAttribute('aria-expanded', String(isCollapsed));
        toggle.setAttribute('aria-label', isCollapsed ? 'Collapse child spans' : 'Expand child spans');
        toggle.textContent = isCollapsed ? '-' : '+';

        // Show/hide descendant rows
        let sibling = row.nextElementSibling;
        const rowDepth = parseInt(row.dataset.depth);
        while (sibling && parseInt(sibling.dataset.depth) > rowDepth) {
            if (isCollapsed) {
                sibling.style.display = '';
                // If this row has a collapsed toggle, skip its children
                const sibToggle = sibling.querySelector('.pk-gantt-toggle');
                if (sibToggle && sibToggle.getAttribute('aria-expanded') === 'false') {
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

function renderSpanRows(container, span, depth, traceStart, totalDuration, parentId) {
    if (!span) return;

    const indent = depth * 20;
    const spanStart = span.startTimeMs || traceStart;
    const spanDuration = span.durationMs || 0;
    const left = Math.max(0, ((spanStart - traceStart) / totalDuration) * 100);
    // Percent of total trace time, from the raw ratio - computed before the 0.5%-min
    // clamp below, which exists only to keep the bar itself visible, not to inflate
    // what a genuinely tiny span reports as its share of the trace.
    const pct = Math.min(100, Math.round((spanDuration / totalDuration) * 100));
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

    // Logs are attached to the span by the backend
    const spanLogs = span.logs || [];
    const hasLogs = spanLogs.length > 0;

    const row = document.createElement('div');
    row.className = 'pk-gantt-row';
    row.dataset.spanId = span.spanId;
    row.dataset.depth = depth;
    if (parentId) row.dataset.parentId = parentId;

    let nameHtml = `<div class="pk-gantt-name" style="padding-left: ${indent}px">`;
    if (hasChildren) {
        nameHtml += `<button type="button" class="pk-gantt-toggle" aria-expanded="true" aria-label="Collapse child spans">-</button>`;
    } else {
        nameHtml += `<span style="width:16px"></span>`;
    }
    if (kind !== 'internal' && kind !== 'unknown') {
        nameHtml += `<span class="pk-gantt-kind ${kind}">${kind}</span>`;
    }
    nameHtml += `<span class="pk-gantt-name-text" title="${escapeHtml(span.name || 'unknown')}">${escapeHtml(span.name || 'unknown')}</span>`;

    // Add row count badge for result-set spans
    if (isResultSetSpan && rowCount !== undefined) {
        nameHtml += `<span class="pk-span-row-count">${formatCount(Number(rowCount), 'row')}</span>`;
    }

    // Add query toggle for query spans with SQL
    if (hasQuery) {
        nameHtml += `<button type="button" class="pk-span-query-toggle" data-span-id="${escapeHtml(span.spanId)}" title="Show SQL" aria-label="Show SQL for this span">&#128196;</button>`;
    }

    // Add logs toggle for spans with logs - switches the overlay to the Logs tab,
    // pre-filtered to this span (see context.goToSpanLogs above).
    if (hasLogs) {
        nameHtml += `<button type="button" class="pk-span-logs-toggle" data-span-id="${escapeHtml(span.spanId)}"`
            + ` title="View logs for this span" aria-label="View ${formatCount(spanLogs.length, 'log')} for this span in the Logs tab">`
            + `${formatCount(spanLogs.length, 'log')}</button>`;
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
            trackHtml += `<button type="button" class="pk-gantt-event-marker" style="left: ${eventLeft}%" aria-label="Event: ${escapeHtml(event.name)}"><span class="pk-gantt-event-tooltip" aria-hidden="true">${escapeHtml(event.name)}</span></button>`;
        }
    });
    trackHtml += `</div>`;

    row.innerHTML = nameHtml + trackHtml + `<span class="pk-gantt-duration">${spanDuration}ms · ${pct}%</span>`;

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

    // Add tags row if present (events are shown as markers on the timeline)
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

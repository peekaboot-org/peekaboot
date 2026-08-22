/**
 * Trace-detail overlay - Logs tab: the filterable log list. renderLogRows is exported
 * because the Spans tab's span-logs popup (tabs/spans.js) reuses it verbatim.
 */
import {escapeHtml} from '../../shared/markup.js';
import {formatTimeOfDay} from '../../shared/format.js';
import {buildSpanNames} from '../../shared/span-names.js';

/**
 * Renders log rows. Pass showSpanColumn when rows come from more than one span,
 * so the span id is worth a column of its own.
 */
export function renderLogRows(logs, {showSpanColumn = false, spanNames} = {}) {
    return logs.map(log => {
        const spanId = log.spanId || '';
        const spanCell = showSpanColumn
            ? `<button type="button" class="pk-log__span" data-span-id="${escapeHtml(spanId)}" title="${escapeHtml(spanId)}" aria-label="Filter logs to span ${escapeHtml(spanId)}">`
              + `${escapeHtml(spanNames?.get(spanId) || spanId)}</button>`
            : '';
        return `<div class="pk-log" data-level="${escapeHtml(log.level)}" data-span-id="${escapeHtml(spanId)}">`
             + `<span class="pk-log__time">${escapeHtml(formatTimeOfDay(log.timestamp))}</span>`
             + spanCell
             + `<span class="pk-log__level pk-log__level--${escapeHtml(String(log.level).toLowerCase())}">${escapeHtml(log.level)}</span>`
             + `<span class="pk-log__message">${escapeHtml(log.message)}</span>`
             + `</div>`;
    }).join('');
}

export function render(container, trace) {
    const spanNames = buildSpanNames(trace.rootSpan);
    const logs = trace.logs || [];

    if (logs.length === 0) {
        container.innerHTML = '<div class="pk-empty">No logs recorded for this trace</div>';
        return;
    }

    let currentSpanFilter = null;

    function renderView() {
        let html = '<div class="pk-logs-filter">';
        html += '<input type="text" placeholder="Filter logs..." id="pk-log-filter">';
        html += '<select id="pk-log-level"><option value="">All Levels</option><option>ERROR</option><option>WARN</option><option>INFO</option><option>DEBUG</option></select>';
        if (currentSpanFilter) {
            const spanName = spanNames.get(currentSpanFilter) || currentSpanFilter;
            const shortName = spanName.length > 20 ? spanName.substring(0, 20) + '...' : spanName;
            html += `<span class="pk-logs-filter-span">Span: ${escapeHtml(shortName)} <button type="button" class="pk-logs-filter-span-clear" id="pk-clear-span-filter" aria-label="Clear span filter">&times;</button></span>`;
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
                renderView();
            });
        }

        // Span click to filter
        container.querySelectorAll('.pk-log__span').forEach(el => {
            el.addEventListener('click', () => {
                const spanId = el.dataset.spanId;
                if (spanId) {
                    currentSpanFilter = spanId;
                    renderView();
                }
            });
        });
    }

    renderView();
}

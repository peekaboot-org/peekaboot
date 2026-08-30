/**
 * Trace-detail overlay - Logs tab: the filterable log list. Every row names its span
 * (a click filters to it) and carries that span's full id as a copyable control - the
 * one place a span's id lives now that the Spans tab tree dropped it (too crowded with
 * a full id on every row); see spans.js's "N logs" toggle, which lands here pre-filtered
 * via context.spanFilter below rather than opening its own popup.
 */
import {escapeHtml} from '../../shared/markup.js';
import {formatTimeOfDay} from '../../shared/format.js';
import {buildSpanNames} from '../../shared/span-names.js';
import {copyableIdHtml} from '../../shared/copyable.js';

function renderLogRows(logs, spanNames) {
    return logs.map(log => {
        const spanId = log.spanId || '';
        const spanCell = `<span class="pk-log__span-cell">`
            + `<button type="button" class="pk-log__span" data-span-id="${escapeHtml(spanId)}" title="${escapeHtml(spanId)}" aria-label="Filter logs to span ${escapeHtml(spanId)}">`
            + `${escapeHtml(spanNames?.get(spanId) || spanId)}</button>`
            + copyableIdHtml(spanId, {label: 'spanId', truncate: true})
            + `</span>`;
        return `<div class="pk-log" data-level="${escapeHtml(log.level)}" data-span-id="${escapeHtml(spanId)}">`
             + `<span class="pk-log__time">${escapeHtml(formatTimeOfDay(log.timestamp))}</span>`
             + spanCell
             + `<span class="pk-log__level pk-log__level--${escapeHtml(String(log.level).toLowerCase())}">${escapeHtml(log.level)}</span>`
             + `<span class="pk-log__message">${escapeHtml(log.message)}</span>`
             + `</div>`;
    }).join('');
}

/**
 * context.spanFilter seeds the filter spans.js's "N logs" toggle asked for - trace-detail.js
 * switches this tab in and re-renders it with that span already applied, on top of the
 * clear/back affordance below that this tab already had for a filter set from within itself.
 */
export function render(container, trace, context = {}) {
    const spanNames = buildSpanNames(trace.rootSpan);
    const logs = trace.logs || [];

    if (logs.length === 0) {
        container.innerHTML = '<div class="pk-empty">No logs recorded for this trace</div>';
        return;
    }

    let currentSpanFilter = context.spanFilter || null;

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
        html += `<div id="pk-logs-list">${renderLogRows(logs, spanNames)}</div>`;
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

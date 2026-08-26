/**
 * Trace-detail overlay - Logs tab: the filterable log list. renderLogRows is exported
 * because the Spans tab's span-logs popup (tabs/spans.js) reuses it verbatim.
 */
import {escapeHtml} from '../../shared/markup.js';
import {formatTimeOfDay} from '../../shared/format.js';
import {buildSpanNames} from '../../shared/span-names.js';

const LEVELS = ['ERROR', 'WARN', 'INFO', 'DEBUG'];

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

/**
 * `view.filters` (`{q, level, span}`, see url-state.js) seeds this tab's state when it is
 * the one restored from the URL at overlay-open time; `view.setFilters(next)` reports every
 * change back so it round-trips into the hash. Both are optional - the dev toolbar's open
 * path (no urlState at all) leaves filtering purely local, as before.
 */
export function render(container, trace, view = {}) {
    const spanNames = buildSpanNames(trace.rootSpan);
    const logs = trace.logs || [];

    if (logs.length === 0) {
        container.innerHTML = '<div class="pk-empty">No logs recorded for this trace</div>';
        return;
    }

    // Single source of truth for the three filters - renderView() below renders the
    // controls FROM this, instead of the controls' own DOM values, so a re-render (the
    // span filter changing) can no longer wipe out the text/level filters. See logs.js's
    // task brief for the bug this replaced.
    //
    // state.level is validated against LEVELS (mirroring how trace-detail.js falls an
    // unrecognized subview back to 'spans'): an unvalidated value from the URL (a typo, a
    // stale link, a different case) would match none of the rendered <option>s, so the
    // browser would default the <select> to "All Levels" while applyFilters() kept
    // filtering by that bogus value underneath - a dropdown that visually claims no filter
    // is applied while silently hiding every row.
    const state = {
        q: view.filters?.q || '',
        level: LEVELS.includes(view.filters?.level) ? view.filters.level : '',
        span: view.filters?.span || null
    };

    function publishFilters() {
        const next = {};
        if (state.q) next.q = state.q;
        if (state.level) next.level = state.level;
        if (state.span) next.span = state.span;
        view.setFilters?.(next);
    }

    function renderView() {
        let html = '<div class="pk-logs-filter">';
        html += `<input type="text" placeholder="Filter logs..." id="pk-log-filter" value="${escapeHtml(state.q)}">`;
        html += '<select id="pk-log-level">';
        html += `<option value=""${state.level === '' ? ' selected' : ''}>All Levels</option>`;
        LEVELS.forEach(level => {
            html += `<option${state.level === level ? ' selected' : ''}>${level}</option>`;
        });
        html += '</select>';
        if (state.span) {
            const shortId = state.span.slice(0, 8);
            const spanName = spanNames.get(state.span);
            let label;
            let title = '';
            if (spanName) {
                const shortName = spanName.length > 20 ? spanName.substring(0, 20) + '...' : spanName;
                label = `${escapeHtml(shortName)} (${shortId})`;
            } else {
                label = shortId;
                title = ` title="${escapeHtml(state.span)}"`;
            }
            html += `<span class="pk-logs-filter-span"${title}>Span: ${label} `
                + `<button type="button" class="pk-logs-filter-span-clear" id="pk-clear-span-filter" aria-label="Clear span filter">&times;</button></span>`;
        }
        html += '</div>';
        html += `<div id="pk-logs-list">${renderLogRows(logs, {showSpanColumn: true, spanNames})}</div>`;
        container.innerHTML = html;

        // Filter controls
        const filterInput = container.querySelector('#pk-log-filter');
        const levelSelect = container.querySelector('#pk-log-level');
        const clearSpanFilter = container.querySelector('#pk-clear-span-filter');

        function applyFilters() {
            const text = state.q.toLowerCase();
            const level = state.level;
            container.querySelectorAll('.pk-log').forEach(item => {
                const message = item.querySelector('.pk-log__message').textContent.toLowerCase();
                const itemLevel = item.dataset.level;
                const itemSpanId = item.dataset.spanId;
                const matchText = !text || message.includes(text);
                const matchLevel = !level || itemLevel === level;
                const matchSpan = !state.span || itemSpanId === state.span;
                item.classList.toggle('pk-log--hidden', !(matchText && matchLevel && matchSpan));
            });
        }

        // Establishes the initial visibility (filters restored from the URL, or a span
        // filter set before this render) before any control has fired an event.
        applyFilters();

        filterInput.addEventListener('input', () => {
            state.q = filterInput.value;
            applyFilters();
            publishFilters();
        });
        levelSelect.addEventListener('change', () => {
            state.level = levelSelect.value;
            applyFilters();
            publishFilters();
        });

        // Clear span filter
        if (clearSpanFilter) {
            clearSpanFilter.addEventListener('click', () => {
                state.span = null;
                publishFilters();
                renderView();
            });
        }

        // Span click to filter
        container.querySelectorAll('.pk-log__span').forEach(el => {
            el.addEventListener('click', () => {
                const spanId = el.dataset.spanId;
                if (spanId) {
                    state.span = spanId;
                    publishFilters();
                    renderView();
                }
            });
        });
    }

    renderView();
}

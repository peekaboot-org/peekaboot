/**
 * Trace-detail overlay - Logs tab: the filterable log list. Every row names its span
 * (a click filters to it) and carries that span's full id as a copyable control - the
 * one place a span's id lives, since the Spans tab tree shows none (too crowded with a
 * full id on every row); see spans.js's "N logs" toggle, which lands here with this
 * tab's own span filter already seeded.
 */
import {escapeHtml} from '../../shared/markup.js';
import {formatTimeOfDay} from '../../shared/format.js';
import {LOG_LEVELS} from '../../shared/severity.js';
import {buildSpanNames} from '../../shared/span-names.js';
import {copyableIdHtml} from '../../shared/copyable.js';

function renderLogRows(logs, spanNames, dateOptions, canJumpToSpan) {
    return logs.map(log => {
        const spanId = log.spanId || '';
        // Cross-link to the span's row in the Spans tab tree - distinct from the name
        // button beside it, which filters this list (see trace-detail.js's goToSpan).
        const treeLink = canJumpToSpan && spanId
            ? `<button type="button" class="pk-log__goto-span" data-span-id="${escapeHtml(spanId)}"`
                + ` title="Show this span in the span tree"`
                + ` aria-label="Show span ${escapeHtml(spanId)} in the span tree">&#10550;</button>`
            : '';
        const spanCell = `<span class="pk-log__span-cell">`
            + `<span class="pk-log__span-row">`
            + `<button type="button" class="pk-log__span" data-span-id="${escapeHtml(spanId)}" title="${escapeHtml(spanId)}" aria-label="Filter logs to span ${escapeHtml(spanId)}">`
            + `${escapeHtml(spanNames?.get(spanId) || spanId)}</button>`
            + treeLink
            + `</span>`
            + copyableIdHtml(spanId, {label: 'spanId', truncate: true})
            + `</span>`;
        return `<div class="pk-log" data-level="${escapeHtml(log.level)}" data-span-id="${escapeHtml(spanId)}">`
             + `<span class="pk-log__time">${escapeHtml(formatTimeOfDay(log.timestamp, dateOptions))}</span>`
             + spanCell
             + `<span class="pk-log__level pk-log__level--${escapeHtml(String(log.level).toLowerCase())}">${escapeHtml(log.level)}</span>`
             + `<span class="pk-log__message">${escapeHtml(log.message)}</span>`
             + `</div>`;
    }).join('');
}

/**
 * `view.filters` (`{q, level, span}`, see url-state.js) seeds this tab's state: from the URL
 * when this is the tab restored at overlay-open time, or from spans.js's "N logs" toggle,
 * which routes through the very same seam (trace-detail.js's goToSpanLogs re-renders this
 * tab with `{span}`) instead of needing a hand-off channel of its own.
 * `view.setFilters(next)` reports every change back so it round-trips into the hash. Both
 * are optional - the dev toolbar's open path (no urlState at all) leaves filtering purely
 * local. `view.locale`/`view.timeZone` are the dashboard's display settings for the
 * timestamps; absent (the toolbar), the browser's own apply.
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
    // span filter changing) cannot wipe out the text/level filters.
    //
    // state.level is validated against LOG_LEVELS (mirroring how trace-detail.js falls an
    // unrecognized subview back to 'spans'): an unvalidated value from the URL (a typo, a
    // stale link, a different case) would match none of the rendered <option>s, so the
    // browser would default the <select> to "All Levels" while applyFilters() kept
    // filtering by that bogus value underneath - a dropdown that visually claims no filter
    // is applied while silently hiding every row.
    const state = {
        q: view.filters?.q || '',
        level: LOG_LEVELS.includes(view.filters?.level) ? view.filters.level : '',
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
        LOG_LEVELS.forEach(level => {
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
                label = `${escapeHtml(shortName)} (${escapeHtml(shortId)})`;
            } else {
                label = escapeHtml(shortId);
                title = ` title="${escapeHtml(state.span)}"`;
            }
            html += `<span class="pk-logs-filter-span"${title}>Span: ${label} `
                + `<button type="button" class="pk-logs-filter-span-clear" id="pk-clear-span-filter" aria-label="Clear span filter">&times;</button></span>`;
        }
        html += '</div>';
        html += `<div id="pk-logs-list">${renderLogRows(logs, spanNames, {locale: view.locale, timeZone: view.timeZone}, Boolean(view.goToSpan))}</div>`;
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

        // Cross-link to the span tree (see renderLogRows)
        container.querySelectorAll('.pk-log__goto-span').forEach(el => {
            el.addEventListener('click', () => view.goToSpan?.(el.dataset.spanId));
        });
    }

    renderView();
}

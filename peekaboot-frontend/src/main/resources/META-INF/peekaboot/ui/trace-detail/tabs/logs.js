/**
 * Trace-detail overlay - Logs tab: the filterable log list. Every row names its span
 * (a click filters to it) and carries that span's full id as a copyable control - the
 * one place a span's id lives, since the Spans tab tree shows none (too crowded with a
 * full id on every row); see spans.js's "N logs" toggle, which lands here with this
 * tab's own span filter already seeded.
 */
import {el, button} from '../../shared/dom.js';
import {formatTimeOfDay} from '../../shared/format.js';
import {LOG_LEVELS} from '../../shared/severity.js';
import {buildSpanNames} from '../../shared/span-names.js';
import {copyableId} from '../../shared/copyable.js';
import {emptyState} from '../../shared/components.js';

function logRow(log, spanNames, dateOptions, view, onFilterToSpan) {
    const spanId = log.spanId || '';

    const nameButton = button({
        className: 'pk-unbutton pk-log__span',
        text: spanNames.get(spanId) || spanId,
        title: spanId,
        attrs: {'data-span-id': spanId, 'aria-label': `Filter logs to span ${spanId}`}
    });
    nameButton.addEventListener('click', () => {
        if (spanId) onFilterToSpan(spanId);
    });

    const spanRow = el('span', {className: 'pk-log__span-row'}, nameButton);
    // Cross-link to the span's row in the Spans tab tree - distinct from the name
    // button beside it, which filters this list (see trace-detail.js's goToSpan).
    if (view.goToSpan && spanId) {
        const treeLink = button({
            className: 'pk-unbutton pk-icon-btn pk-log__goto-span',
            text: '⤶',
            title: 'Show this span in the span tree',
            attrs: {'data-span-id': spanId, 'aria-label': `Show span ${spanId} in the span tree`}
        });
        treeLink.addEventListener('click', () => view.goToSpan(spanId));
        spanRow.append(treeLink);
    }

    return el('div', {className: 'pk-log', attrs: {'data-level': log.level, 'data-span-id': spanId}},
        el('span', {className: 'pk-log__time', text: formatTimeOfDay(log.timestamp, dateOptions)}),
        el('span', {className: 'pk-log__span-cell'}, spanRow, copyableId(spanId, {label: 'spanId', truncate: true})),
        el('span', {className: `pk-log__level pk-log__level--${String(log.level).toLowerCase()}`, text: log.level}),
        el('span', {className: 'pk-log__message', text: log.message}));
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
        container.replaceChildren(emptyState('No logs recorded for this trace'));
        return;
    }

    // Single source of truth for the three filters - renderView() below renders the
    // controls FROM this, instead of the controls' own DOM values, so a re-render (the
    // span filter changing) cannot wipe out the text/level filters.
    //
    // state.level is validated against LOG_LEVELS (as trace-detail.js validates the
    // subview): an unrecognized value from the URL matches none of the <option>s, so the
    // <select> would show "All Levels" while applyFilters() kept filtering by the bogus
    // value underneath - a dropdown claiming no filter while silently hiding every row.
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

    function filterToSpan(spanId) {
        state.span = spanId;
        publishFilters();
        renderView();
    }

    function renderView() {
        const dateOptions = {locale: view.locale, timeZone: view.timeZone};
        const list = el('div', {attrs: {id: 'pk-logs-list'}},
            ...logs.map(log => logRow(log, spanNames, dateOptions, view, filterToSpan)));
        container.replaceChildren(filterBar(state, spanNames), list);

        // Establishes the initial visibility (filters restored from the URL, or a span
        // filter set before this render) before any control has fired an event.
        applyFilters(container, state);

        container.querySelector('#pk-log-filter').addEventListener('input', event => {
            state.q = event.target.value;
            applyFilters(container, state);
            publishFilters();
        });
        container.querySelector('#pk-log-level').addEventListener('change', event => {
            state.level = event.target.value;
            applyFilters(container, state);
            publishFilters();
        });
        container.querySelector('#pk-clear-span-filter')?.addEventListener('click', () => {
            state.span = null;
            publishFilters();
            renderView();
        });
    }

    renderView();
}

function filterBar(state, spanNames) {
    const input = el('input', {attrs: {type: 'text', placeholder: 'Filter logs...', 'aria-label': 'Filter logs', id: 'pk-log-filter'}});
    input.value = state.q;

    const select = el('select', {attrs: {id: 'pk-log-level', 'aria-label': 'Log level'}},
        el('option', {text: 'All Levels', attrs: {value: ''}}),
        ...LOG_LEVELS.map(level => el('option', {text: level})));
    select.value = state.level;

    const bar = el('div', {className: 'pk-logs-filter'}, input, select);
    if (state.span) bar.append(spanFilterChip(state.span, spanNames));
    return bar;
}

/** The active span filter as a chip: "name (shortId)", or the short id alone with the full id as its title. */
function spanFilterChip(spanId, spanNames) {
    const shortId = spanId.slice(0, 8);
    const spanName = spanNames.get(spanId);
    let label;
    let title;
    if (spanName) {
        const shortName = spanName.length > 20 ? spanName.substring(0, 20) + '...' : spanName;
        label = `${shortName} (${shortId})`;
    } else {
        label = shortId;
        title = spanId;
    }
    return el('span', {className: 'pk-logs-filter-span', title},
        `Span: ${label} `,
        button({
            className: 'pk-unbutton pk-logs-filter-span-clear',
            text: '×',
            attrs: {id: 'pk-clear-span-filter', 'aria-label': 'Clear span filter'}
        }));
}

function applyFilters(container, state) {
    const text = state.q.toLowerCase();
    container.querySelectorAll('.pk-log').forEach(item => {
        const message = item.querySelector('.pk-log__message').textContent.toLowerCase();
        const matchText = !text || message.includes(text);
        const matchLevel = !state.level || item.dataset.level === state.level;
        const matchSpan = !state.span || item.dataset.spanId === state.span;
        item.classList.toggle('pk-log--hidden', !(matchText && matchLevel && matchSpan));
    });
}

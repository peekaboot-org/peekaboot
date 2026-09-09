/**
 * Trace-detail overlay - Queries tab: the list of captured database queries. Each entry
 * carries its span's id (the backend's QueryInfo.spanId) as a stable anchor, so the
 * Spans tab's query link can land on it - and links back to that span in the span tree
 * via view.goToSpan (see trace-detail.js's jumpToElement).
 */
import {emptyState} from '../../shared/components.js';
import {el, button} from '../../shared/dom.js';
import {querySeverity, severityClass} from '../../shared/severity.js';
import {formatCount, formatDurationMs} from '../../shared/format.js';

export function render(container, trace, view = {}) {
    const queries = trace.queries || [];

    if (queries.length === 0) {
        container.replaceChildren(emptyState('No database queries recorded'));
        return;
    }

    container.replaceChildren(...queries.map((query, index) => queryItem(query, index, view)));
}

function queryItem(query, index, view) {
    const duration = query.durationMs || 0;
    const durationClass = querySeverity(duration, view.features);
    const system = query.dbSystem || 'SQL';
    const spanId = query.spanId || '';

    const meta = el('span', {className: 'pk-query-meta'},
        el('span', {
            className: 'pk-query__duration' + (durationClass ? ` ${severityClass(durationClass)}` : ''),
            text: formatDurationMs(duration) + (durationClass ? ' SLOW' : '')
        }));
    if (query.rowCount != null) {
        meta.append(el('span', {className: 'pk-query-rows', text: formatCount(Number(query.rowCount), 'row')}));
    }
    if (spanId && view.goToSpan) {
        const link = button({
            className: 'pk-span-action pk-query-span-link',
            text: '⤶',
            title: "Show this query's span in the span tree",
            attrs: {'data-span-id': spanId, 'aria-label': "Show this query's span in the span tree"}
        });
        // Fresh elements on every render, so per-element listeners cannot accumulate on the
        // shared tab-content container the way a delegated one would.
        link.addEventListener('click', () => view.goToSpan(spanId));
        meta.append(link);
    }

    return el('div', {className: 'pk-query-item', attrs: {'data-span-id': spanId || null}},
        el('div', {className: 'pk-query-header'},
            el('span', {className: 'pk-query-system', text: `${index + 1}. ${system.toUpperCase()}`}),
            meta),
        el('div', {className: 'pk-code-block', text: query.sql || 'Unknown query'}));
}

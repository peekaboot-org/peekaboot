/**
 * Trace-detail overlay - Queries tab: the list of captured database queries. Each entry
 * carries its span's id (the backend's QueryInfo.spanId) as a stable anchor, so the
 * Spans tab's query link can land on it - and links back to that span in the span tree
 * via view.goToSpan (see trace-detail.js's jumpToElement). Its statement is that span's
 * own `query`, the one copy the backend serves.
 */
import {emptyState} from '../../shared/components.js';
import {el} from '../../shared/dom.js';
import {sqlView} from '../../shared/sql-view.js';
import {querySeverity, severityClass} from '../../shared/severity.js';
import {formatCount, formatDurationMs} from '../../shared/format.js';
import {slowBadge} from '../../shared/trace-stats.js';
import {spanTreeLink} from '../span-tree-link.js';
import {spansOf} from './spans.js';

export function render(container, trace, view = {}) {
    const queries = trace.queries || [];

    if (queries.length === 0) {
        container.replaceChildren(emptyState('No database queries recorded'));
        return;
    }

    const statements = new Map(Array.from(spansOf(trace.rootSpan), span => [span.spanId, span.query]));
    container.replaceChildren(...queries.map((query, index) => queryItem(query, statements.get(query.spanId), index, view)));
}

function queryItem(query, statement, index, view) {
    const duration = query.durationMs || 0;
    const durationClass = querySeverity(duration, view.features);
    const spanId = query.spanId || '';

    const meta = el('span', {className: 'pk-query-meta'},
        el('span', {
            className: 'pk-query__duration' + (durationClass ? ` ${severityClass(durationClass)}` : ''),
            text: formatDurationMs(duration)
        }),
        durationClass ? slowBadge() : null);
    if (query.rowCount != null) {
        meta.append(el('span', {
            className: 'pk-query-rows',
            text: formatCount(Number(query.rowCount), 'row', {locale: view.locale})
        }));
    }
    if (spanId && view.goToSpan) {
        meta.append(spanTreeLink(spanId, view.goToSpan, "Show this query's span in the span tree"));
    }

    return el('div', {className: 'pk-query-item', attrs: {'data-span-id': spanId || null}},
        el('div', {className: 'pk-query-header'},
            el('span', {className: 'pk-query-system', text: `${index + 1}. ${queryLabel(query)}`}),
            meta),
        statement ? sqlView(statement) : el('div', {className: 'pk-code-block', text: 'Unknown query'}));
}

/** A query named by its DataSource alone already shows that name as its system, so it is not repeated. */
function queryLabel(query) {
    const system = (query.dbSystem || 'SQL').toUpperCase();
    const name = query.dataSourceName;
    return name && name.toUpperCase() !== system ? `${system} · ${name}` : system;
}

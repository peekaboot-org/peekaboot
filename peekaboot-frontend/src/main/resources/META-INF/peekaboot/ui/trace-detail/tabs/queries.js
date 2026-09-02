/**
 * Trace-detail overlay - Queries tab: the list of captured database queries. Each entry
 * carries its span's id (the backend's QueryInfo.spanId) as a stable anchor, so the
 * Spans tab's query link can land on it - and links back to that span in the span tree
 * via view.goToSpan (see trace-detail.js's jumpToElement).
 */
import {escapeHtml} from '../../shared/markup.js';
import {emptyStateHtml} from '../../shared/components.js';
import {querySeverity} from '../../shared/severity.js';
import {formatCount, formatDurationMs} from '../../shared/format.js';

export function render(container, trace, view = {}) {
    const queries = trace.queries || [];

    if (queries.length === 0) {
        container.innerHTML = emptyStateHtml('No database queries recorded');
        return;
    }

    let html = '';
    queries.forEach((query, idx) => {
        const sql = query.sql || 'Unknown query';
        const duration = query.durationMs || 0;
        const durationClass = querySeverity(duration, view.features);
        const system = query.dbSystem || 'SQL';
        const rowCount = query.rowCount;
        const spanId = query.spanId || '';

        html += `<div class="pk-query-item"${spanId ? ` data-span-id="${escapeHtml(spanId)}"` : ''}>`;
        html += '<div class="pk-query-header">';
        html += `<span class="pk-query-system">${idx + 1}. ${escapeHtml(system.toUpperCase())}</span>`;
        html += '<span class="pk-query-meta">';
        html += `<span class="pk-query__duration${durationClass ? ' pk-query__duration--' + durationClass : ''}">${formatDurationMs(duration)}${durationClass ? ' SLOW' : ''}</span>`;
        if (rowCount !== null && rowCount !== undefined) {
            html += `<span class="pk-query-rows">${formatCount(Number(rowCount), 'row')}</span>`;
        }
        if (spanId && view.goToSpan) {
            html += `<button type="button" class="pk-span-action pk-query-span-link" data-span-id="${escapeHtml(spanId)}"`
                + ` title="Show this query's span in the span tree"`
                + ` aria-label="Show this query's span in the span tree">&#10550;</button>`;
        }
        html += '</span>';
        html += '</div>';
        html += `<div class="pk-query__sql">${escapeHtml(sql)}</div>`;
        html += '</div>';
    });

    container.innerHTML = html;

    // Fresh elements on every render, so per-element listeners cannot accumulate on the
    // shared tab-content container the way a delegated one would.
    container.querySelectorAll('.pk-query-span-link').forEach(link => {
        link.addEventListener('click', () => view.goToSpan?.(link.dataset.spanId));
    });
}

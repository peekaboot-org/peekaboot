/**
 * Trace-detail overlay - Queries tab: the list of captured database queries.
 */
import {escapeHtml} from '../../shared/markup.js';
import {durationSeverity} from '../../shared/severity.js';

export function render(container, trace) {
    const queries = trace.queries || [];

    if (queries.length === 0) {
        container.innerHTML = '<div class="pk-empty">No database queries recorded</div>';
        return;
    }

    let html = '';
    queries.forEach((query, idx) => {
        const sql = query.sql || 'Unknown query';
        const duration = query.durationMs || 0;
        const durationClass = durationSeverity(duration);
        const system = query.dbSystem || 'SQL';
        const rowCount = query.rowCount;

        html += '<div class="pk-query-item">';
        html += '<div class="pk-query-header">';
        html += `<span class="pk-query-system">${idx + 1}. ${escapeHtml(system.toUpperCase())}</span>`;
        html += '<span class="pk-query-meta">';
        html += `<span class="pk-query__duration${durationClass ? ' pk-query__duration--' + durationClass : ''}">${duration}ms${durationClass ? ' SLOW' : ''}</span>`;
        if (rowCount !== null && rowCount !== undefined) {
            html += `<span class="pk-query-rows">${escapeHtml(String(rowCount))} rows</span>`;
        }
        html += '</span>';
        html += '</div>';
        html += `<div class="pk-query__sql">${escapeHtml(sql)}</div>`;
        html += '</div>';
    });

    container.innerHTML = html;
}

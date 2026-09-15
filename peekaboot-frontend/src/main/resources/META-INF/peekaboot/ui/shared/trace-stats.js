/**
 * The stat line a trace shows wherever it is summarised - the Traces tab's list rows and
 * the dev toolbar's bar: span count, query count with the total query time, log count,
 * then the error and warning log-level badges - the overlay's own Request/Spans/Queries/Logs
 * tab order. One builder so the two surfaces cannot drift in wording, pluralisation, order
 * or the thresholds that colour the query time. Returns detached elements; each surface
 * decides how to separate them.
 */
import {el} from './dom.js';
import {badge} from './components.js';
import {formatCount, formatDurationMs} from './format.js';
import {durationSeverity, logLevelVariant, severityClass} from './severity.js';

export function traceStatParts(trace, {features, locale} = {}) {
    const summary = trace.summary || {};
    const spans = summary.spans || {};
    const queries = summary.queries || {};
    const logs = summary.logs || {};
    const parts = [];
    if (spans.count > 0) {
        parts.push(el('span', {className: 'pk-stat', text: formatCount(spans.count, 'span', {locale})}));
    }
    if (queries.count > 0) {
        parts.push(durationStat(
                formatCount(queries.count, 'query', {plural: 'queries', locale}),
                queries.totalDurationMs,
                durationSeverity(queries.totalDurationMs, features)));
    }
    if (logs.count > 0) {
        parts.push(el('span', {className: 'pk-stat', text: formatCount(logs.count, 'log', {locale})}));
    }
    if (logs.errorCount > 0) {
        parts.push(badge(formatCount(logs.errorCount, 'error', {locale}), logLevelVariant('ERROR')));
    }
    if (logs.warnCount > 0) {
        parts.push(badge(formatCount(logs.warnCount, 'warning', {locale}), logLevelVariant('WARN')));
    }
    return parts;
}

/** The one wording for a trace that hit the max-spans-per-trace cap, wherever the trace is shown. */
export function truncatedBadge() {
    return badge('TRUNCATED', 'warn', {
        title: 'This trace hit the max-spans-per-trace cap - the oldest spans were dropped, so its span, query and log counts may be incomplete.'
    });
}

/**
 * A `.pk-stat`: `lead` (a string or an element, the count or an icon) followed by the
 * duration in monospace, coloured by `severity` (a severity.js suffix, or '').
 */
export function durationStat(lead, ms, severity) {
    const stat = document.createElement('span');
    stat.className = 'pk-stat';
    stat.append(lead, ' ');

    const duration = document.createElement('span');
    duration.className = 'pk-stat__duration' + (severity ? ` ${severityClass(severity)}` : '');
    duration.textContent = formatDurationMs(ms);
    stat.appendChild(duration);
    return stat;
}

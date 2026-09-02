/**
 * The stat line a trace shows wherever it is summarised - the Traces tab's list rows and
 * the dev toolbar's bar: the query count with the total query time, then the error and
 * warning log counts. One builder so the two surfaces cannot drift in wording,
 * pluralisation or the thresholds that colour the query time. Returns detached elements;
 * each surface decides how to separate them.
 */
import {badge} from './components.js';
import {formatCount, formatDurationMs} from './format.js';
import {durationSeverity, logLevelVariant} from './severity.js';

export function traceStatParts(trace, features) {
    const summary = trace.summary || {};
    const queries = summary.queries || {};
    const logs = summary.logs || {};
    const parts = [];
    if (queries.count > 0) parts.push(queryStat(queries, features));
    if (logs.errorCount > 0) parts.push(badge(formatCount(logs.errorCount, 'error'), logLevelVariant('ERROR')));
    if (logs.warnCount > 0) parts.push(badge(formatCount(logs.warnCount, 'warning'), logLevelVariant('WARN')));
    return parts;
}

function queryStat({count, totalDurationMs}, features) {
    const severity = durationSeverity(totalDurationMs, features);
    const stat = document.createElement('span');
    stat.className = 'pk-stat' + (severity ? ` pk-stat--${severity}` : '');
    stat.append(formatCount(count, 'query', 'queries'), ' ');

    const duration = document.createElement('span');
    duration.className = 'pk-stat__duration';
    duration.textContent = formatDurationMs(totalDurationMs || 0);
    stat.appendChild(duration);
    return stat;
}

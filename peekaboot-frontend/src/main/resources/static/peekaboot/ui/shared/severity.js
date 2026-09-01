/**
 * Where the dashboard, the toolbar and the trace-detail overlay decide how a duration, a
 * span's issues, a log level or a health status is coloured.
 *
 * The duration thresholds are the backend's effective ones, published once by
 * /api/features (see the backend's Features record) and handed in as `features` by every
 * caller that has them. DEFAULT_THRESHOLDS mirrors the backend defaults, keyed by the same
 * wire names, for the paths that have no features in hand - the dev toolbar, and the
 * overlay it opens.
 */
export const DEFAULT_THRESHOLDS = Object.freeze({
    slowSpanThresholdMs: 100,
    verySlowSpanThresholdMs: 500,
    slowQueryThresholdMs: 50,
    slowTraceThresholdMs: 1000
});

/** The published value when there is one, the default otherwise (slowTraceThresholdMs is null while tracing is off). */
export function threshold(features, key) {
    return features?.[key] ?? DEFAULT_THRESHOLDS[key];
}

/**
 * Severity of a latency, as a CSS modifier suffix, at the span thresholds. Deliberately
 * distinct from log-level vocabulary: 'warn' and 'error' describe log records and health
 * status, never how long something took. Strictly greater: a duration exactly at a
 * threshold is not yet slow. Where a span's own issues are in hand, issueSeverity() is the
 * backend's judgement and is preferred over re-deriving one here.
 */
export function durationSeverity(ms, features) {
    if (ms == null) return '';
    if (ms > threshold(features, 'verySlowSpanThresholdMs')) return 'very-slow';
    if (ms > threshold(features, 'slowSpanThresholdMs')) return 'slow';
    return '';
}

/** Every IssueType the backend raises, in the enum's order, and the severity suffix each one paints a duration with. */
const ISSUE_SEVERITIES = Object.freeze({
    SLOW: 'slow',
    VERY_SLOW: 'very-slow',
    ERROR: '',
    SLOW_QUERY: 'slow',
    HIGH_QUERY_COUNT: ''
});

export const ISSUE_TYPES = Object.keys(ISSUE_SEVERITIES);

/** The worst latency severity among a span's issues - the backend's own verdict on that span. */
export function issueSeverity(issues) {
    const severities = (issues || []).map(issue => ISSUE_SEVERITIES[issue.type] || '');
    if (severities.includes('very-slow')) return 'very-slow';
    if (severities.includes('slow')) return 'slow';
    return '';
}

/** The levels a captured log line can carry, most severe first - Logback's, minus OFF and ALL, which never label an event. */
export const LOG_LEVELS = Object.freeze(['ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE']);

/** A log level as a badge variant: ERROR/WARN/INFO map to their own tier; DEBUG, TRACE and unset are muted. */
export function logLevelVariant(level) {
    if (level === 'ERROR') return 'error';
    if (level === 'WARN') return 'warn';
    if (level === 'INFO') return 'info';
    return 'muted';
}

/** Actuator health status as a badge modifier suffix. */
export function healthSeverity(status) {
    if (status === 'UP') return 'ok';
    if (status === 'DOWN' || status === 'OUT_OF_SERVICE') return 'error';
    return 'muted';
}

const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

export function formatDurationMs(ms) {
    if (ms == null) return '-';
    if (ms < 0) return '-';
    if (ms < 1) return '<1ms';
    if (ms < 1000) return Math.round(ms) + 'ms';
    if (ms < 60000) return (ms / 1000).toFixed(2) + 's';
    return (ms / 60000).toFixed(2) + 'm';
}

/**
 * Sibling of formatDurationMs for spans too long for it to say anything useful (two days
 * would render as "2880.00m") - the three largest non-zero units ("1 day, 2 hours, 3
 * minutes"), plain seconds below a minute. Mirrors the backend's UptimeFormat.humanize
 * unit-by-unit so a run's duration reads identically in the log banner and this
 * dashboard; null and negative input differ, falling back to this module's own '-'
 * convention instead of the backend's "0 seconds".
 */
export function formatLongDuration(ms) {
    if (ms == null || ms < 0) return '-';

    const totalSeconds = Math.floor(ms / 1000);
    if (totalSeconds < 60) return formatCount(totalSeconds, 'second');

    const units = [
        ['day', Math.floor(totalSeconds / 86400)],
        ['hour', Math.floor((totalSeconds % 86400) / 3600)],
        ['minute', Math.floor((totalSeconds % 3600) / 60)],
        ['second', totalSeconds % 60]
    ];
    return units.filter(([, value]) => value > 0)
        .slice(0, 3)
        .map(([unit, value]) => formatCount(value, unit))
        .join(', ');
}

/** Compact interval label, e.g. 250 -> "250ms", 1500 -> "1.5s", 3600000 -> "1h", 172800000 -> "2d". */
export function formatInterval(ms) {
    const short = value => (Number.isInteger(value) ? String(value) : value.toFixed(1));
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60000) return `${short(ms / 1000)}s`;
    if (ms < 3600000) return `${short(ms / 60000)}m`;
    if (ms < 86400000) return `${short(ms / 3600000)}h`;
    return `${short(ms / 86400000)}d`;
}

export function formatBytes(bytes) {
    if (bytes == null || bytes < 0) return '-';
    if (bytes === 0) return '0 B';

    const exponent = bytes < 1
        ? 0
        : Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), BYTE_UNITS.length - 1);
    const value = bytes / Math.pow(1024, exponent);
    const decimals = value >= 100 ? 0 : (value >= 10 ? 1 : 2);

    return value.toFixed(decimals) + ' ' + BYTE_UNITS[exponent];
}

/** Formats API host objects ({hostname, port}) as "hostname:port, ...". */
export function formatHosts(hosts) {
    if (!hosts || hosts.length === 0) return 'unknown';
    return hosts.map(h => h.hostname + (h.port ? ':' + h.port : '')).join(', ');
}

const DEFAULT_DATE_OPTIONS = {
    year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
};

/**
 * Formats a date/time value. Passing any option beyond locale/timeZone fully
 * replaces DEFAULT_DATE_OPTIONS rather than merging with it — callers that want
 * a single custom field (e.g. just dateStyle) must supply the complete option
 * set they want, not a partial override.
 */
export function formatDateTime(value, {locale, timeZone, ...options} = {}) {
    if (value == null || value === '') return '-';
    const date = new Date(value);
    // toLocaleDateString does not throw on an invalid Date - it returns the
    // literal string "Invalid Date" - so this must be checked explicitly rather
    // than relying on the try/catch below, which only guards a malformed
    // locale/timeZone.
    if (Number.isNaN(date.getTime())) return String(value);
    try {
        const resolved = Object.keys(options).length > 0 ? options : DEFAULT_DATE_OPTIONS;
        return date.toLocaleDateString(locale, timeZone ? {...resolved, timeZone} : resolved);
    } catch {
        return String(value);
    }
}

/** value.toPrecision(digits), stripped back to a plain number string (no trailing zeros/exponent for typical magnitudes). */
function toSignificant(value, digits) {
    if (value === 0) return '0';
    return Number(value.toPrecision(digits)).toString();
}

function formatMetricCount(value) {
    return Number.isInteger(value) ? String(value) : value.toFixed(2);
}

/** Formats "n noun(s)" - plural defaults to singular + 's', override it for irregular nouns (e.g. 'query'/'queries'). */
export function formatCount(n, singular, plural = singular + 's') {
    return `${n} ${n === 1 ? singular : plural}`;
}

/**
 * Formats one insights series/measurement value per its configured unit (see
 * InsightsConfigResponse.Series/Panel.unit): bytes/millis reuse the existing
 * byte/duration formatters, percent is raw 0..1 and scaled for display, persec/
 * bytes-persec append a rate suffix, count falls back to formatMetricCount().
 */
export function formatMetricValue(value, unit) {
    if (value === null || value === undefined || Number.isNaN(value)) return '-';

    switch (unit) {
        case 'bytes': return formatBytes(value);
        case 'percent': return (value * 100).toFixed(1) + '%';
        case 'millis': return formatDurationMs(value);
        case 'persec': return toSignificant(value, 2) + '/s';
        case 'bytes-persec': return formatBytes(value) + '/s';
        case 'count':
        default: return formatMetricCount(value);
    }
}

/**
 * Formats one insights tile value per its configured format (see
 * InsightsConfigResponse.Tile.format). duration/datetime tile values are seconds
 * server-side (see InsightsService), hence the *1000 before handing off to the
 * millisecond-based formatDurationMs/formatDateTime.
 */
export function formatTileValue(value, format, {locale, timeZone} = {}) {
    if (value === null || value === undefined || Number.isNaN(value)) return '-';

    switch (format) {
        case 'duration': return formatDurationMs(value * 1000);
        case 'datetime': return formatDateTime(value * 1000, {locale, timeZone});
        case 'bytes': return formatBytes(value);
        case 'count':
        default: return formatMetricCount(value);
    }
}

export function formatTimeOfDay(value, {locale, timeZone} = {}) {
    if (value == null || value === '') return '-';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return String(value);
    const options = {
        hour12: false, hour: '2-digit', minute: '2-digit',
        second: '2-digit', fractionalSecondDigits: 3
    };
    try {
        return date.toLocaleTimeString(locale, timeZone ? {...options, timeZone} : options);
    } catch {
        return String(value).substring(11, 23);
    }
}

const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

export function formatDurationMs(ms) {
    if (ms == null) return '-';
    if (ms < 0) return '-';
    if (ms < 1) return '<1ms';
    if (ms < 1000) return Math.round(ms) + 'ms';
    if (ms < 60000) return (ms / 1000).toFixed(2) + 's';
    return (ms / 60000).toFixed(2) + 'm';
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

function formatCount(value) {
    return Number.isInteger(value) ? String(value) : value.toFixed(2);
}

/**
 * Formats one insights series/measurement value per its configured unit (see
 * InsightsConfigResponse.Series/Panel.unit): bytes/millis reuse the existing
 * byte/duration formatters, percent is raw 0..1 and scaled for display, persec/
 * bytes-persec append a rate suffix, count falls back to formatCount().
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
        default: return formatCount(value);
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
        default: return formatCount(value);
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

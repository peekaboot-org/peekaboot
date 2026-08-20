const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

export function formatDurationMs(ms) {
    if (ms == null) return '-';
    if (ms < 1) return '<1ms';
    if (ms < 1000) return Math.round(ms) + 'ms';
    if (ms < 60000) return (ms / 1000).toFixed(2) + 's';
    return (ms / 60000).toFixed(2) + 'm';
}

export function formatBytes(bytes) {
    if (bytes == null || bytes < 0) return '-';
    if (bytes === 0) return '0 B';

    const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), BYTE_UNITS.length - 1);
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

export function formatDateTime(value, {locale, timeZone, ...options} = {}) {
    if (!value) return '-';
    try {
        const resolved = Object.keys(options).length > 0 ? options : DEFAULT_DATE_OPTIONS;
        return new Date(value).toLocaleDateString(locale, timeZone ? {...resolved, timeZone} : resolved);
    } catch {
        return String(value);
    }
}

export function formatTimeOfDay(value, {locale, timeZone} = {}) {
    if (!value) return '-';
    const options = {
        hour12: false, hour: '2-digit', minute: '2-digit',
        second: '2-digit', fractionalSecondDigits: 3
    };
    try {
        return new Date(value).toLocaleTimeString(locale, timeZone ? {...options, timeZone} : options);
    } catch {
        return String(value).substring(11, 23);
    }
}

const DURATION_UNITS = [
    {unit: 'ms', size: 1, decimals: 0},
    {unit: 's', size: 1000, decimals: 2},
    {unit: 'm', size: 60000, decimals: 2},
    {unit: 'h', size: 3600000, decimals: 2},
    {unit: 'd', size: 86400000, decimals: 2}
];
const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB'].map((unit, exponent) => ({unit, size: 1024 ** exponent}));
// A meter's seconds, counted in microseconds.
const TIMER_UNITS = [
    {unit: 'µs', size: 1, decimals: 0},
    {unit: 'ms', size: 1000, decimals: 2},
    {unit: 's', size: 1000000, decimals: 3}
];

/** A duration or a size: a finite number from zero up. */
function isAmount(value) {
    return Number.isFinite(value) && value >= 0;
}

/**
 * toFixed's rounding as a number. Intl rounds 59.995 up where toFixed rounds it down, and
 * the figure a unit is chosen by has to be the figure shown.
 */
function rounded(value, decimals) {
    return Number(value.toFixed(decimals));
}

/** `value` with `decimals` decimals behind `locale`'s decimal mark, ungrouped: a unit follows it. */
function formatDecimal(value, decimals, locale) {
    return rounded(value, decimals).toLocaleString(locale, {
        minimumFractionDigits: decimals,
        maximumFractionDigits: decimals,
        useGrouping: false
    });
}

/**
 * `value` in the smallest unit of `ladder` in which it, rounded to the decimals it is shown
 * with, stays below one of the next unit: 999.6ms is 1.00s, where choosing the unit before
 * rounding gives "1000ms". `decimalsOf` is for a ladder whose decimals follow the figure.
 */
function onLadder(value, ladder, decimalsOf = (amount, step) => step.decimals) {
    const last = ladder.length - 1;
    for (let index = 0; ; index++) {
        const {unit, size} = ladder[index];
        const amount = value / size;
        const decimals = decimalsOf(amount, ladder[index]);
        if (index === last || rounded(amount, decimals) < ladder[index + 1].size / size) {
            return {unit, size, amount, decimals};
        }
    }
}

export function formatDurationMs(ms, {locale} = {}) {
    if (!isAmount(ms)) return '-';
    if (ms < 1) return '<1ms';
    const {unit, amount, decimals} = onLadder(ms, DURATION_UNITS);
    return formatDecimal(amount, decimals, locale) + unit;
}

/**
 * The five labels of a timeline axis from 0 to totalMs, one per quarter. All five share the
 * unit formatDurationMs would give totalMs, with as many decimals as keep neighbours apart:
 * rounding each quarter on its own turns a 3ms trace's 2.25ms into "2ms", next to 1.5ms's.
 */
export function formatTimelineTicks(totalMs, {locale} = {}) {
    const {unit, size, decimals: minDecimals} = onLadder(totalMs, DURATION_UNITS);
    const step = totalMs / size / 4;
    let decimals = minDecimals;
    while (step > 0 && step < 10 ** -decimals) decimals++;
    return [0, 1, 2, 3, 4].map(quarter => (quarter === 0 ? '0' : formatDecimal(step * quarter, decimals, locale)) + unit);
}

/**
 * Sibling of formatDurationMs for a span read in words, where one figure ("1.09d") says
 * too little - the three largest non-zero units ("1 day, 2 hours, 3 minutes"), plain
 * seconds below a minute. Mirrors the backend's UptimeFormat.humanize
 * unit-by-unit so a run's duration reads identically in the log banner and this
 * dashboard; null and negative input differ, falling back to this module's own '-'
 * convention instead of the backend's "0 seconds", as does a run past 999 days, which is
 * grouped here (formatCount) and not there.
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

/** Two below 10, one below 100, none from there, judged on the figure as shown: 99.96 is "100", not "100.0". */
function sizeDecimals(amount) {
    if (rounded(amount, 2) < 10) return 2;
    if (rounded(amount, 1) < 100) return 1;
    return 0;
}

export function formatBytes(bytes, {locale} = {}) {
    if (!isAmount(bytes)) return '-';
    if (bytes === 0) return '0 B';
    const {unit, amount, decimals} = onLadder(bytes, BYTE_UNITS, sizeDecimals);
    return formatDecimal(amount, decimals, locale) + ' ' + unit;
}

/** Formats API host objects ({hostname, port}) as "hostname:port, ...". */
export function formatHosts(hosts) {
    if (!hosts || hosts.length === 0) return 'unknown';
    return hosts.map(h => h.hostname + (h.port ? ':' + h.port : '')).join(', ');
}

const DEFAULT_DATE_OPTIONS = {
    year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
};
const TIME_OF_DAY_OPTIONS = {
    hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3
};

/**
 * An ISO-8601 instant as epoch milliseconds, the digits past the millisecond dropped the way
 * Java's toEpochMilli drops them: WebKit's Date.parse rounds them instead. NaN for a value
 * that is no date string.
 */
export function instantMs(instant) {
    return typeof instant === 'string' ? Date.parse(instant.replace(/(\.\d{3})\d+/, '$1')) : NaN;
}

/** A date/time in the dashboard's usual shape (date, hour and minute), in the reader's locale and timezone. */
export function formatDateTime(value, display = {}) {
    return formatDateTimeWith(value, DEFAULT_DATE_OPTIONS, display);
}

/** The time alone, to the millisecond - the overlay's log rows. */
export function formatTimeOfDay(value, display = {}) {
    return formatDateTimeWith(value, TIME_OF_DAY_OPTIONS, display);
}

/**
 * A date/time with exactly the Intl.DateTimeFormat `options` given - the whole set, not
 * a delta on the default: only the fields named are rendered, so a time-only set yields
 * a time-only string. `display` is the reader's {locale, timeZone}.
 */
export function formatDateTimeWith(value, options, {locale, timeZone} = {}) {
    if (value == null || value === '') return '-';
    const date = new Date(typeof value === 'string' ? instantMs(value) : value);
    // toLocaleString does not throw on an invalid Date - it returns the
    // literal string "Invalid Date" - so this must be checked explicitly rather
    // than relying on the try/catch below, which only guards a malformed
    // locale/timeZone.
    if (Number.isNaN(date.getTime())) return String(value);
    try {
        return date.toLocaleString(locale, timeZone ? {...options, timeZone} : options);
    } catch {
        return String(value);
    }
}

/** `value` to `digits` significant digits in `locale`, ungrouped, with no trailing zeros and no exponent. */
function toSignificant(value, digits, locale) {
    return value.toLocaleString(locale, {maximumSignificantDigits: digits, useGrouping: false});
}

function formatMetricCount(value, locale) {
    return Number.isInteger(value)
        ? value.toLocaleString(locale)
        : value.toLocaleString(locale, {minimumFractionDigits: 2, maximumFractionDigits: 2});
}

/** A property or detail value as one line of text: '-' for nothing, JSON for a structured value. */
export function formatPlainValue(value) {
    if (value === null || value === undefined) return '-';
    if (typeof value === 'object') return JSON.stringify(value);
    return String(value);
}

/**
 * Compares two `[key, value]` entries by key in code-point order, not localeCompare's:
 * collation is the reader's browser setting, and two readers must not see the same trace's
 * headers, parameters or tags in different orders.
 */
export const byKey = ([a], [b]) => (a < b ? -1 : a > b ? 1 : 0);

/** A dotted Java name (a scheduled task's target, a controller's class and method) shortened to its last two segments. */
export function shortTarget(qualified) {
    return qualified.split('.').slice(-2).join('.');
}

/** `Number(n).toLocaleString(locale)` - grouped in `locale`, or the reader's browser locale when it is undefined. */
export function formatNumber(n, {locale} = {}) {
    return Number(n).toLocaleString(locale);
}

/**
 * Formats "n noun(s)" - plural defaults to singular + 's', override it for irregular nouns
 * (e.g. 'query'/'queries'). The number is grouped in `locale`, or the reader's browser
 * locale when it is undefined, which a row count in the tens of thousands needs to be
 * readable at all.
 */
export function formatCount(n, singular, {plural = singular + 's', locale} = {}) {
    return `${formatNumber(n, {locale})} ${n === 1 ? singular : plural}`;
}

/** The units a series or panel can carry (the backend's Unit enum, by wire name), and the formats a tile can (TileFormat). */
export const METRIC_UNITS = Object.freeze(['bytes', 'percent', 'millis', 'count', 'persec', 'bytes-persec']);
export const TILE_FORMATS = Object.freeze(['duration', 'datetime', 'bytes', 'count']);

/**
 * Formats one insights series/measurement value per its configured unit (see
 * InsightsConfigResponse.Series/Panel.unit): bytes/millis reuse the existing
 * byte/duration formatters, percent arrives as 0..100 and gains its sign, persec/
 * bytes-persec append a rate suffix, count falls back to formatMetricCount(). Every figure
 * is written in `locale`, and only a count is grouped.
 */
export function formatMetricValue(value, unit, {locale} = {}) {
    if (value === null || value === undefined || Number.isNaN(value)) return '-';

    switch (unit) {
        case 'bytes': return formatBytes(value, {locale});
        case 'percent': return formatDecimal(value, 1, locale) + '%';
        case 'millis': return formatDurationMs(value, {locale});
        case 'persec': return toSignificant(value, 2, locale) + '/s';
        case 'bytes-persec': return formatBytes(value, {locale}) + '/s';
        case 'count':
        default: return formatMetricCount(value, locale);
    }
}

/**
 * One statistic of a meter by the meter's base unit: bytes as a size, seconds by magnitude
 * with the sign kept, anything else as a number. Apart from formatMetricValue's rule on
 * purpose: a raw statistic needs a small value's precision, which a chart axis has no room for.
 */
export function formatMeasurementValue(value, baseUnit, {locale} = {}) {
    if (value === null || value === undefined || Number.isNaN(value)) return '-';

    if (baseUnit === 'bytes') return formatBytes(value, {locale});

    if (baseUnit === 'seconds') {
        const {unit, amount, decimals} = onLadder(Math.abs(value) * 1000000, TIMER_UNITS);
        return `${value < 0 ? '-' : ''}${formatDecimal(amount, decimals, locale)} ${unit}`;
    }

    if (Math.abs(value) >= 1000) return value.toLocaleString(locale, {maximumFractionDigits: 2});
    if (value !== 0 && Math.abs(value) < 0.01) return value.toExponential(2);
    return value.toLocaleString(locale, {maximumFractionDigits: 4});
}

/**
 * Formats one insights tile value per its configured format (see
 * InsightsConfigResponse.Tile.format). duration and datetime tile values are milliseconds,
 * a duration and an epoch instant, as the millisecond-based formatters take them; bytes and
 * count are formatMetricValue's own.
 */
export function formatTileValue(value, format, display = {}) {
    if (value === null || value === undefined || Number.isNaN(value)) return '-';

    switch (format) {
        case 'duration': return formatMetricValue(value, 'millis', display);
        case 'datetime': return formatDateTime(value, display);
        default: return formatMetricValue(value, format, display);
    }
}

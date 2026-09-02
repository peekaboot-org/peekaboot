/**
 * The Insights tab's client-side mirror of the server's ring buffers: one snapshot per
 * loaded level, kept current by the tick/rollup events instead of by polling. Pure data
 * code with no DOM, so the browser tests can drive it directly.
 */
export const STAT_NAMES = ['min', 'max', 'avg', 'median', 'p90', 'p95', 'p99'];

/**
 * The /data response as a mutable mirror of the server ring: {@code series} holds
 * either the raw values (level 0) or one array per stat (levels >= 1), and grows
 * by one sample per tick/rollup until it reaches `size`, the level's configured size.
 */
export function normalizeLevel(body, size = body.count) {
    const series = {};
    Object.entries(body.series).forEach(([key, entry]) => {
        series[key] = body.level === 0 ? (entry.values ?? []) : normalizeStats(entry.stats);
    });
    return {
        level: body.level,
        intervalMs: body.intervalMs,
        endEpochMs: body.endEpochMs,
        count: body.count,
        size,
        series
    };
}

function normalizeStats(stats) {
    const result = {};
    STAT_NAMES.forEach(name => {
        result[name] = stats?.[name] ?? [];
    });
    return result;
}

/** The latest non-null value of a series, or null when it never carried one. */
export function lastValue(values) {
    if (!Array.isArray(values)) return null;
    for (let i = values.length - 1; i >= 0; i--) {
        if (values[i] != null) return values[i];
    }
    return null;
}

function numberOrNull(value) {
    return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function pushCapped(values, value, size) {
    values.push(value);
    while (values.length > size) values.shift();
}

function seriesArray(snapshot, key) {
    if (!snapshot.series[key]) {
        snapshot.series[key] = snapshot.level === 0
            ? new Array(snapshot.count).fill(null)
            : normalizeStats(null);
    }
    return snapshot.series[key];
}

/**
 * An event is only applicable if it is newer than what the mirrored ring already
 * holds: a /data snapshot loaded while events were in flight (first subscribe, or
 * a reconnect resync) already contains them, and appending them again would
 * duplicate samples and shift the whole history.
 */
function isStale(snapshot, event) {
    return snapshot.endEpochMs > 0 && event.epochMs <= snapshot.endEpochMs;
}

/**
 * Boundaries the server skipped between the ring's end and this event. Samples
 * carry no timestamps - sample i sits (count - 1 - i) intervals before endEpochMs -
 * so a gap has to be pushed as nulls or every older sample shifts forward in time.
 * Capped at the ring size; beyond that everything visible is a gap anyway.
 */
export function missedSamples(snapshot, event) {
    if (!snapshot.endEpochMs || !snapshot.intervalMs) return 0;
    const missed = Math.floor((event.epochMs - snapshot.endEpochMs) / snapshot.intervalMs) - 1;
    return Math.max(0, Math.min(missed, snapshot.size));
}

/**
 * Appends one sample to every series of the snapshot, gap nulls first. `keys` are the
 * series the event names (a new one starts as an all-null ring); `pushInto(key, series,
 * push)` decides which array(s) of a series a value lands in.
 */
function appendSample(snapshot, event, keys, pushInto) {
    if (!snapshot || isStale(snapshot, event)) return;
    keys.forEach(key => seriesArray(snapshot, key));
    const missed = missedSamples(snapshot, event);
    const push = (values, value) => {
        for (let i = 0; i < missed; i++) pushCapped(values, null, snapshot.size);
        pushCapped(values, numberOrNull(value), snapshot.size);
    };
    Object.entries(snapshot.series).forEach(([key, series]) => pushInto(key, series, push));
    snapshot.count = Math.min(snapshot.count + missed + 1, snapshot.size);
    snapshot.endEpochMs = event.epochMs;
}

/** Applies a level-0 tick ({epochMs, values: {seriesId: value}}) to its snapshot. */
export function appendTick(snapshot, event) {
    const values = event.values ?? {};
    appendSample(snapshot, event, Object.keys(values), (key, series, push) => push(series, values[key]));
}

/** Applies a rollup ({epochMs, entries: {seriesId: {min, max, ...}}}) to the snapshot of its level. */
export function appendRollup(snapshot, event) {
    const entries = event.entries ?? {};
    appendSample(snapshot, event, Object.keys(entries), (key, stats, push) =>
        STAT_NAMES.forEach(name => push(stats[name], entries[key]?.[name])));
}

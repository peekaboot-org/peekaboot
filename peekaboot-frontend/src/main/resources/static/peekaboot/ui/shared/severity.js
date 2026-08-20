/** A span or query slower than this is worth noticing. */
export const SLOW_MS = 100;
/** Slower than this is a problem. */
export const VERY_SLOW_MS = 500;

/**
 * Severity of a latency, as a CSS modifier suffix. Deliberately distinct from
 * log-level vocabulary: 'warn' and 'error' describe log records and health
 * status, never how long something took.
 */
export function durationSeverity(ms) {
    if (ms == null) return '';
    if (ms > VERY_SLOW_MS) return 'very-slow';
    if (ms > SLOW_MS) return 'slow';
    return '';
}

/** Actuator health status as a badge modifier suffix. */
export function healthSeverity(status) {
    if (status === 'UP') return 'ok';
    if (status === 'DOWN') return 'error';
    return 'muted';
}

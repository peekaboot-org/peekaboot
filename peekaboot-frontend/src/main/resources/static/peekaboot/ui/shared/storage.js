/**
 * Guarded localStorage access for per-browser settings (theme, locale, timezone).
 * Storage can be blocked - private browsing, some embedded/iframe contexts, strict
 * cookie policies - and a throw during module evaluation would blank the whole
 * surface before any code runs, so both directions swallow the failure.
 */

/** The stored value, or null when nothing is stored or storage is unavailable. */
export function readSetting(key) {
    try {
        return localStorage.getItem(key);
    } catch {
        return null;
    }
}

/** Stores a value; when storage is unavailable the setting simply will not persist. */
export function writeSetting(key, value) {
    try {
        localStorage.setItem(key, value);
    } catch {
        /* preference simply will not persist */
    }
}

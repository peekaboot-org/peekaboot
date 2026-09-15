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

// The key the dashboard's locale select writes, shared across surfaces via same-origin
// storage the way theme.js's own key shares the theme.
export const LOCALE_STORAGE_KEY = 'peekaboot-locale';

/**
 * The stored locale tag, kept only if Intl actually accepts it - a stale non-BCP-47 value
 * (e.g. an old build's 'en_US') would otherwise reach every toLocaleString call as a
 * RangeError. Null for nothing stored, a blocked store, or an invalid tag, so every
 * caller's own fallback (the browser's locale) applies the same way.
 */
export function readLocaleSetting() {
    const value = readSetting(LOCALE_STORAGE_KEY);
    if (!value) return null;
    try {
        return Intl.NumberFormat.supportedLocalesOf([value]).length > 0 ? value : null;
    } catch {
        return null;
    }
}

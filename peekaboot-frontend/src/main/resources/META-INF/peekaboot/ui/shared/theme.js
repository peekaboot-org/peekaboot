import {readSetting, writeSetting} from './storage.js';

// The key the dashboard toggle writes, shared across surfaces via same-origin storage;
// assets/theme-boot.js, the dashboard's pre-paint stamp, reads the same literal.
const THEME_STORAGE_KEY = 'peekaboot-theme';

const DARK_QUERY = '(prefers-color-scheme: dark)';

function storedTheme() {
    const stored = readSetting(THEME_STORAGE_KEY);
    return stored === 'light' || stored === 'dark' ? stored : null;
}

/** The stored preference if there is one, otherwise the OS preference. */
export function resolveTheme() {
    return storedTheme() || (window.matchMedia(DARK_QUERY).matches ? 'dark' : 'light');
}

/** Applies a theme to a document element or to a shadow host element. */
export function applyTheme(target, theme) {
    target.setAttribute('data-theme', theme);
}

export function storeTheme(theme) {
    writeSetting(THEME_STORAGE_KEY, theme);
}

/**
 * Invokes callback(theme) whenever the effective theme changes: the OS
 * preference flipping, or another tab writing the stored preference.
 * Returns an unsubscribe function.
 */
export function watchTheme(callback) {
    const media = window.matchMedia(DARK_QUERY);
    const onMedia = () => callback(resolveTheme());
    const onStorage = (event) => {
        if (event.key === THEME_STORAGE_KEY) callback(resolveTheme());
    };

    media.addEventListener('change', onMedia);
    window.addEventListener('storage', onStorage);

    return () => {
        media.removeEventListener('change', onMedia);
        window.removeEventListener('storage', onStorage);
    };
}

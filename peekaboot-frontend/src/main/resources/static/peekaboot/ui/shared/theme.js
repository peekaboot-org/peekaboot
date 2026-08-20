/** The key the dashboard toggle writes. Shared across surfaces via same-origin storage. */
export const THEME_STORAGE_KEY = 'peekaboot-theme';

const DARK_QUERY = '(prefers-color-scheme: dark)';

function storedTheme() {
    try {
        const stored = localStorage.getItem(THEME_STORAGE_KEY);
        return stored === 'light' || stored === 'dark' ? stored : null;
    } catch {
        return null;   // storage can be blocked; fall through to the OS preference
    }
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
    try {
        localStorage.setItem(THEME_STORAGE_KEY, theme);
    } catch {
        /* preference simply will not persist */
    }
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

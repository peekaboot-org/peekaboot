/**
 * Theme-token colour resolution for the Insights charts, shared by the chart and its
 * marker layer. Read from the live document styles so a chart follows the active theme.
 */

/**
 * The light-theme value of every token a chart draws with, for a document whose
 * tokens.css has not applied (blocked, 404, a stale cache): one copy, mirroring
 * tokens.css's light block, instead of a literal beside every read.
 */
export const LIGHT_FALLBACKS = Object.freeze({
    '--pk-primary-text': '#447718',
    '--pk-info-text': '#0a6e7f',
    '--pk-warning-text': '#9a5e06',
    '--pk-purple': '#7c3aed',
    '--pk-danger': '#d21f1f',
    '--pk-text-muted': '#626c79',
    '--pk-border': '#d1d5db',
    '--pk-font': 'system-ui, sans-serif'
});

/** The document's value of a --pk-* custom property, or its light-theme fallback when it is unset. */
export function themeToken(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || LIGHT_FALLBACKS[name];
}

/**
 * `hex` (3- or 6-digit) with a two-hex-digit alpha appended, for a translucent fill
 * derived from a stroke; any other colour syntax yields `fallback`.
 */
export function withAlpha(hex, alpha, fallback) {
    if (/^#[0-9a-f]{6}$/i.test(hex)) return hex + alpha;
    if (/^#[0-9a-f]{3}$/i.test(hex)) {
        return '#' + [...hex.slice(1)].map(c => c + c).join('') + alpha;
    }
    return fallback;
}

/**
 * Theme-token colour resolution for the Insights charts, shared by the chart and its
 * marker layer. Read from the live document styles so a chart follows the active theme.
 */

/** The document's value of a --pk-* custom property, or `fallback` when it is unset. */
export function themeToken(name, fallback) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback;
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

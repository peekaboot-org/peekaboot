const HTML_ESCAPES = {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'};

/**
 * Fallback for the backend's mask literal, for the surfaces that never load
 * /api/features - the dev toolbar and the overlay it opens. Everywhere else
 * Features.maskLiteral is authoritative; this copy must match MaskingEngine.MASK_LITERAL
 * (which mirrors Spring's own Sanitizer) exactly, or a masked-value comparison on those
 * surfaces silently stops matching (the browser tests pin the two together).
 */
export const MASK_LITERAL = '******';

/** Escapes text for safe interpolation into an HTML string. */
export function escapeHtml(text) {
    if (text == null) return '';
    return String(text).replace(/[&<>"']/g, c => HTML_ESCAPES[c]);
}

/**
 * Escapes text and wraps every case-insensitive occurrence of query in <mark>. Matched on
 * the original string with Unicode case folding, so a character whose lower-case form is
 * longer (Turkish dotted I) cannot shift the marks off the characters that matched.
 */
export function highlightText(text, query) {
    if (!query) return escapeHtml(text);

    const value = String(text);
    const pattern = new RegExp(query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'giu');

    let result = '';
    let lastIndex = 0;
    for (const match of value.matchAll(pattern)) {
        result += escapeHtml(value.slice(lastIndex, match.index));
        result += `<mark>${escapeHtml(match[0])}</mark>`;
        lastIndex = match.index + match[0].length;
    }

    return result + escapeHtml(value.slice(lastIndex));
}

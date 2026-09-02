const HTML_ESCAPES = {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'};

/**
 * Fallback for the backend's mask literal, for the surfaces that never load
 * /api/features - the dev toolbar and the overlay it opens. Everywhere else
 * Features.maskLiteral is authoritative; this copy must match MaskingEngine.MASK_LITERAL
 * (which mirrors Spring's own Sanitizer) exactly, or a masked-value comparison on those
 * surfaces silently stops matching (SharedModuleIT pins the two together).
 */
export const MASK_LITERAL = '******';

/** Escapes text for safe interpolation into an HTML string. */
export function escapeHtml(text) {
    if (text == null) return '';
    return String(text).replace(/[&<>"']/g, c => HTML_ESCAPES[c]);
}

/** Escapes text and wraps every case-insensitive occurrence of query in <mark>. */
export function highlightText(text, query) {
    if (!query) return escapeHtml(text);

    const value = String(text);
    const haystack = value.toLowerCase();
    const needle = query.toLowerCase();

    let result = '';
    let lastIndex = 0;
    let index = haystack.indexOf(needle);

    while (index !== -1) {
        result += escapeHtml(value.substring(lastIndex, index));
        result += `<mark>${escapeHtml(value.substring(index, index + query.length))}</mark>`;
        lastIndex = index + query.length;
        index = haystack.indexOf(needle, lastIndex);
    }

    return result + escapeHtml(value.substring(lastIndex));
}

const HTML_ESCAPES = {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'};

/**
 * The backend's mask literal (MaskingRules.MASK, mirroring Spring's own Sanitizer).
 * A single source of truth here so a future literal change (this has happened once
 * already - an earlier eight-star frontend copy silently stopped matching this
 * six-star value) only needs updating in one place.
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

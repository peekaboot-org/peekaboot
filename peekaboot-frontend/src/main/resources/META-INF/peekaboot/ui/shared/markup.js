/**
 * Splits text at every case-insensitive occurrence of query into `[{text, match}]`, in
 * order, empty parts left out. Matched on the original string with Unicode case folding,
 * so a character whose lower-case form is longer (Turkish dotted I) cannot shift a match
 * off the characters that matched. The query is literal: its regex metacharacters mean
 * themselves.
 */
export function highlightParts(text, query) {
    const value = text == null ? '' : String(text);
    const parts = [];
    const add = (part, match) => {
        if (part) parts.push({text: part, match});
    };

    let lastIndex = 0;
    for (const match of query ? value.matchAll(literalPattern(query)) : []) {
        add(value.slice(lastIndex, match.index), false);
        add(match[0], true);
        lastIndex = match.index + match[0].length;
    }
    add(value.slice(lastIndex), false);

    return parts;
}

function literalPattern(query) {
    return new RegExp(query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'giu');
}

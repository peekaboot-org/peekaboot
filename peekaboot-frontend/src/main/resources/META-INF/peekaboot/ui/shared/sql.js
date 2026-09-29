/**
 * SQL for display: a tokenizer, a renderer that turns tokens into highlighted DOM, and the
 * placeholder substitution the trace overlay offers. Display only - it never decides what a
 * statement means, so a dialect construct it misreads costs a colour, never a value.
 *
 * Lossless by construction: every character lands in exactly one token, so the tokens always
 * join back to the input, and what the copy button copies is what the reader sees.
 */
import {el} from './dom.js';

const KEYWORDS = new Set(`select from where and or not in is null like ilike between exists
    join inner left right full outer cross natural on using as distinct all any some
    insert into values update set delete merge upsert returning conflict do nothing
    group by having order asc desc nulls first last limit offset fetch next rows row only top
    union intersect except case when then else end cast true false
    with recursive over partition window filter lateral
    create alter drop table index view sequence primary key foreign references constraint
    default unique check for share nowait skip locked begin commit rollback call`.split(/\s+/));

/** Tried in order at each position; the first match wins. `word` becomes `keyword` or `identifier`. */
const RULES = [
    ['whitespace', /\s+/y],
    ['comment', /--[^\n]*/y],
    ['comment', /\/\*[\s\S]*?(?:\*\/|$)/y],
    ['string', /'(?:[^']|'')*(?:'|$)/y],
    ['quoted-identifier', /"(?:[^"]|"")*(?:"|$)/y],
    ['quoted-identifier', /`[^`]*(?:`|$)/y],
    ['number', /(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?/y],
    ['placeholder', /\?/y],
    ['word', /[\p{L}_][\p{L}\p{N}_$]*/uy],
    ['operator', /<>|<=|>=|!=|\|\||::|[-+*/%=<>!|&^~]/y],
    ['punctuation', /[(),;.[\]{}]/y],
];

const PLAIN = new Set(['whitespace', 'identifier']);

export function tokenize(sql) {
    const tokens = [];
    let index = 0;
    while (index < sql.length) {
        const token = tokenAt(sql, index);
        tokens.push(token);
        index += token.text.length;
    }
    return tokens;
}

/** A character no rule claims (`$`, `@`, `#`, a lone `:`) is an identifier of its own. */
function tokenAt(sql, index) {
    for (const [type, pattern] of RULES) {
        pattern.lastIndex = index;
        const match = pattern.exec(sql);
        if (match) {
            return {type: type === 'word' ? wordType(match[0]) : type, text: match[0]};
        }
    }
    return {type: 'identifier', text: sql[index]};
}

function wordType(word) {
    return KEYWORDS.has(word.toLowerCase()) ? 'keyword' : 'identifier';
}

export function renderSql(tokens) {
    return tokens.map(({type, text}) => PLAIN.has(type)
        ? document.createTextNode(text)
        : el('span', {className: `pk-sql__${type}`, text}));
}

export function placeholderCount(tokens) {
    return tokens.filter(token => token.type === 'placeholder').length;
}

/**
 * The n-th placeholder replaced by the tokens of the n-th literal, so a substituted value is
 * highlighted like one typed into the SQL; placeholders beyond the literals stay.
 */
export function substitute(tokens, literals) {
    let next = 0;
    return tokens.flatMap(token => token.type === 'placeholder' && next < literals.length
        ? tokenize(literals[next++])
        : [token]);
}

export function textOf(tokens) {
    return tokens.map(token => token.text).join('');
}

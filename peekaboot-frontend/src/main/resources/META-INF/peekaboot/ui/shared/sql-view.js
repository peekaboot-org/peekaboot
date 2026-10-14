/**
 * A captured statement as the trace overlay shows it, in a span's details panel and in the
 * Queries tab alike: the SQL highlighted, a Formatted toggle when the backend could format
 * it, a Substitute parameters toggle while its one parameter set fits the placeholders of the
 * SQL shown, a copy button for exactly what is shown, and the bind parameters as numbered lists,
 * with a note after the last where the backend capped the batch.
 *
 * `statement` is the backend's SqlStatement: {text, formatted, parameters}. Both toggles start
 * off and are not remembered; a toggle re-renders the code block, and adds or removes the
 * Substitute toggle when the SQL shown changes whether it fits.
 */
import {el, pressedToggle} from './dom.js';
import {copyableText} from './copyable.js';
import {placeholderCount, renderSql, substitute, textOf, tokenize} from './sql.js';

/** The final set the backend appends to a capped batch: one literal saying how many sets it dropped. */
const OMITTED_SETS = /^\/\* (\d+ parameter sets? omitted) \*\/$/;

export function sqlView(statement) {
    const {parameterSets, omitted} = splitOmitted(statement.parameters || []);
    const state = {formatted: false, substituted: false};
    const code = el('pre', {className: 'pk-code-block pk-sql__code'});
    const formatToggle = statement.formatted != null
        ? toggle('pk-sql__format', 'Formatted', pressed => {
            state.formatted = pressed;
            update();
        })
        : null;
    const substituteToggle = parameterSets.length === 1
        ? toggle('pk-sql__substitute', 'Substitute parameters', pressed => {
            state.substituted = pressed;
            update();
        })
        : null;
    const copy = copyableText(() => textOf(shown().tokens), {label: 'SQL'});
    const actions = el('div', {className: 'pk-sql__actions'}, formatToggle, copy);

    /** The tokens on screen, and whether the one parameter set fits the placeholders of the SQL shown. */
    function shown() {
        const tokens = tokenize(state.formatted ? statement.formatted : statement.text);
        const fits = substituteToggle !== null && parameterSets[0].length === placeholderCount(tokens);
        return {tokens: state.substituted && fits ? substitute(tokens, parameterSets[0]) : tokens, fits};
    }

    // Inserts or removes only the Substitute toggle, so the control just clicked keeps its place and keyboard focus.
    function update() {
        const {tokens, fits} = shown();
        const rendered = substituteToggle !== null && substituteToggle.parentNode === actions;
        if (fits && !rendered) {
            copy.before(substituteToggle);
        } else if (!fits && rendered) {
            substituteToggle.remove();
        }
        code.replaceChildren(...renderSql(tokens));
    }

    update();
    return el('div', {className: 'pk-sql'},
        actions,
        code,
        ...parameterSets.map((literals, index) => parameterList(literals, index, parameterSets.length)),
        omitted !== null ? el('p', {className: 'pk-sql__params-omitted', text: omitted}) : null);
}

function splitOmitted(sets) {
    const last = sets.at(-1);
    const match = last?.length === 1 ? OMITTED_SETS.exec(last[0]) : null;
    return match ? {parameterSets: sets.slice(0, -1), omitted: match[1]} : {parameterSets: sets, omitted: null};
}

function toggle(className, text, onChange) {
    return pressedToggle({className: `pk-btn pk-btn--small ${className}`, text}, onChange);
}

function parameterList(literals, index, setCount) {
    return el('div', {className: 'pk-sql__params'},
        el('div', {className: 'pk-sql__params-label', text: setCount > 1 ? `Parameter set ${index + 1}` : 'Parameters'}),
        el('ol', {className: 'pk-sql__param-list'}, ...literals.map(literal => el('li', {text: literal}))));
}

import {el, button} from './dom.js';
import {formatNumber} from './format.js';
import {highlightText} from './markup.js';

let groupSequence = 0;

/** A semantic pill. Variant is one of ok, warn, error, error-soft, info, muted (components.css). */
export function badge(text, variant = 'muted', {title} = {}) {
    return el('span', {className: `pk-badge pk-badge--${variant}`, text: text == null ? '' : String(text), title: title || undefined});
}

/**
 * A real link whose only content is an icon: `label` becomes both the tooltip and the
 * accessible name, since the glyph's own Unicode name would otherwise be announced. `href`
 * is an app hash (see url-state.js's buildAppHash), so the hash router does the rest.
 */
export function iconLink(href, {label, icon, className}) {
    return el('a', {
        className: 'pk-icon-link' + (className ? ` ${className}` : ''),
        text: icon,
        title: label,
        attrs: {href, 'aria-label': label}
    });
}

/** A wrapping row of muted "Label: n" badges above a tab's groups; `counts` is `[[label, n], ...]`. */
export function countSummary(counts, locale) {
    return el('div', {className: 'pk-count-summary'},
        ...counts.map(([label, n]) => badge(`${label}: ${formatNumber(n, {locale})}`, 'muted')));
}

/** The centred, muted placeholder a list shows when it has nothing to list. */
export function emptyState(message) {
    return el('p', {className: 'pk-empty', text: message});
}

/** The spinner with its caption that every loading state shows. */
export function loadingBlock(message) {
    return el('div', {className: 'pk-loading'}, el('div', {className: 'pk-spinner'}), el('p', {text: message}));
}

/**
 * A data table in its own scroll box: `columns` are the header labels, `rows` ready-made
 * <tr> elements. `className` is added to the table's own, for a surface's row accents.
 */
export function table(columns, rows, {className} = {}) {
    const head = el('thead', {}, el('tr', {}, ...columns.map(label => el('th', {text: label, attrs: {scope: 'col'}}))));
    return el('div', {className: 'pk-table-scroll'},
        el('table', {className: 'pk-table' + (className ? ` ${className}` : '')}, head, el('tbody', {}, ...rows)));
}

/** Replaces `target`'s content with a card-styled table of `rows`: the one shape every list-of-records tab shows. */
export function cardTable(target, columns, rows) {
    target.replaceChildren(table(columns, rows, {className: 'pk-table--card'}));
}

/** A table cell: `children` are text or elements; `title` keeps a truncating cell's full value reachable. */
export function cell({className, title} = {}, ...children) {
    return el('td', {className, title: title || undefined}, ...children);
}

/** A text cell whose title carries the full value, so a truncating column keeps it reachable on hover. */
export function textCell(text, className) {
    return cell({className, title: text ? String(text) : undefined}, text ?? '');
}

/** `element` holding text with every match of the query wrapped in <mark>, or plain text when there is no query. */
function withHighlightedText(element, text, highlight) {
    if (highlight) element.innerHTML = highlightText(text, highlight);
    else element.textContent = text;
    return element;
}

/**
 * A key/value row. `highlight`, when given, is a plain query string -- kvRow calls
 * highlightText() itself, so no caller can pass raw markup through key/value/highlight.
 *
 * Values arrive already masked by the backend when sensitive (a literal `******`), so
 * kvRow has no `sensitive` option of its own to decide or flag - there is nothing left
 * for the frontend to know that the rendered text doesn't already say.
 */
export function kvRow(key, value, {mono = false, tight = false, highlight} = {}) {
    return el('div', {className: 'pk-kv' + (tight ? ' pk-kv--tight' : '')},
        withHighlightedText(el('span', {className: 'pk-kv__key'}), key == null ? '' : String(key), highlight),
        withHighlightedText(el('span', {className: 'pk-kv__value' + (mono ? ' pk-kv__value--mono' : '')}),
            value == null ? '-' : String(value), highlight));
}

/**
 * A collapsible group. Returns the wrapper plus its header button and list so
 * callers can populate the list and drive state without re-querying. `highlight`,
 * when given, is a plain query string applied to the name via highlightText(); the
 * count is always rendered as plain text. `nameModifier` picks one of the name's
 * components.css variants (`strong`, `mono`).
 */
export function group({name, count, expanded = false, highlight, nameModifier} = {}) {
    const listId = `pk-group-list-${++groupSequence}`;
    const nameClass = 'pk-group__name' + (nameModifier ? ` pk-group__name--${nameModifier}` : '');

    const header = button({className: 'pk-group__header', attrs: {'aria-expanded': String(expanded), 'aria-controls': listId}},
        withHighlightedText(el('span', {className: nameClass}), name == null ? '' : String(name), highlight),
        el('span', {className: 'pk-group__count', text: count == null ? '' : String(count)}));
    const list = el('div', {className: 'pk-group__list', attrs: {id: listId, hidden: expanded ? null : ''}});

    header.addEventListener('click', () => {
        const nowExpanded = header.getAttribute('aria-expanded') !== 'true';
        header.setAttribute('aria-expanded', String(nowExpanded));
        list.toggleAttribute('hidden', !nowExpanded);
    });

    return {element: el('div', {className: 'pk-group'}, header, list), header, list};
}

/** A usage bar. percent is clamped to 0..100 and drives the fill's severity. */
export function meter(percent) {
    const value = Math.max(0, Math.min(percent || 0, 100));
    const variant = value >= 90 ? ' pk-meter__fill--danger'
                  : (value >= 70 ? ' pk-meter__fill--warning' : '');

    const fill = el('div', {className: 'pk-meter__fill' + variant});
    fill.style.width = value + '%';
    return el('div', {className: 'pk-meter'}, fill);
}

/**
 * Renders a list of collapsible groups, restoring the expansion state captured
 * by expandedKeys() so an auto-refresh does not collapse what the user opened.
 *
 *   key(group)         -> stable identity string
 *   header(group)      -> {name, count, highlight, nameModifier}
 *   items(group, list) -> populates the list element
 */
export function groupList(container, groups, {key, header, items, expandedKeys: expanded}) {
    groups.forEach(data => {
        const identity = key(data);
        const built = group({...header(data), expanded: Boolean(expanded && expanded.has(identity))});
        built.element.dataset.groupKey = identity;
        items(data, built.list);
        container.appendChild(built.element);
    });
}

/** Captures which groups are expanded. Call before clearing the container. */
export function expandedKeys(container) {
    if (!container) return new Set();
    return new Set(Array.from(container.querySelectorAll('.pk-group__header[aria-expanded="true"]'))
        .map(header => header.parentElement?.dataset.groupKey)
        .filter(Boolean));
}

/**
 * tabStrip(container, tabs, {onSelect, initial, panel}) -> {select(id, {focus, silent})}
 *
 * An ARIA tab strip with roving tabindex: only the selected tab stays in the tab
 * order (tabIndex 0; every other tab -1), and Arrow/Home/End move between tabs,
 * wrapping at the ends and skipping any tab currently hidden (offsetParent ===
 * null). `tabs` is `[{id, label, count}]`.
 *
 * A `.pk-tab[data-tab="<id>"]` button already present in `container` is reused
 * as-is - callers whose markup already carries aria-controls/id wiring (e.g. the
 * dashboard's static tab buttons) keep that wiring untouched. Only when no such
 * button exists yet does this build one from `label`/`count`, and `panel` - the one
 * element every tab switches the content of - is what the built buttons point at: it
 * becomes the tabpanel, each button controls it, and its label follows the selection.
 * `count` is already display text (grouped, in whatever locale the caller formatted
 * it for) - this module renders it verbatim, never reformats it.
 *
 * `select(id)` (also returned to the caller) always updates the DOM; it invokes
 * `onSelect` too unless called with `{silent: true}` - the escape hatch a caller
 * needs to sync the strip's visual selection (e.g. from hash-driven routing)
 * without re-triggering the side effects `onSelect` runs for real user activation.
 */
export function tabStrip(container, tabs, {onSelect, initial, panel} = {}) {
    container.setAttribute('role', 'tablist');
    if (panel) panel.setAttribute('role', 'tabpanel');

    const buttons = tabs.map(tab => {
        const existing = container.querySelector(`.pk-tab[data-tab="${tab.id}"]`);
        if (existing) return existing;
        const built = button({
            className: 'pk-tab',
            attrs: {
                'data-tab': tab.id,
                role: 'tab',
                id: panel ? `pk-tab-${tab.id}` : null,
                'aria-controls': panel ? panel.id : null
            }
        }, tab.label, ...(tab.count != null ? [' ', el('span', {className: 'pk-tab__count', text: String(tab.count)})] : []));
        container.appendChild(built);
        return built;
    });

    const visible = () => buttons.filter(tabButton => tabButton.offsetParent !== null);

    function select(id, {focus = false, silent = false} = {}) {
        buttons.forEach(tabButton => {
            const active = tabButton.dataset.tab === id;
            tabButton.setAttribute('aria-selected', String(active));
            tabButton.tabIndex = active ? 0 : -1;
            if (active && focus) tabButton.focus();
            if (active && panel && tabButton.id) panel.setAttribute('aria-labelledby', tabButton.id);
        });
        if (onSelect && !silent) onSelect(id);
    }

    container.addEventListener('click', event => {
        const clicked = event.target.closest('.pk-tab');
        if (clicked) select(clicked.dataset.tab);
    });

    container.addEventListener('keydown', event => {
        const OFFSETS = {ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1};
        const candidates = visible();
        const current = candidates.indexOf(event.target.closest('.pk-tab'));
        if (current === -1) return;

        let next = null;
        if (event.key in OFFSETS) {
            next = (current + OFFSETS[event.key] + candidates.length) % candidates.length;
        } else if (event.key === 'Home') {
            next = 0;
        } else if (event.key === 'End') {
            next = candidates.length - 1;
        }

        if (next !== null) {
            event.preventDefault();
            select(candidates[next].dataset.tab, {focus: true});
        }
    });

    select(initial || tabs[0].id, {silent: true});
    return {select};
}

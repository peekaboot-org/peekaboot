import {el, button, highlighted} from './dom.js';
import {formatNumber} from './format.js';
import {keyedChildren, renderIfChanged} from './keyed.js';

let groupSequence = 0;

/** Group element -> the {header, list} group() returned with it, for groupList's update in place. */
const groupParts = new WeakMap();

/** A cardTable target -> the <tbody> of the table it holds. */
const cardTableBodies = new WeakMap();

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
    const head = el('thead', {}, el('tr', {}, ...columns.map(label => el('th', {className: 'pk-label', text: label, attrs: {scope: 'col'}}))));
    return el('div', {className: 'pk-table-scroll'},
        el('table', {className: 'pk-table' + (className ? ` ${className}` : '')}, head, el('tbody', {}, ...rows)));
}

/**
 * Shows `items` in `target` as a card-styled table, the one shape every list-of-records tab
 * shows. The table is built once; its rows then update by key (see keyed.js), so a row whose
 * item did not change stays the node it was.
 *
 *   key(item) -> stable identity string
 *   row(item) -> the item's <tr>
 *   revision  -> as in groupList
 */
export function cardTable(target, columns, items, {key, row, revision = ''}) {
    let body = cardTableBodies.get(target);
    if (!body || !target.contains(body)) {
        const built = table(columns, [], {className: 'pk-table--card'});
        body = built.querySelector('tbody');
        cardTableBodies.set(target, body);
        target.replaceChildren(built);
    }
    keyedChildren(body, items, {key, build: row, signature: item => JSON.stringify(item) + revision});
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
    element.replaceChildren(...highlighted(text, highlight));
    return element;
}

/**
 * A key/value row. `highlight`, when given, is a plain query string -- kvRow marks the
 * matches itself, so no caller can pass raw markup through key/value/highlight.
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
 * when given, is a plain query string whose matches in the name are marked; the
 * count is always rendered as plain text. `nameModifier` picks one of the name's
 * components.css variants (`strong`, `mono`). `meta` is an array of elements that share
 * `.pk-group__meta` with the count, ahead of it, as columns that line up down a list.
 */
export function group({name, count, expanded = false, highlight, nameModifier, meta} = {}) {
    const listId = `pk-group-list-${++groupSequence}`;

    const header = button({className: 'pk-group__header', attrs: {'aria-expanded': String(expanded), 'aria-controls': listId}},
        ...groupHeaderContent({name, count, highlight, nameModifier, meta}));
    const list = el('div', {className: 'pk-group__list', attrs: {id: listId, hidden: expanded ? null : ''}});

    header.addEventListener('click', () => {
        const nowExpanded = header.getAttribute('aria-expanded') !== 'true';
        header.setAttribute('aria-expanded', String(nowExpanded));
        list.toggleAttribute('hidden', !nowExpanded);
    });

    return {element: el('div', {className: 'pk-group'}, header, list), header, list};
}

/** What a group's header button holds: see group() for the options. */
function groupHeaderContent({name, count, highlight, nameModifier, meta}) {
    const nameClass = 'pk-group__name' + (nameModifier ? ` pk-group__name--${nameModifier}` : '');
    const countEl = el('span', {className: 'pk-group__count', text: count == null ? '' : String(count)});
    return [
        withHighlightedText(el('span', {className: nameClass}), name == null ? '' : String(name), highlight),
        meta ? el('span', {className: 'pk-group__meta'}, ...meta, countEl) : countEl
    ];
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
 * Shows `groups` in `container` as collapsible groups that update by key (see keyed.js): a
 * group that stays keeps its header button, its open state and its list element, so an
 * auto-refresh neither collapses what the reader opened nor takes focus off a header. A
 * group that changed gets its header content and its list filled again.
 *
 *   key(group)         -> stable identity string
 *   header(group)      -> {name, count, highlight, nameModifier, meta}
 *   items(group, list) -> populates the empty list element
 *   revision           -> a string for what the rendering depends on besides the group:
 *                         query, locale, time zone
 */
export function groupList(container, groups, {key, header, items, revision = ''}) {
    const signature = data => JSON.stringify(data) + revision;
    const fillList = (list, data) => renderIfChanged(list, signature(data), () => {
        list.replaceChildren();
        items(data, list);
    });

    keyedChildren(container, groups, {
        key,
        signature,
        build: data => {
            const built = group(header(data));
            built.element.dataset.groupKey = key(data);
            groupParts.set(built.element, built);
            fillList(built.list, data);
            return built.element;
        },
        update: (element, data) => {
            const parts = groupParts.get(element);
            parts.header.replaceChildren(...groupHeaderContent(header(data)));
            fillList(parts.list, data);
        }
    });
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

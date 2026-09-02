import {highlightText} from './markup.js';

let groupSequence = 0;

/** A semantic pill. Variant is one of ok, warn, error, info, muted. */
export function badge(text, variant = 'muted') {
    const element = document.createElement('span');
    element.className = `pk-badge pk-badge--${variant}`;
    element.textContent = text == null ? '' : String(text);
    return element;
}

function setKvText(element, text, highlight) {
    if (highlight) element.innerHTML = highlightText(text, highlight);
    else element.textContent = text;
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
    const row = document.createElement('div');
    row.className = 'pk-kv' + (tight ? ' pk-kv--tight' : '');

    const keyEl = document.createElement('span');
    keyEl.className = 'pk-kv__key';
    setKvText(keyEl, key == null ? '' : String(key), highlight);

    const valueEl = document.createElement('span');
    valueEl.className = 'pk-kv__value' + (mono ? ' pk-kv__value--mono' : '');
    setKvText(valueEl, value == null ? '-' : String(value), highlight);

    row.append(keyEl, valueEl);
    return row;
}

/**
 * A collapsible group. Returns the wrapper plus its header button and list so
 * callers can populate the list and drive state without re-querying. `highlight`,
 * when given, is a plain query string applied to the name via highlightText(); the
 * count is always rendered as plain text.
 */
export function group({name, count, expanded = false, highlight} = {}) {
    const element = document.createElement('div');
    element.className = 'pk-group';

    const listId = `pk-group-list-${++groupSequence}`;

    const header = document.createElement('button');
    header.type = 'button';
    header.className = 'pk-group__header';
    header.setAttribute('aria-expanded', String(expanded));
    header.setAttribute('aria-controls', listId);

    const nameEl = document.createElement('span');
    nameEl.className = 'pk-group__name';
    setKvText(nameEl, name == null ? '' : String(name), highlight);

    const countEl = document.createElement('span');
    countEl.className = 'pk-group__count';
    countEl.textContent = count == null ? '' : String(count);

    header.append(nameEl, countEl);

    const list = document.createElement('div');
    list.className = 'pk-group__list';
    list.id = listId;
    list.toggleAttribute('hidden', !expanded);

    header.addEventListener('click', () => {
        const nowExpanded = header.getAttribute('aria-expanded') !== 'true';
        header.setAttribute('aria-expanded', String(nowExpanded));
        list.toggleAttribute('hidden', !nowExpanded);
    });

    element.append(header, list);
    return {element, header, list};
}

/** A usage bar. percent is clamped to 0..100 and drives the fill's severity. */
export function meter(percent) {
    const value = Math.max(0, Math.min(percent || 0, 100));
    const variant = value >= 90 ? ' pk-meter__fill--danger'
                  : (value >= 70 ? ' pk-meter__fill--warning' : '');

    const element = document.createElement('div');
    element.className = 'pk-meter';

    const fill = document.createElement('div');
    fill.className = 'pk-meter__fill' + variant;
    fill.style.width = value + '%';

    element.appendChild(fill);
    return element;
}

/**
 * Renders a list of collapsible groups, restoring the expansion state captured
 * by expandedKeys() so an auto-refresh does not collapse what the user opened.
 *
 *   key(group)         -> stable identity string
 *   header(group)      -> {name, count, highlight}
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
 * tabStrip(container, tabs, {onSelect, initial}) -> {select(id, {focus, silent})}
 *
 * An ARIA tab strip with roving tabindex: only the selected tab stays in the tab
 * order (tabIndex 0; every other tab -1), and Arrow/Home/End move between tabs,
 * wrapping at the ends and skipping any tab currently hidden (offsetParent ===
 * null). `tabs` is `[{id, label, count}]`.
 *
 * A `.pk-tab[data-tab="<id>"]` button already present in `container` is reused
 * as-is - callers whose markup already carries aria-controls/id wiring (e.g. the
 * dashboard's static tab buttons) keep that wiring untouched. Only when no such
 * button exists yet does this build one from `label`/`count`.
 *
 * `select(id)` (also returned to the caller) always updates the DOM; it invokes
 * `onSelect` too unless called with `{silent: true}` - the escape hatch a caller
 * needs to sync the strip's visual selection (e.g. from hash-driven routing)
 * without re-triggering the side effects `onSelect` runs for real user activation.
 */
export function tabStrip(container, tabs, {onSelect, initial} = {}) {
    container.setAttribute('role', 'tablist');

    const buttons = tabs.map(tab => {
        let button = container.querySelector(`.pk-tab[data-tab="${tab.id}"]`);
        if (!button) {
            button = document.createElement('button');
            button.type = 'button';
            button.className = 'pk-tab';
            button.dataset.tab = tab.id;
            button.setAttribute('role', 'tab');
            button.append(tab.label);
            if (tab.count != null) {
                const count = document.createElement('span');
                count.className = 'pk-tab__count';
                count.textContent = String(tab.count);
                button.append(' ', count);
            }
            container.appendChild(button);
        }
        return button;
    });

    const visible = () => buttons.filter(button => button.offsetParent !== null);

    function select(id, {focus = false, silent = false} = {}) {
        buttons.forEach(button => {
            const active = button.dataset.tab === id;
            button.setAttribute('aria-selected', String(active));
            button.tabIndex = active ? 0 : -1;
            if (active && focus) button.focus();
        });
        if (onSelect && !silent) onSelect(id);
    }

    container.addEventListener('click', event => {
        const button = event.target.closest('.pk-tab');
        if (button) select(button.dataset.tab);
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

/**
 * The shell shared by the dashboard tabs that show a filterable list of collapsible groups
 * (config.js, environment.js, loggers.js): the module-level data/context, the filter input
 * wired once, the URL <-> filter reconciliation, the expansion state that survives a
 * re-render, and the empty states. A tab supplies only what differs - where its groups come
 * from, how one group is filtered, and how a group's header and items look.
 *
 *   select(data)                -> the groups array, or nothing when the payload has none
 *   filterGroup(group, query)   -> the group narrowed to the query, or null when nothing
 *                                  in it matches; called with '' when no filter is set
 *   key/header/items            -> groupList()'s callbacks; header also receives the query
 *   extraTop(data)              -> optional element rendered above the groups, or null
 *   emptyMessage                -> shown when the payload has no groups at all
 *   noMatchMessage(query)       -> shown when the filter narrows everything away
 *   urlFilter                   -> optional {reconcile(input, container, context),
 *                                  write(input, container, context)} for a tab whose URL
 *                                  state is more than the single "q" the default handles
 *   decorate(listEl)            -> optional post-processing of the rendered groups
 *
 * The returned tab exposes render(container, data, context) - the tab-module contract - and
 * refresh(container), which re-renders with the current filter for a control the tab wires
 * itself (loggers.js's checkbox).
 */
import {groupList, expandedKeys} from './components.js';
import {escapeHtml} from './markup.js';
import {reconcileTextFilter, writeTextFilter} from './url-filter.js';

export function filteredGroupTab({
    inputId, listId, select, filterGroup, key, header, items, extraTop,
    emptyMessage, noMatchMessage, urlFilter, decorate
}) {
    let currentData = null;
    // The most recent render() call's context - read by the persistent filter listener
    // (wired once) so a later render's context (its setUrlParams closes over the URL's
    // tab/detail/subview at *that* call - see main.js's currentContext()) is always what a
    // later keystroke writes through, not whatever was current the first time this tab
    // was rendered.
    let currentContext = null;

    const reconcile = urlFilter?.reconcile ?? ((input, container, context) => reconcileTextFilter(input, context));
    const write = urlFilter?.write ?? ((input, container, context) => writeTextFilter(input, context));

    function render(container, data, context) {
        currentData = data;
        currentContext = context;
        wireFilter(container);
        // Only while this tab is the one the hash currently points at - context.urlParams
        // reflects whatever tab is active in the URL, so reconciling during a background
        // auto-refresh render of a hidden tab would read another tab's params (or none)
        // and clobber whatever the user already typed here.
        if (container.classList.contains('active')) reconcile(input(container), container, context);
        renderGroups(container);
    }

    function refresh(container) {
        renderGroups(container);
    }

    function input(container) {
        return container.querySelector(`#${inputId}`);
    }

    function currentQuery(container) {
        return input(container)?.value.trim() || '';
    }

    function wireFilter(container) {
        const filterInput = input(container);
        if (!filterInput || filterInput.dataset.wired) return;
        filterInput.dataset.wired = 'true';
        filterInput.addEventListener('input', () => {
            write(filterInput, container, currentContext);
            renderGroups(container);
        });
    }

    function renderGroups(container) {
        const query = currentQuery(container);
        const target = container.querySelector(`#${listId}`);
        // Must run before the container is cleared below - it reads the DOM's current
        // aria-expanded state so a re-render (e.g. the 30s auto-refresh) can restore it.
        const expanded = expandedKeys(target);
        target.innerHTML = '';

        const groups = select(currentData);
        if (!groups || groups.length === 0) {
            target.innerHTML = `<p class="pk-empty">${escapeHtml(emptyMessage)}</p>`;
            return;
        }

        const top = extraTop?.(currentData);
        if (top) target.appendChild(top);

        const filtered = groups.map(group => filterGroup(group, query)).filter(Boolean);
        if (filtered.length === 0) {
            const message = query ? noMatchMessage(query) : emptyMessage;
            target.insertAdjacentHTML('beforeend', `<p class="pk-empty">${escapeHtml(message)}</p>`);
            return;
        }

        groupList(target, filtered, {
            key,
            header: group => header(group, query),
            items: (group, list) => items(group, list, query),
            expandedKeys: expanded
        });
        if (decorate) decorate(target);
    }

    return {render, refresh};
}

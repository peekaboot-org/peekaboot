/**
 * The shell shared by the dashboard tabs that show a list of collapsible groups, filterable
 * or not (config.js, environment.js, loggers.js, meters.js, scheduled-tasks.js): the tab
 * instance with its data and context, the filter input wired once, the URL <-> filter
 * reconciliation, the groups updated by key on a re-render, and the empty states. A
 * tab supplies only what differs - where its groups come from, how one group is filtered,
 * and how a group's header and items look. A tab with no `inputId` has no filter: every
 * group renders, and its `filterGroup` is called with an empty query.
 *
 *   select(data)                -> the groups array, or nothing when the payload has none
 *   filterGroup(group, query)   -> the group narrowed to the query, or null when nothing
 *                                  in it matches; called with '' when no filter is set
 *   key/header/items            -> groupList()'s callbacks: header(group, query, context)
 *                                  and items(group, list, query, context) each also take
 *                                  the current query and context
 *   extraTop(data, context)     -> optional element rendered above the groups, or null
 *   emptyMessage                -> shown when the payload has no groups at all
 *   noMatchMessage(query)       -> shown when the filter narrows everything away
 *   filterState(input)          -> optional: the filter's URL state {reconcile, save} (see
 *                                  url-filter.js) for a tab that keeps more in the URL
 *                                  than the single "q" of the default, textFilterState
 *   afterRender(container, {groups, filtered, query, context})
 *                               -> optional hook run after every (re-)render, the empty
 *                                  states included - meters.js's match-count readout
 *   fetchData(context)          -> optional: the tab's data comes from its own endpoint
 *                                  instead of the shared payload show() receives, on the
 *                                  self-fetching-tab.js contract (stopped while hidden,
 *                                  supersession, rejection). Resolves to the data select()
 *                                  reads; a rejection renders fetchErrorMessage.
 *   loadingMessage              -> shown while the very first fetchData() is in flight
 *   fetchErrorMessage(error)    -> shown when fetchData() rejects
 *
 * The call is one tab instance over `container`, the tab's panel. It returns show, update
 * and hide - the tab contract - and refresh(), which writes the filter to the URL and
 * re-renders with it, for a control the tab wires itself (loggers.js's checkbox).
 */
import {groupList, emptyState, loadingBlock, kvRow} from './components.js';
import {formatCount, formatPlainValue} from './format.js';
import {textFilterState} from './url-filter.js';
import {selfFetchingTab} from './self-fetching-tab.js';
import {renderUnmaskControl} from './unmask-control.js';

export function filteredGroupTab(container, {
    inputId, listId, select, filterGroup, key, header, items, extraTop,
    emptyMessage, noMatchMessage, filterState = textFilterState, afterRender,
    fetchData, loadingMessage = 'Loading...', fetchErrorMessage = error => `Failed to load: ${error.message}`
}) {
    const target = container.querySelector(`#${listId}`);
    const filterInput = inputId ? container.querySelector(`#${inputId}`) : null;
    // nothing for a tab without a filter input
    const filterUrlState = filterInput ? filterState(filterInput) : null;
    let currentData = null;
    // The most recent show() or update() call's context - read by the filter listener, so
    // a later keystroke formats its groups with the latest locale and timeZone instead of
    // whatever was current the first time this tab was shown.
    let currentContext = null;

    const fetcher = fetchData && selfFetchingTab({
        fetch: fetchData,
        // Only before the very first data arrives - a refresh of an already-populated list
        // must not blank it for the round trip's duration (renderGroups replaces the
        // content once the response is in).
        loading: (panel, {firstLoad}) => {
            if (firstLoad) target.replaceChildren(loadingBlock(loadingMessage));
        },
        renderResult: (panel, result) => {
            currentData = result;
            renderGroups();
        },
        renderError: (panel, error) => target.replaceChildren(emptyState(fetchErrorMessage(error)))
    });

    filterInput?.addEventListener('input', refresh);

    function render(data, context) {
        currentContext = context;
        filterUrlState?.reconcile(context);
        if (fetcher) return fetcher.load(container, context);
        currentData = data;
        renderGroups();
    }

    function refresh() {
        filterUrlState?.save(currentContext);
        renderGroups();
    }

    function renderGroups() {
        const query = filterInput?.value.trim() || '';

        const groups = select(currentData);
        if (!groups || groups.length === 0) {
            target.replaceChildren(emptyState(emptyMessage));
            afterRender?.(container, {groups: [], filtered: [], query, context: currentContext});
            return;
        }

        const top = extraTop?.(currentData, currentContext);

        const filtered = groups.map(group => filterGroup(group, query)).filter(Boolean);
        if (filtered.length === 0) {
            const message = query ? noMatchMessage(query) : emptyMessage;
            target.replaceChildren(...[top, emptyState(message)].filter(Boolean));
            afterRender?.(container, {groups, filtered, query, context: currentContext});
            return;
        }

        groupList(target, filtered, {
            key,
            header: group => header(group, query, currentContext),
            items: (group, list) => items(group, list, query, currentContext),
            revision: [query, currentContext.locale, currentContext.timeZone].join('|')
        });
        // after groupList, which removes what it did not build: the last render's top included
        if (top) target.prepend(top);
        afterRender?.(container, {groups, filtered, query, context: currentContext});
    }

    return {show: render, update: render, hide: () => fetcher?.stop(), refresh};
}

/**
 * The property-list case of filteredGroupTab, shared by config.js and environment.js:
 * the backend's PropertyGroup {name, properties} of {key, value} properties, matched on
 * either, values rendered as one line of text (a structured value as JSON), and the "Show
 * secrets" control (unmask-control.js) in the slot `unmaskSlotId` names. A group's name -
 * config's prefix, a property source's name - doubles as the key it is updated by.
 */
export function propertyGroupTab(container, {inputId, listId, unmaskSlotId, select, extraTop, emptyMessage}) {
    const tab = filteredGroupTab(container, {
        inputId,
        listId,
        select,
        filterGroup: (group, query) => {
            const properties = (group.properties || []).filter(prop => propertyMatches(prop, query));
            return properties.length > 0 ? {...group, properties} : null;
        },
        key: group => group.name,
        header: (group, query, context) => ({
            name: group.name,
            count: formatCount(group.properties.length, 'property', {plural: 'properties', locale: context.locale}),
            highlight: query
        }),
        items: (group, list, query) => group.properties.forEach(prop =>
            list.appendChild(kvRow(prop.key, formatPlainValue(prop.value), {highlight: query}))),
        extraTop,
        emptyMessage,
        noMatchMessage: query => `No properties matching "${query}"`
    });
    const unmaskSlot = container.querySelector(`#${unmaskSlotId}`);

    function render(data, context) {
        renderUnmaskControl(unmaskSlot, context);
        return tab.show(data, context);
    }

    return {show: render, update: render, hide: tab.hide};
}

/**
 * Key or rendered value contains the query, case-insensitively. A missing value is matched
 * as the empty string, so such a row matches on its key alone; the dash the row renders in
 * its place is no part of the filter text. Exported for the browser tests.
 */
export function propertyMatches(prop, query) {
    if (!query) return true;
    const needle = query.toLowerCase();
    const value = prop.value == null ? '' : formatPlainValue(prop.value);
    return prop.key.toLowerCase().includes(needle) || value.toLowerCase().includes(needle);
}

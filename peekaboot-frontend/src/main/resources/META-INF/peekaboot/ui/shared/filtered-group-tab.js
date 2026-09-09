/**
 * The shell shared by the dashboard tabs that show a list of collapsible groups, filterable
 * or not (config.js, environment.js, loggers.js, meters.js, scheduled-tasks.js): the
 * module-level data/context, the filter input wired once, the URL <-> filter
 * reconciliation, the expansion state that survives a re-render, and the empty states. A
 * tab supplies only what differs - where its groups come from, how one group is filtered,
 * and how a group's header and items look. A tab with no `inputId` has no filter: every
 * group renders, and its `filterGroup` is called with an empty query.
 *
 *   select(data)                -> the groups array, or nothing when the payload has none
 *   filterGroup(group, query)   -> the group narrowed to the query, or null when nothing
 *                                  in it matches; called with '' when no filter is set
 *   key/header/items            -> groupList()'s callbacks; header also receives the
 *                                  query, items the query and the current context
 *   extraTop(data)              -> optional element rendered above the groups, or null
 *   emptyMessage                -> shown when the payload has no groups at all
 *   noMatchMessage(query)       -> shown when the filter narrows everything away
 *   urlFilter                   -> optional {reconcile(input, container, context),
 *                                  write(input, container, context)} for a tab whose URL
 *                                  state is more than the single "q" the default handles
 *   decorate(listEl, filtered)  -> optional post-processing of the rendered groups
 *   afterRender(container, {groups, filtered, query})
 *                               -> optional hook run after every (re-)render, the empty
 *                                  states included - meters.js's match-count readout
 *   fetchData(context)          -> optional: the tab's data comes from its own endpoint
 *                                  instead of the shared payload render() receives, on the
 *                                  self-fetching-tab.js contract (active-tab guard,
 *                                  supersession, rejection). Resolves to the data select()
 *                                  reads; a rejection renders fetchErrorMessage.
 *   loadingMessage              -> shown while the very first fetchData() is in flight
 *   fetchErrorMessage(error)    -> shown when fetchData() rejects
 *
 * The returned tab exposes render(container, data, context) - the tab-module contract - and
 * refresh(container), which re-renders with the current filter for a control the tab wires
 * itself (loggers.js's checkbox).
 */
import {groupList, expandedKeys, emptyState, loadingBlock, kvRow} from './components.js';
import {formatCount, formatPlainValue} from './format.js';
import {reconcileTextFilter, writeTextFilter} from './url-filter.js';
import {selfFetchingTab} from './self-fetching-tab.js';
import {renderUnmaskControl} from './unmask-control.js';

export function filteredGroupTab({
    inputId, listId, select, filterGroup, key, header, items, extraTop,
    emptyMessage, noMatchMessage, urlFilter, decorate, afterRender,
    fetchData, loadingMessage = 'Loading...', fetchErrorMessage = error => `Failed to load: ${error.message}`
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

    const fetcher = fetchData && selfFetchingTab({
        fetch: fetchData,
        reconcile: (container, context) => reconcile(input(container), container, context),
        // Only before the very first data arrives - a background refresh of an
        // already-populated, currently visible list must not blank it for the round
        // trip's duration (renderGroups replaces the content once the response is in).
        loading: (container, {firstLoad}) => {
            if (firstLoad) list(container).replaceChildren(loadingBlock(loadingMessage));
        },
        renderResult: (container, result) => {
            currentData = result;
            renderGroups(container);
        },
        renderError: (container, error) => list(container).replaceChildren(emptyState(fetchErrorMessage(error)))
    });

    function render(container, data, context) {
        currentContext = context;
        wireFilter(container);
        if (fetcher) {
            fetcher.render(container, data, context);
            return;
        }
        currentData = data;
        if (context.active) reconcile(input(container), container, context);
        renderGroups(container);
    }

    function refresh(container) {
        renderGroups(container);
    }

    function list(container) {
        return container.querySelector(`#${listId}`);
    }

    function input(container) {
        return inputId ? container.querySelector(`#${inputId}`) : null;
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
        const target = list(container);
        // Must run before the container is cleared below - it reads the DOM's current
        // aria-expanded state so a re-render (e.g. the 30s auto-refresh) can restore it.
        const expanded = expandedKeys(target);
        target.innerHTML = '';

        const groups = select(currentData);
        if (!groups || groups.length === 0) {
            target.appendChild(emptyState(emptyMessage));
            afterRender?.(container, {groups: [], filtered: [], query});
            return;
        }

        const top = extraTop?.(currentData);
        if (top) target.appendChild(top);

        const filtered = groups.map(group => filterGroup(group, query)).filter(Boolean);
        if (filtered.length === 0) {
            const message = query ? noMatchMessage(query) : emptyMessage;
            target.appendChild(emptyState(message));
            afterRender?.(container, {groups, filtered, query});
            return;
        }

        groupList(target, filtered, {
            key,
            header: group => header(group, query),
            items: (group, list) => items(group, list, query, currentContext),
            expandedKeys: expanded
        });
        if (decorate) decorate(target, filtered);
        afterRender?.(container, {groups, filtered, query});
    }

    return {render, refresh};
}

/**
 * The property-list case of filteredGroupTab, shared by config.js and environment.js:
 * groups of {key, value} properties matched on either, values rendered as one line of
 * text (a structured value as JSON), and the "Show secrets" control (unmask-control.js)
 * in the slot `unmaskSlotId` names. `groupName(group)` names a group - config's prefix, a
 * property source's name - and doubles as its identity for expansion restore.
 */
export function propertyGroupTab({inputId, listId, unmaskSlotId, select, groupName, extraTop, emptyMessage}) {
    const tab = filteredGroupTab({
        inputId,
        listId,
        select,
        filterGroup: (group, query) => {
            const properties = (group.properties || []).filter(prop => propertyMatches(prop, query));
            return properties.length > 0 ? {...group, properties} : null;
        },
        key: groupName,
        header: (group, query) => ({
            name: groupName(group),
            count: formatCount(group.properties.length, 'property', 'properties'),
            highlight: query
        }),
        items: (group, list, query) => group.properties.forEach(prop =>
            list.appendChild(kvRow(prop.key, formatPlainValue(prop.value), {highlight: query}))),
        extraTop,
        emptyMessage,
        noMatchMessage: query => `No properties matching "${query}"`
    });

    function render(container, data, context) {
        renderUnmaskControl(container.querySelector(`#${unmaskSlotId}`), context);
        tab.render(container, data, context);
    }

    return {render};
}

/** Key or rendered value contains the query, case-insensitively; a missing value matches nothing. */
function propertyMatches(prop, query) {
    if (!query) return true;
    const needle = query.toLowerCase();
    const value = prop.value == null ? '' : formatPlainValue(prop.value);
    return prop.key.toLowerCase().includes(needle) || value.toLowerCase().includes(needle);
}

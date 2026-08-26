/**
 * The "Config" tab: @ConfigurationProperties groups, filterable. Sensitive values
 * arrive already masked from the backend (MaskingEngine) - this tab just renders
 * what the API gives it, with no sensitivity decision of its own.
 */
import {groupList, expandedKeys, kvRow} from '../../shared/components.js';
import {escapeHtml} from '../../shared/markup.js';
import {renderUnmaskControl} from '../../shared/unmask-control.js';

export const id = 'config';
export const label = 'Config';

let currentData = null;

// The most recent render() call's context - read by the persistent filter input
// listener below (wired once, see wireFilter) so a later render's context (its
// setUrlParams closes over the URL's tab/detail/subview at *that* call - see main.js's
// currentContext()) is always what a later keystroke writes through, not whatever was
// current the first time this tab was rendered.
let currentContext = null;

export function isAvailable(data) {
    return Boolean(data?.config?.groups?.length);
}

export function render(container, data, context) {
    currentData = data;
    currentContext = context;
    wireFilter(container);
    // Only while this tab is the one the hash currently points at - context.urlParams
    // reflects whatever tab is active in the URL, so seeding during a background
    // auto-refresh render of a hidden config tab would read another tab's params (or
    // none) and clobber whatever the user already typed here.
    if (container.classList.contains('active')) seedFilterFromUrl(container);
    renderUnmaskControl(container.querySelector('#config-unmask-slot'), context);
    renderGroups(container, currentFilterValue(container));
}

function wireFilter(container) {
    const input = container.querySelector('#config-filter');
    if (!input || input.dataset.wired) return;
    input.dataset.wired = 'true';
    input.addEventListener('input', () => {
        const value = input.value.trim();
        currentContext.setUrlParams(value ? {q: value} : {});
        renderGroups(container, value);
    });
}

/** Seeds the filter input from the URL - a no-op once the input already matches it,
    which is the common case: every keystroke above writes straight back via
    setUrlParams, so an ordinary auto-refresh render finds nothing to seed. Only a
    genuine hash-driven navigation (deep link, Back/Forward) actually changes the
    input's value here. */
function seedFilterFromUrl(container) {
    const input = container.querySelector('#config-filter');
    if (!input) return;
    const urlQuery = currentContext.urlParams.q || '';
    if (urlQuery !== input.value.trim()) input.value = urlQuery;
}

function currentFilterValue(container) {
    return container.querySelector('#config-filter')?.value.trim() || '';
}

function renderGroups(container, filterQuery) {
    const configInfo = currentData?.config;
    const target = container.querySelector('#config-groups');
    // Must run before the container is cleared below - see environment.js.
    const expanded = expandedKeys(target);
    target.innerHTML = '';

    const groups = configInfo?.groups;
    if (!groups || groups.length === 0) {
        target.innerHTML = '<p class="pk-empty">No configuration properties available</p>';
        return;
    }

    // Pre-filter groups and their properties
    const filteredGroups = groups
        .map(group => ({
            prefix: group.prefix,
            properties: group.properties.filter(prop => {
                if (!filterQuery) return true;
                const matchesKey = prop.key.toLowerCase().includes(filterQuery.toLowerCase());
                const matchesValue = prop.value && prop.value.toLowerCase().includes(filterQuery.toLowerCase());
                return matchesKey || matchesValue;
            })
        }))
        .filter(group => group.properties.length > 0);

    if (filteredGroups.length === 0) {
        target.innerHTML = filterQuery
            ? `<p class="pk-empty">No properties matching "${escapeHtml(filterQuery)}"</p>`
            : '<p class="pk-empty">No configuration properties available</p>';
        return;
    }

    groupList(target, filteredGroups, {
        key: group => group.prefix,
        header: group => ({
            name: group.prefix,
            count: `${group.properties.length} properties`,
            highlight: filterQuery
        }),
        items: (group, list) => group.properties.forEach(prop => list.appendChild(
            kvRow(prop.key, prop.value, {highlight: filterQuery}))),
        expandedKeys: expanded
    });
}

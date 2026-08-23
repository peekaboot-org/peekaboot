/**
 * The "Config" tab: @ConfigurationProperties groups, filterable. Sensitive values
 * arrive already masked from the backend (MaskingEngine) - this tab just renders
 * what the API gives it, with no sensitivity decision of its own.
 */
import {groupList, expandedKeys, kvRow} from '../../shared/components.js';
import {escapeHtml} from '../../shared/markup.js';

export const id = 'config';
export const label = 'Config';

let currentData = null;

export function isAvailable(data) {
    return Boolean(data?.config?.groups?.length);
}

export function render(container, data) {
    currentData = data;
    wireFilter(container);
    renderGroups(container, currentFilterValue(container));
}

function wireFilter(container) {
    const input = container.querySelector('#config-filter');
    if (!input || input.dataset.wired) return;
    input.dataset.wired = 'true';
    input.addEventListener('input', () => renderGroups(container, input.value.trim()));
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

/**
 * The "Config" tab: @ConfigurationProperties groups, filterable. Sensitive values
 * arrive already masked from the backend (MaskingEngine) - this tab just renders
 * what the API gives it, with no sensitivity decision of its own.
 */
import {kvRow} from '../../shared/components.js';
import {filteredGroupTab} from '../../shared/filtered-group-tab.js';
import {renderUnmaskControl} from '../../shared/unmask-control.js';

export const id = 'config';
export const label = 'Config';

const tab = filteredGroupTab({
    inputId: 'config-filter',
    listId: 'config-groups',
    select: data => data?.config?.groups,
    filterGroup: (group, query) => {
        const properties = group.properties.filter(prop => matches(prop, query));
        return properties.length > 0 ? {prefix: group.prefix, properties} : null;
    },
    key: group => group.prefix,
    header: (group, query) => ({
        name: group.prefix,
        count: `${group.properties.length} properties`,
        highlight: query
    }),
    items: (group, list, query) => group.properties.forEach(prop =>
        list.appendChild(kvRow(prop.key, prop.value, {highlight: query}))),
    emptyMessage: 'No configuration properties available',
    noMatchMessage: query => `No properties matching "${query}"`
});

export function isAvailable(data) {
    return Boolean(data?.config?.groups?.length);
}

export function render(container, data, context) {
    renderUnmaskControl(container.querySelector('#config-unmask-slot'), context);
    tab.render(container, data, context);
}

function matches(prop, query) {
    if (!query) return true;
    const needle = query.toLowerCase();
    return prop.key.toLowerCase().includes(needle)
        || Boolean(prop.value && prop.value.toLowerCase().includes(needle));
}

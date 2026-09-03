/**
 * The "Environment" tab: property sources grouped and filterable, each expandable to
 * its key/value pairs, with the active Spring profiles shown as a banner above them.
 */
import {kvRow, badge} from '../../shared/components.js';
import {formatCount} from '../../shared/format.js';
import {filteredGroupTab} from '../../shared/filtered-group-tab.js';
import {renderUnmaskControl} from '../../shared/unmask-control.js';

export const id = 'environment';
export const label = 'Environment';

const tab = filteredGroupTab({
    inputId: 'env-filter',
    listId: 'property-sources',
    select: data => data?.environment?.propertySources,
    filterGroup: (source, query) => {
        const properties = (source.properties || []).filter(prop => matches(prop, query));
        return properties.length > 0 ? {name: source.name || 'Unknown Source', properties} : null;
    },
    key: source => source.name,
    header: (source, query) => ({
        name: source.name,
        count: formatCount(source.properties.length, 'property', 'properties'),
        highlight: query
    }),
    items: (source, list, query) => source.properties.forEach(prop =>
        list.appendChild(kvRow(prop.key, formatValue(prop.value), {highlight: query}))),
    extraTop: data => renderActiveProfiles(data.environment.activeProfiles),
    emptyMessage: 'No environment properties available',
    noMatchMessage: query => `No properties matching "${query}"`
});

export function render(container, data, context) {
    renderUnmaskControl(container.querySelector('#env-unmask-slot'), context);
    tab.render(container, data, context);
}

function renderActiveProfiles(activeProfiles) {
    if (!activeProfiles || activeProfiles.length === 0) return null;
    const profilesEl = document.createElement('div');
    profilesEl.className = 'pk-profiles';

    const label = document.createElement('strong');
    label.textContent = 'Active Profiles:';
    profilesEl.appendChild(label);

    activeProfiles.forEach(profile => profilesEl.appendChild(badge(profile, 'info')));
    return profilesEl;
}

function matches(prop, query) {
    if (!query) return true;
    const needle = query.toLowerCase();
    return prop.key.toLowerCase().includes(needle) || String(prop.value).toLowerCase().includes(needle);
}

function formatValue(value) {
    if (value === null || value === undefined) return '-';
    if (typeof value === 'object') return JSON.stringify(value);
    return String(value);
}

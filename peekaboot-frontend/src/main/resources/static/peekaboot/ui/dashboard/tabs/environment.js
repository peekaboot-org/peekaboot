/**
 * The "Environment" tab: property sources grouped and filterable, each expandable to
 * its key/value pairs, with the active Spring profiles shown as a banner above them.
 */
import {groupList, expandedKeys, kvRow, badge} from '../../shared/components.js';
import {escapeHtml} from '../../shared/markup.js';

export const id = 'environment';
export const label = 'Environment';

let currentData = null;

export function render(container, data) {
    currentData = data;
    wireFilter(container);
    renderGroups(container, currentFilterValue(container));
}

function wireFilter(container) {
    const input = container.querySelector('#env-filter');
    if (!input || input.dataset.wired) return;
    input.dataset.wired = 'true';
    input.addEventListener('input', () => renderGroups(container, input.value.trim()));
}

function currentFilterValue(container) {
    return container.querySelector('#env-filter')?.value.trim() || '';
}

function renderGroups(container, filterQuery) {
    const env = currentData?.environment;
    const target = container.querySelector('#property-sources');
    // Must run before the container is cleared below - it reads the DOM's current
    // aria-expanded state so a re-render (e.g. the 30s auto-refresh) can restore it.
    const expanded = expandedKeys(target);
    target.innerHTML = '';

    if (!env?.propertySources || env.propertySources.length === 0) {
        target.innerHTML = '<p class="pk-empty">No environment properties available</p>';
        return;
    }

    if (env.activeProfiles && env.activeProfiles.length > 0) {
        target.appendChild(renderActiveProfiles(env.activeProfiles));
    }

    // Pre-filter sources and their properties
    const filteredSources = env.propertySources
        .map(source => ({
            name: source.name || 'Unknown Source',
            properties: (source.properties || []).filter(prop => matchesFilter(prop.key, prop.value, filterQuery))
        }))
        .filter(source => source.properties.length > 0);

    if (filteredSources.length === 0 && filterQuery) {
        target.innerHTML = `<p class="pk-empty">No properties matching "${escapeHtml(filterQuery)}"</p>`;
        return;
    }

    // While filtering, every surviving group has a match by construction - open them
    // all so the highlighted hits are visible without an extra click. With no filter,
    // fall back to whatever the user had expanded before this re-render.
    const effectiveExpanded = filterQuery
        ? new Set(filteredSources.map(source => source.name))
        : expanded;

    groupList(target, filteredSources, {
        key: source => source.name,
        header: source => ({
            name: source.name,
            count: `${source.properties.length} properties`,
            highlight: filterQuery
        }),
        items: (source, list) => source.properties.forEach(prop =>
            list.appendChild(kvRow(prop.key, formatValue(prop.value), {highlight: filterQuery}))),
        expandedKeys: effectiveExpanded
    });
}

function renderActiveProfiles(activeProfiles) {
    const profilesEl = document.createElement('div');
    profilesEl.className = 'pk-profiles';

    const label = document.createElement('strong');
    label.textContent = 'Active Profiles:';
    profilesEl.appendChild(label);

    activeProfiles.forEach(profile => profilesEl.appendChild(badge(profile, 'info')));
    return profilesEl;
}

function matchesFilter(key, value, filter) {
    if (!filter) return true;
    const filterLower = filter.toLowerCase();
    return key.toLowerCase().includes(filterLower) ||
        String(value).toLowerCase().includes(filterLower);
}

function formatValue(value) {
    if (value === null || value === undefined) return '-';
    if (typeof value === 'object') return JSON.stringify(value);
    return String(value);
}

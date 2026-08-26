/**
 * The "Environment" tab: property sources grouped and filterable, each expandable to
 * its key/value pairs, with the active Spring profiles shown as a banner above them.
 */
import {groupList, expandedKeys, kvRow, badge} from '../../shared/components.js';
import {escapeHtml} from '../../shared/markup.js';
import {renderUnmaskControl} from '../../shared/unmask-control.js';

export const id = 'environment';
export const label = 'Environment';

let currentData = null;

// The most recent render() call's context - read by the persistent filter input
// listener below (wired once, see wireFilter) so a later render's context (its
// setUrlParams closes over the URL's tab/detail/subview at *that* call - see main.js's
// currentContext()) is always what a later keystroke writes through, not whatever was
// current the first time this tab was rendered.
let currentContext = null;

export function render(container, data, context) {
    currentData = data;
    currentContext = context;
    wireFilter(container);
    // Only while this tab is the one the hash currently points at - context.urlParams
    // reflects whatever tab is active in the URL, so seeding during a background
    // auto-refresh render of a hidden environment tab would read another tab's params
    // (or none) and clobber whatever the user already typed here.
    if (container.classList.contains('active')) seedFilterFromUrl(container);
    renderUnmaskControl(container.querySelector('#env-unmask-slot'), context);
    renderGroups(container, currentFilterValue(container));
}

function wireFilter(container) {
    const input = container.querySelector('#env-filter');
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
    const input = container.querySelector('#env-filter');
    if (!input) return;
    const urlQuery = currentContext.urlParams.q || '';
    if (urlQuery !== input.value.trim()) input.value = urlQuery;
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

    groupList(target, filteredSources, {
        key: source => source.name,
        header: source => ({
            name: source.name,
            count: `${source.properties.length} properties`,
            highlight: filterQuery
        }),
        items: (source, list) => source.properties.forEach(prop =>
            list.appendChild(kvRow(prop.key, formatValue(prop.value), {highlight: filterQuery}))),
        expandedKeys: expanded
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

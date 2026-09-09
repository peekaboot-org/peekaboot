/**
 * The "Environment" tab: property sources grouped and filterable, each expandable to
 * its key/value pairs, with the active Spring profiles shown as a banner above them.
 */
import {badge} from '../../shared/components.js';
import {propertyGroupTab} from '../../shared/filtered-group-tab.js';

export const id = 'environment';
export const label = 'Environment';

const tab = propertyGroupTab({
    inputId: 'env-filter',
    listId: 'property-sources',
    unmaskSlotId: 'env-unmask-slot',
    select: data => data?.environment?.propertySources,
    groupName: source => source.name || 'Unknown Source',
    extraTop: data => renderActiveProfiles(data.environment.activeProfiles),
    emptyMessage: 'No environment properties available'
});

export function render(container, data, context) {
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

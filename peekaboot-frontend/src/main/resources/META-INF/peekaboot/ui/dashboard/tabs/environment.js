/**
 * The "Environment" tab: property sources grouped and filterable, each expandable to
 * its key/value pairs, with the active Spring profiles shown as a banner above them.
 */
import {badge} from '../../shared/components.js';
import {el} from '../../shared/dom.js';
import {propertyGroupTab} from '../../shared/filtered-group-tab.js';

export const id = 'environment';

const tab = propertyGroupTab({
    inputId: 'env-filter',
    listId: 'property-sources',
    unmaskSlotId: 'env-unmask-slot',
    select: data => data?.environment?.propertySources,
    groupName: source => source.name || 'Unknown Source',
    extraTop: data => renderActiveProfiles(data.environment.activeProfiles),
    emptyMessage: 'No environment properties available'
});

export const {render} = tab;

function renderActiveProfiles(activeProfiles) {
    if (!activeProfiles || activeProfiles.length === 0) return null;
    return el('div', {className: 'pk-profiles'},
        el('strong', {text: 'Active Profiles:'}),
        ...activeProfiles.map(profile => badge(profile, 'info')));
}

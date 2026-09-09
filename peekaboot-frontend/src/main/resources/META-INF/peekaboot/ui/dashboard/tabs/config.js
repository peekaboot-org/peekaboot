/**
 * The "Config" tab: @ConfigurationProperties groups, filterable. Sensitive values
 * arrive already masked from the backend (MaskingEngine) - this tab just renders
 * what the API gives it, with no sensitivity decision of its own.
 */
import {propertyGroupTab} from '../../shared/filtered-group-tab.js';

export const id = 'config';
export const label = 'Config';

const tab = propertyGroupTab({
    inputId: 'config-filter',
    listId: 'config-groups',
    unmaskSlotId: 'config-unmask-slot',
    select: data => data?.config?.groups,
    groupName: group => group.prefix,
    emptyMessage: 'No configuration properties available'
});

export function isAvailable(data) {
    return Boolean(data?.config?.groups?.length);
}

export function render(container, data, context) {
    tab.render(container, data, context);
}

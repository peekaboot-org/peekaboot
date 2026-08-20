/**
 * The "Loggers" tab: logger levels grouped by package, filterable by name and
 * restrictable to loggers with an explicit configured level.
 */
import {groupList, expandedKeys, badge} from '../../shared/components.js';
import {highlightText} from '../../shared/markup.js';

export const id = 'loggers';
export const label = 'Loggers';

let currentData = null;

export function isAvailable(data) {
    return Boolean(data?.loggers?.packages?.length);
}

export function render(container, data) {
    currentData = data;
    wireControls(container);
    renderGroups(container, currentFilterValue(container));
}

function wireControls(container) {
    const input = container.querySelector('#loggers-filter');
    if (input && !input.dataset.wired) {
        input.dataset.wired = 'true';
        input.addEventListener('input', () => renderGroups(container, input.value.trim()));
    }

    const checkbox = container.querySelector('#loggers-configured-only');
    if (checkbox && !checkbox.dataset.wired) {
        checkbox.dataset.wired = 'true';
        checkbox.addEventListener('change', () => renderGroups(container, currentFilterValue(container)));
    }
}

function currentFilterValue(container) {
    return container.querySelector('#loggers-filter')?.value.trim() || '';
}

/** ERROR/WARN/INFO map to their badge variant; DEBUG/TRACE/unset fall back to muted. */
function levelVariant(level) {
    if (level === 'ERROR') return 'error';
    if (level === 'WARN') return 'warn';
    if (level === 'INFO') return 'info';
    return 'muted';
}

function renderGroups(container, filterQuery) {
    const loggersInfo = currentData?.loggers;
    const target = container.querySelector('#loggers-list');
    // Must run before the container is cleared below - see environment.js.
    const expanded = expandedKeys(target);
    target.innerHTML = '';

    const packages = loggersInfo?.packages;
    if (!packages || packages.length === 0) {
        target.innerHTML = '<p class="pk-empty">No loggers available</p>';
        return;
    }

    const configuredOnly = container.querySelector('#loggers-configured-only')?.checked || false;

    const summaryEl = document.createElement('div');
    summaryEl.className = 'pk-loggers-summary';
    summaryEl.appendChild(badge(`Total: ${loggersInfo.totalCount}`, 'muted'));
    summaryEl.appendChild(badge(`Configured: ${loggersInfo.configuredCount}`, 'muted'));
    target.appendChild(summaryEl);

    // Pre-filter packages and their loggers
    const filteredPackages = packages
        .map(group => ({
            packageName: group.packageName,
            loggers: group.loggers.filter(logger => {
                if (configuredOnly && !logger.configuredLevel) return false;
                if (filterQuery && !logger.name.toLowerCase().includes(filterQuery.toLowerCase())) return false;
                return true;
            })
        }))
        .filter(group => group.loggers.length > 0);

    if (filteredPackages.length === 0 && filterQuery) {
        target.innerHTML = '<p class="pk-empty">No loggers matching criteria</p>';
        return;
    }

    // See environment.js for why filtering forces every surviving group open.
    const effectiveExpanded = filterQuery
        ? new Set(filteredPackages.map(group => group.packageName))
        : expanded;

    groupList(target, filteredPackages, {
        key: group => group.packageName,
        header: group => ({
            name: group.packageName,
            count: `${group.loggers.length} loggers`,
            highlight: filterQuery
        }),
        items: (group, list) => group.loggers.forEach(logger =>
            list.appendChild(renderLoggerRow(logger, filterQuery))),
        expandedKeys: effectiveExpanded
    });

    // pk-group__name defaults to the primary-coloured style; logger package names use
    // the text-strong modifier instead (see the modifier's doc comment in components.css).
    target.querySelectorAll('.pk-group__name').forEach(el => el.classList.add('pk-group__name--strong'));
}

function renderLoggerRow(logger, filterQuery) {
    const row = document.createElement('div');
    row.className = 'pk-kv';

    const nameEl = document.createElement('span');
    nameEl.className = 'pk-kv__key' + (logger.configuredLevel !== null ? ' pk-kv__key--configured' : '');
    nameEl.innerHTML = highlightText(logger.name, filterQuery);
    row.appendChild(nameEl);

    row.appendChild(badge(logger.effectiveLevel || '-', levelVariant(logger.effectiveLevel)));
    return row;
}

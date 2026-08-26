/**
 * The "Loggers" tab: logger levels grouped by package, filterable by name and
 * restrictable to loggers with an explicit configured level.
 */
import {groupList, expandedKeys, badge} from '../../shared/components.js';
import {highlightText} from '../../shared/markup.js';
import {reconcileFilterWithUrl} from '../../shared/url-filter.js';

export const id = 'loggers';
export const label = 'Loggers';

let currentData = null;

// The most recent render() call's context - read by the persistent filter/checkbox
// listeners below (wired once, see wireControls) so a later render's context (its
// setUrlParams closes over the URL's tab/detail/subview at *that* call - see main.js's
// currentContext()) is always what a later change writes through, not whatever was
// current the first time this tab was rendered.
let currentContext = null;

export function isAvailable(data) {
    return Boolean(data?.loggers?.packages?.length);
}

export function render(container, data, context) {
    currentData = data;
    currentContext = context;
    wireControls(container);
    // Only while this tab is the one the hash currently points at - context.urlParams
    // reflects whatever tab is active in the URL, so reconciling during a background
    // auto-refresh render of a hidden loggers tab would read another tab's params (or
    // none) and clobber whatever the user already set here.
    if (container.classList.contains('active')) reconcileWithUrl(container);
    renderGroups(container, currentFilterValue(container));
}

function wireControls(container) {
    const input = container.querySelector('#loggers-filter');
    if (input && !input.dataset.wired) {
        input.dataset.wired = 'true';
        input.addEventListener('input', () => {
            writeUrlParams(container);
            renderGroups(container, input.value.trim());
        });
    }

    const checkbox = container.querySelector('#loggers-configured-only');
    if (checkbox && !checkbox.dataset.wired) {
        checkbox.dataset.wired = 'true';
        checkbox.addEventListener('change', () => {
            writeUrlParams(container);
            renderGroups(container, currentFilterValue(container));
        });
    }
}

function currentFilterValue(container) {
    return container.querySelector('#loggers-filter')?.value.trim() || '';
}

/**
 * Reconciles the filter input and configured-only checkbox with the URL - composes
 * shared/url-filter.js's reconcileFilterWithUrl (see its own doc comment for the
 * URL-authoritative / bare-hash direction logic every tab like this one shares) with this
 * tab's own two-field seed/write, since a single flat "q" wouldn't capture the checkbox.
 * A param that's absent from an otherwise non-bare URL means "at its default" -
 * writeUrlParams always decides both keys together, so their presence/absence is never
 * ambiguous.
 */
function reconcileWithUrl(container) {
    const input = container.querySelector('#loggers-filter');
    const checkbox = container.querySelector('#loggers-configured-only');

    reconcileFilterWithUrl(currentContext, ['q', 'configured'], {
        seed: params => {
            const urlQuery = params.q || '';
            const urlConfiguredOnly = params.configured === '1';
            if (input && urlQuery !== input.value.trim()) input.value = urlQuery;
            if (checkbox && urlConfiguredOnly !== checkbox.checked) checkbox.checked = urlConfiguredOnly;
        },
        hasNonDefaultState: () => Boolean(currentFilterValue(container) || checkbox?.checked),
        writeBack: () => writeUrlParams(container)
    });
}

/** Writes the current filter/configured-only state back to the URL, omitting each key
    that's at its default so a clean filter yields a clean "#loggers" hash. */
function writeUrlParams(container) {
    const params = {};
    const value = currentFilterValue(container);
    if (value) params.q = value;
    if (container.querySelector('#loggers-configured-only')?.checked) params.configured = '1';
    currentContext.setUrlParams(params);
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

    groupList(target, filteredPackages, {
        key: group => group.packageName,
        header: group => ({
            name: group.packageName,
            count: `${group.loggers.length} loggers`,
            highlight: filterQuery
        }),
        items: (group, list) => group.loggers.forEach(logger =>
            list.appendChild(renderLoggerRow(logger, filterQuery))),
        expandedKeys: expanded
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

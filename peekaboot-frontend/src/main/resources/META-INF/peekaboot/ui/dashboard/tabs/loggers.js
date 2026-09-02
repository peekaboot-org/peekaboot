/**
 * The "Loggers" tab: logger levels grouped by package, filterable by name and
 * restrictable to loggers with an explicit configured level.
 */
import {badge} from '../../shared/components.js';
import {formatCount} from '../../shared/format.js';
import {filteredGroupTab} from '../../shared/filtered-group-tab.js';
import {highlightText} from '../../shared/markup.js';
import {logLevelVariant} from '../../shared/severity.js';
import {reconcileFilterWithUrl} from '../../shared/url-filter.js';

export const id = 'loggers';
export const label = 'Loggers';

const tab = filteredGroupTab({
    inputId: 'loggers-filter',
    listId: 'loggers-list',
    select: data => data?.loggers?.packages,
    filterGroup: (group, query) => {
        const loggers = group.loggers.filter(logger => matches(logger, query));
        return loggers.length > 0 ? {packageName: group.packageName, loggers} : null;
    },
    key: group => group.packageName,
    header: (group, query) => ({
        name: group.packageName,
        count: formatCount(group.loggers.length, 'logger'),
        highlight: query
    }),
    items: (group, list, query) => group.loggers.forEach(logger =>
        list.appendChild(renderLoggerRow(logger, query))),
    extraTop: data => renderSummary(data.loggers),
    emptyMessage: 'No loggers available',
    noMatchMessage: () => 'No loggers matching criteria',
    urlFilter: {reconcile: reconcileWithUrl, write: writeUrlParams},
    // pk-group__name defaults to the primary-coloured style; logger package names use
    // the text-strong modifier instead (see the modifier's doc comment in components.css).
    decorate: listEl => listEl.querySelectorAll('.pk-group__name')
        .forEach(el => el.classList.add('pk-group__name--strong'))
});

// The checkbox is this tab's own second filter control: wired once here, its state
// read by matches() below and written to the URL beside the text filter's "q".
let configuredOnly = false;

export function isAvailable(data) {
    return Boolean(data?.loggers?.packages?.length);
}

export function render(container, data, context) {
    wireCheckbox(container, context);
    tab.render(container, data, context);
}

function wireCheckbox(container, context) {
    const checkbox = container.querySelector('#loggers-configured-only');
    if (!checkbox || checkbox.dataset.wired) return;
    checkbox.dataset.wired = 'true';
    checkbox.addEventListener('change', () => {
        configuredOnly = checkbox.checked;
        writeUrlParams(container.querySelector('#loggers-filter'), container, context);
        tab.refresh(container);
    });
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
function reconcileWithUrl(input, container, context) {
    const checkbox = container.querySelector('#loggers-configured-only');

    reconcileFilterWithUrl(context, ['q', 'configured'], {
        seed: params => {
            const urlQuery = params.q || '';
            const urlConfiguredOnly = params.configured === '1';
            if (input && urlQuery !== input.value.trim()) input.value = urlQuery;
            if (checkbox && urlConfiguredOnly !== checkbox.checked) checkbox.checked = urlConfiguredOnly;
            configuredOnly = urlConfiguredOnly;
        },
        hasNonDefaultState: () => Boolean(input?.value.trim() || configuredOnly),
        writeBack: () => writeUrlParams(input, container, context)
    });
}

/**
 * Writes the current filter/configured-only state back to the URL, omitting each key
 * that's at its default so a clean filter yields a clean "#loggers" hash.
 */
function writeUrlParams(input, container, context) {
    const params = {};
    const value = input?.value.trim();
    if (value) params.q = value;
    if (configuredOnly) params.configured = '1';
    context.setUrlParams(params);
}

/** A logger with an explicitly configured level, as opposed to one merely inheriting its parent's. */
function isConfigured(logger) {
    return logger.configuredLevel != null;
}

function matches(logger, query) {
    if (configuredOnly && !isConfigured(logger)) return false;
    return !query || logger.name.toLowerCase().includes(query.toLowerCase());
}

function renderSummary(loggersInfo) {
    const summaryEl = document.createElement('div');
    summaryEl.className = 'pk-loggers-summary';
    summaryEl.appendChild(badge(`Total: ${loggersInfo.totalCount}`, 'muted'));
    summaryEl.appendChild(badge(`Configured: ${loggersInfo.configuredCount}`, 'muted'));
    return summaryEl;
}

function renderLoggerRow(logger, query) {
    const row = document.createElement('div');
    row.className = 'pk-kv';

    const nameEl = document.createElement('span');
    nameEl.className = 'pk-kv__key' + (isConfigured(logger) ? ' pk-kv__key--configured' : '');
    nameEl.innerHTML = highlightText(logger.name, query);
    row.appendChild(nameEl);

    row.appendChild(badge(logger.effectiveLevel || '-', logLevelVariant(logger.effectiveLevel)));
    return row;
}

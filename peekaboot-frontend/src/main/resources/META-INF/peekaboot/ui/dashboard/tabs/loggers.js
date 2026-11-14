/**
 * The "Loggers" tab: logger levels grouped by package, filterable by name and
 * restrictable to loggers with an explicit configured level.
 */
import {badge, countSummary} from '../../shared/components.js';
import {el} from '../../shared/dom.js';
import {formatCount} from '../../shared/format.js';
import {filteredGroupTab} from '../../shared/filtered-group-tab.js';
import {highlightText} from '../../shared/markup.js';
import {logLevelVariant} from '../../shared/severity.js';
import {urlState} from '../../shared/url-filter.js';

export const id = 'loggers';

export function isAvailable(data) {
    return Boolean(data?.loggers?.packages?.length);
}

export function create(container) {
    // The checkbox is this tab's own second filter control: its state is read by the
    // group filter below and kept in the URL beside the text filter's "q".
    const checkbox = container.querySelector('#loggers-configured-only');
    let configuredOnly = false;

    const tab = filteredGroupTab(container, {
        inputId: 'loggers-filter',
        listId: 'loggers-list',
        select: data => data?.loggers?.packages,
        filterGroup: (group, query) => {
            const loggers = group.loggers.filter(logger => matches(logger, query, configuredOnly));
            return loggers.length > 0 ? {packageName: group.packageName, loggers} : null;
        },
        key: group => group.packageName,
        header: (group, query, context) => ({
            name: group.packageName,
            count: formatCount(group.loggers.length, 'logger', {locale: context.locale}),
            highlight: query,
            nameModifier: 'strong'
        }),
        items: (group, list, query) => group.loggers.forEach(logger =>
            list.appendChild(renderLoggerRow(logger, query))),
        extraTop: ({loggers}, context) => countSummary([
            ['Total', loggers.totalCount],
            ['Configured', loggers.configuredCount]
        ], context.locale),
        emptyMessage: 'No loggers available',
        noMatchMessage: () => 'No loggers matching criteria',
        // The filter input and the checkbox as one URL state (see shared/url-filter.js),
        // since a single "q" would not carry the checkbox. Both keys are written together,
        // so a param missing from a URL that names the other is at its default.
        filterState: input => urlState({
            keys: ['q', 'configured'],
            read: urlParams => ({query: urlParams.q || '', configuredOnly: urlParams.configured === '1'}),
            apply: state => {
                if (state.query !== input.value.trim()) input.value = state.query;
                if (checkbox) checkbox.checked = state.configuredOnly;
                configuredOnly = state.configuredOnly;
            },
            params: () => {
                const params = {};
                const query = input.value.trim();
                if (query) params.q = query;
                if (configuredOnly) params.configured = '1';
                return params;
            }
        })
    });

    checkbox?.addEventListener('change', () => {
        configuredOnly = checkbox.checked;
        tab.refresh();
    });

    return tab;
}

/** A logger with an explicitly configured level, as opposed to one merely inheriting its parent's. */
function isConfigured(logger) {
    return logger.configuredLevel != null;
}

function matches(logger, query, configuredOnly) {
    if (configuredOnly && !isConfigured(logger)) return false;
    return !query || logger.name.toLowerCase().includes(query.toLowerCase());
}

function renderLoggerRow(logger, query) {
    const name = el('span', {className: 'pk-kv__key' + (isConfigured(logger) ? ' pk-kv__key--configured' : '')});
    name.innerHTML = highlightText(logger.name, query);
    return el('div', {className: 'pk-kv'}, name, badge(logger.effectiveLevel || '-', logLevelVariant(logger.effectiveLevel)));
}

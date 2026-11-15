/**
 * The "Meters" tab: Micrometer meters, filterable by name or tag, each expandable to
 * its measurements. Built on shared/filtered-group-tab.js like the other filterable
 * group tabs, using its fetchData hook: the metrics come from their own endpoint, not
 * the main dashboard payload.
 */
import {badge} from '../../shared/components.js';
import {el, highlighted} from '../../shared/dom.js';
import {formatCount, formatMeasurementValue, formatNumber} from '../../shared/format.js';
import {filteredGroupTab} from '../../shared/filtered-group-tab.js';

export const id = 'meters';

export function isAvailable(data, features) {
    return Boolean(features?.metrics);
}

export function create(container) {
    return filteredGroupTab(container, {
        inputId: 'meters-filter',
        listId: 'meters-list',
        fetchData: async context => {
            const result = await context.client.get('/api/metrics');
            return result === null ? null : result.metrics || [];
        },
        loadingMessage: 'Loading metrics...',
        fetchErrorMessage: error => `Failed to load metrics: ${error.message}`,
        select: metrics => metrics,
        // a metric either matches as a whole or not at all - its measurements are never narrowed
        filterGroup: (metric, query) => (!query || matchesMetricFilter(metric, query) ? metric : null),
        key: metric => metric.name,
        header: (metric, query, context) => ({
            name: metric.name,
            count: formatCount(metric.measurements.length, 'measurement', {locale: context.locale}),
            highlight: query,
            nameModifier: 'mono',
            // the unit cell is there even when empty: without it the count slides into the unit's column
            meta: [badge(metric.type, 'info'), el('span', {className: 'pk-metric__unit', text: metric.baseUnit || ''})]
        }),
        items: (metric, list, query, context) => {
            if (metric.description) list.appendChild(el('div', {className: 'pk-metric__description', text: metric.description}));
            metric.measurements.forEach(measurement =>
                list.appendChild(renderMeasurement(measurement, query, metric.baseUnit, context.locale)));
        },
        emptyMessage: 'No metrics available',
        noMatchMessage: query => `No metrics matching "${query}"`,
        afterRender: updateCount
    });
}

/** The "N / M metrics" readout beside the filter input, updated on every render. */
function updateCount(container, {groups, filtered, query, context}) {
    const countEl = container.querySelector('#meters-count');
    if (!countEl) return;
    if (groups.length === 0) {
        countEl.textContent = '';
    } else {
        const locale = context.locale;
        countEl.textContent = query
            ? `${formatNumber(filtered.length, {locale})} / ${formatNumber(groups.length, {locale})} metrics`
            : `${formatNumber(groups.length, {locale})} metrics`;
    }
}

function matchesMetricFilter(metric, query) {
    const lowerQuery = query.toLowerCase();
    if (metric.name.toLowerCase().includes(lowerQuery)) return true;

    for (const measurement of metric.measurements) {
        for (const [key, value] of Object.entries(measurement.tags || {})) {
            if (key.toLowerCase().includes(lowerQuery) || value.toLowerCase().includes(lowerQuery)) return true;
        }
    }
    return false;
}

function renderMeasurement(measurement, filterQuery, baseUnit, locale) {
    const tags = Object.entries(measurement.tags || {});
    const tagsEl = el('div', {className: 'pk-metric__tags'},
        ...(tags.length === 0 ? [el('span', {className: 'pk-note pk-metric__no-tags', text: 'no tags'})] : tags.map(([key, value]) =>
            el('span', {className: 'pk-metric__tag'}, ...highlighted(key, filterQuery), '=', ...highlighted(value, filterQuery)))));

    const statsEl = el('div', {className: 'pk-metric__stats'},
        ...(measurement.statistics || []).map(stat => el('span', {className: 'pk-metric__stat'},
            el('span', {className: 'pk-metric__stat-name', text: stat.name}),
            el('span', {className: 'pk-metric__stat-value', text: formatMeasurementValue(stat.value, baseUnit, {locale})}))));

    return el('div', {className: 'pk-kv'}, tagsEl, statsEl);
}

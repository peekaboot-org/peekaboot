/**
 * The "Meters" tab: Micrometer meters, filterable by name or tag, each expandable to
 * its measurements. Built on shared/filtered-group-tab.js like the other filterable
 * group tabs, using its fetchData hook: the metrics come from their own endpoint, not
 * the main dashboard payload.
 */
import {badge} from '../../shared/components.js';
import {el} from '../../shared/dom.js';
import {highlightText} from '../../shared/markup.js';
import {formatBytes, formatCount, formatNumber} from '../../shared/format.js';
import {filteredGroupTab} from '../../shared/filtered-group-tab.js';

export const id = 'meters';

const tab = filteredGroupTab({
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
        highlight: query
    }),
    items: (metric, list, query, context) => {
        if (metric.description) list.appendChild(el('div', {className: 'pk-metric__description', text: metric.description}));
        metric.measurements.forEach(measurement =>
            list.appendChild(renderMeasurement(measurement, query, metric.baseUnit, context.locale)));
    },
    emptyMessage: 'No metrics available',
    noMatchMessage: query => `No metrics matching "${query}"`,
    decorate: decorateGroupHeaders,
    afterRender: updateCount
});

export function isAvailable(data, features) {
    return Boolean(features?.metrics);
}

export function render(container, data, context) {
    tab.render(container, data, context);
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

/**
 * The shared group() header only knows name/count - the metric type badge and unit
 * are appended afterwards, and the name gets the --mono modifier group()'s own doc
 * comment reserves for metric names.
 *
 * The three trailing items move into a .pk-group__meta grid so they line up as columns
 * down the list. The unit cell is always emitted, empty when the metric has no base
 * unit, because a missing cell would slide the count into the unit's column.
 */
function decorateGroupHeaders(listEl, metrics) {
    const metricsByName = new Map(metrics.map(metric => [metric.name, metric]));

    listEl.querySelectorAll('.pk-group').forEach(groupEl => {
        const metric = metricsByName.get(groupEl.dataset.groupKey);
        if (!metric) return;

        groupEl.querySelector('.pk-group__name').classList.add('pk-group__name--mono');

        const countEl = groupEl.querySelector('.pk-group__count');
        const meta = el('span', {className: 'pk-group__meta'});
        countEl.replaceWith(meta);
        meta.append(badge(metric.type, 'info'), el('span', {className: 'pk-metric__unit', text: metric.baseUnit || ''}), countEl);
    });
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
        ...(tags.length === 0 ? [el('span', {className: 'pk-note pk-metric__no-tags', text: 'no tags'})] : tags.map(([key, value]) => {
            const tag = el('span', {className: 'pk-metric__tag'});
            tag.innerHTML = `${highlightText(key, filterQuery)}=${highlightText(value, filterQuery)}`;
            return tag;
        })));

    const statsEl = el('div', {className: 'pk-metric__stats'},
        ...(measurement.statistics || []).map(stat => el('span', {className: 'pk-metric__stat'},
            el('span', {className: 'pk-metric__stat-name', text: stat.name}),
            el('span', {className: 'pk-metric__stat-value', text: formatMeasurementValue(stat.value, baseUnit, locale)}))));

    return el('div', {className: 'pk-kv'}, tagsEl, statsEl);
}

function formatMeasurementValue(value, unit, locale) {
    if (value === null || value === undefined || Number.isNaN(value)) return '-';

    if (unit === 'bytes') return formatBytes(value);

    if (unit === 'seconds') {
        if (value < 0.001) return `${(value * 1000000).toFixed(0)} µs`;
        if (value < 1) return `${(value * 1000).toFixed(2)} ms`;
        return `${value.toFixed(3)} s`;
    }

    if (Math.abs(value) >= 1000) return value.toLocaleString(locale, {maximumFractionDigits: 2});
    if (value !== 0 && Math.abs(value) < 0.01) return value.toExponential(2);
    return value.toLocaleString(locale, {maximumFractionDigits: 4});
}

/**
 * The "Meters" tab: Micrometer meters, filterable by name or tag, each expandable to
 * its measurements. Built on shared/filtered-group-tab.js like the other filterable
 * group tabs, using its fetchData hook: the metrics come from their own endpoint (not
 * the main dashboard payload) and are fetched only while this tab's container is the
 * active one - see the hook's doc comment in the shell.
 */
import {badge} from '../../shared/components.js';
import {highlightText} from '../../shared/markup.js';
import {formatBytes} from '../../shared/format.js';
import {filteredGroupTab} from '../../shared/filtered-group-tab.js';

export const id = 'meters';
export const label = 'Meters';

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
    header: (metric, query) => ({
        name: metric.name,
        count: `${metric.measurements.length} measurement${metric.measurements.length !== 1 ? 's' : ''}`,
        highlight: query
    }),
    items: (metric, list, query, context) => {
        if (metric.description) {
            const descEl = document.createElement('div');
            descEl.className = 'pk-metric__description';
            descEl.textContent = metric.description;
            list.appendChild(descEl);
        }
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
function updateCount(container, {groups, filtered, query}) {
    const countEl = container.querySelector('#meters-count');
    if (!countEl) return;
    if (groups.length === 0) {
        countEl.textContent = '';
    } else {
        countEl.textContent = query
            ? `${filtered.length} / ${groups.length} metrics`
            : `${groups.length} metrics`;
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
        const meta = document.createElement('span');
        meta.className = 'pk-group__meta';

        const unitEl = document.createElement('span');
        unitEl.className = 'pk-metric__unit';
        unitEl.textContent = metric.baseUnit || '';

        countEl.replaceWith(meta);
        meta.append(badge(metric.type, 'info'), unitEl, countEl);
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
    const el = document.createElement('div');
    el.className = 'pk-metric__measurement';

    const tagsEl = document.createElement('div');
    tagsEl.className = 'pk-metric__tags';
    const tags = Object.entries(measurement.tags || {});
    if (tags.length === 0) {
        tagsEl.innerHTML = '<span class="pk-metric__no-tags">no tags</span>';
    } else {
        tags.forEach(([key, value]) => {
            const tagEl = document.createElement('span');
            tagEl.className = 'pk-metric__tag';
            tagEl.innerHTML = `${highlightText(key, filterQuery)}=${highlightText(value, filterQuery)}`;
            tagsEl.appendChild(tagEl);
        });
    }

    const statsEl = document.createElement('div');
    statsEl.className = 'pk-metric__stats';
    (measurement.statistics || []).forEach(stat => {
        const statEl = document.createElement('span');
        statEl.className = 'pk-metric__stat';
        const nameEl = document.createElement('span');
        nameEl.className = 'pk-metric__stat-name';
        nameEl.textContent = stat.name;
        const valueEl = document.createElement('span');
        valueEl.className = 'pk-metric__stat-value';
        valueEl.textContent = formatMeasurementValue(stat.value, baseUnit, locale);
        statEl.append(nameEl, valueEl);
        statsEl.appendChild(statEl);
    });

    el.append(tagsEl, statsEl);
    return el;
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

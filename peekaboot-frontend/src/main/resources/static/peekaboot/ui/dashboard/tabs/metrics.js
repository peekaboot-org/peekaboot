/**
 * The "Metrics" tab: Micrometer meters, filterable by name or tag, each expandable to
 * its measurements. Fetched from its own endpoint (not part of the main dashboard
 * payload), so it manages its own fetch/loading state independently of the other tabs.
 */
import {groupList, badge} from '../../shared/components.js';
import {escapeHtml, highlightText} from '../../shared/markup.js';
import {formatBytes} from '../../shared/format.js';

export const id = 'metrics';
export const label = 'Metrics';

let latestMetrics = null;

export function isAvailable(data, features) {
    return Boolean(features?.metrics);
}

export function render(container, data, context) {
    wireFilter(container, context);
    fetchAndRender(container, context);
}

function wireFilter(container, context) {
    const input = container.querySelector('#metrics-filter');
    if (!input || input.dataset.wired) return;
    input.dataset.wired = 'true';
    input.addEventListener('input', () => renderList(container, input.value.trim(), context.locale));
}

function currentFilterValue(container) {
    return container.querySelector('#metrics-filter')?.value.trim() || '';
}

async function fetchAndRender(container, context) {
    const listEl = container.querySelector('#metrics-list');
    listEl.innerHTML = '<div class="pk-loading"><div class="pk-spinner"></div><p>Loading metrics...</p></div>';

    let result;
    try {
        result = await context.client.get('/api/metrics');
    } catch (error) {
        listEl.innerHTML = `<p class="pk-empty">Failed to load metrics: ${escapeHtml(error.message)}</p>`;
        return;
    }
    if (result === null) return; // superseded by a newer request

    latestMetrics = result?.metrics || [];
    renderList(container, currentFilterValue(container), context.locale);
}

function renderList(container, filterQuery, locale) {
    const listEl = container.querySelector('#metrics-list');
    const countEl = container.querySelector('#metrics-count');
    listEl.innerHTML = '';

    const metrics = latestMetrics;
    if (!metrics || metrics.length === 0) {
        listEl.innerHTML = '<p class="pk-empty">No metrics available</p>';
        if (countEl) countEl.textContent = '';
        return;
    }

    const filteredMetrics = filterQuery ? metrics.filter(m => matchesMetricFilter(m, filterQuery)) : metrics;

    if (countEl) {
        countEl.textContent = filterQuery
            ? `${filteredMetrics.length} / ${metrics.length} metrics`
            : `${metrics.length} metrics`;
    }

    if (filteredMetrics.length === 0) {
        listEl.innerHTML = `<p class="pk-empty">No metrics matching "${escapeHtml(filterQuery)}"</p>`;
        return;
    }

    groupList(listEl, filteredMetrics, {
        key: metric => metric.name,
        header: metric => ({
            name: metric.name,
            count: `${metric.measurements.length} measurement${metric.measurements.length !== 1 ? 's' : ''}`,
            highlight: filterQuery
        }),
        items: (metric, list) => {
            if (metric.description) {
                const descEl = document.createElement('div');
                descEl.className = 'pk-metric__description';
                descEl.textContent = metric.description;
                list.appendChild(descEl);
            }
            metric.measurements.forEach(measurement =>
                list.appendChild(renderMeasurement(measurement, filterQuery, metric.baseUnit, locale)));
        }
    });

    decorateGroupHeaders(listEl, filteredMetrics);
}

/**
 * The shared group() header only knows name/count - the metric type badge and unit
 * (which the six-way .metric-type-badge.type-* CSS split collapsed into a single
 * badge('type', 'info')) are appended afterwards, and the name gets the --mono
 * modifier group()'s own doc comment reserves for metric names.
 */
function decorateGroupHeaders(listEl, metrics) {
    const metricsByName = new Map(metrics.map(metric => [metric.name, metric]));

    listEl.querySelectorAll('.pk-group').forEach(groupEl => {
        const metric = metricsByName.get(groupEl.dataset.groupKey);
        if (!metric) return;

        groupEl.querySelector('.pk-group__name').classList.add('pk-group__name--mono');

        const countEl = groupEl.querySelector('.pk-group__count');
        countEl.before(badge(metric.type, 'info'));
        if (metric.baseUnit) {
            const unitEl = document.createElement('span');
            unitEl.className = 'pk-metric__unit';
            unitEl.textContent = metric.baseUnit;
            countEl.before(unitEl);
        }
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
        valueEl.textContent = formatMetricValue(stat.value, baseUnit, locale);
        statEl.append(nameEl, valueEl);
        statsEl.appendChild(statEl);
    });

    el.append(tagsEl, statsEl);
    return el;
}

function formatMetricValue(value, unit, locale) {
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

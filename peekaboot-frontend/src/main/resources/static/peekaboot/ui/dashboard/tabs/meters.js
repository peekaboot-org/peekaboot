/**
 * The "Meters" tab: Micrometer meters, filterable by name or tag, each expandable to
 * its measurements. Fetched from its own endpoint (not part of the main dashboard
 * payload). render() is called on every 30s auto-refresh cycle for every available tab
 * regardless of which is visible (see main.js's renderData()), so the actual network
 * fetch is skipped here unless this tab's container is the active one - main.js's
 * renderTabById() calls render() again the moment this tab becomes active, so switching
 * to it never waits on the next cycle.
 */
import {groupList, expandedKeys, badge} from '../../shared/components.js';
import {escapeHtml, highlightText} from '../../shared/markup.js';
import {formatBytes} from '../../shared/format.js';
import {reconcileTextFilter, writeTextFilter} from '../../shared/url-filter.js';

export const id = 'meters';
export const label = 'Meters';

let latestMetrics = null;

// The most recent render() call's container/context - read by the persistent filter
// input listener below (wired once, see wireFilter) so a later locale change, or a
// later fetch, always uses fresh values instead of whatever was current the first time
// this tab was rendered.
let currentContainer = null;
let currentContext = null;

export function isAvailable(data, features) {
    return Boolean(features?.metrics);
}

export function render(container, data, context) {
    currentContainer = container;
    currentContext = context;
    wireFilter(container);
    // Only while this tab is the one the hash currently points at - context.urlParams
    // reflects whatever tab is active in the URL, so reconciling during a background
    // auto-refresh render of a hidden meters tab would read another tab's params (or
    // none) and clobber whatever the user already typed here.
    if (container.classList.contains('active')) reconcileTextFilter(container.querySelector('#meters-filter'), context);
    fetchAndRender();
}

function wireFilter(container) {
    const input = container.querySelector('#meters-filter');
    if (!input || input.dataset.wired) return;
    input.dataset.wired = 'true';
    input.addEventListener('input', () => {
        writeTextFilter(input, currentContext);
        renderList(input.value.trim());
    });
}

function currentFilterValue(container) {
    return container.querySelector('#meters-filter')?.value.trim() || '';
}

async function fetchAndRender() {
    const container = currentContainer;
    const context = currentContext;
    // Not the active tab - skip the network round trip. main.js's renderTabById() calls
    // render() (and so this) again the instant this tab is switched to.
    if (!container.classList.contains('active')) return;

    const listEl = container.querySelector('#meters-list');
    // Only show the loading state on the very first fetch - a background refresh of an
    // already-populated, currently visible list must not blank it for the round trip's
    // duration (renderList replaces the content once the response is in hand).
    if (latestMetrics === null) {
        listEl.innerHTML = '<div class="pk-loading"><div class="pk-spinner"></div><p>Loading metrics...</p></div>';
    }

    let result;
    try {
        result = await context.client.get('/api/metrics');
    } catch (error) {
        listEl.innerHTML = `<p class="pk-empty">Failed to load metrics: ${escapeHtml(error.message)}</p>`;
        return;
    }
    if (result === null) return; // superseded by a newer request

    latestMetrics = result?.metrics || [];
    renderList(currentFilterValue(container));
}

function renderList(filterQuery) {
    const container = currentContainer;
    const context = currentContext;
    const listEl = container.querySelector('#meters-list');
    const countEl = container.querySelector('#meters-count');
    // Must run before the container is cleared below - see environment.js.
    const expanded = expandedKeys(listEl);
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
                list.appendChild(renderMeasurement(measurement, filterQuery, metric.baseUnit, context.locale)));
        },
        expandedKeys: expanded
    });

    decorateGroupHeaders(listEl, filteredMetrics);
}

/**
 * The shared group() header only knows name/count - the metric type badge and unit
 * (which the six-way .metric-type-badge.type-* CSS split collapsed into a single
 * badge('type', 'info')) are appended afterwards, and the name gets the --mono
 * modifier group()'s own doc comment reserves for metric names.
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

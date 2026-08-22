/**
 * The "Insights" tab: aggregated metric charts (uPlot) with tiles, a global
 * aggregation-level selector and SSE-driven live updates. All grouping and
 * ordering comes from /api/insights/config - this module renders it verbatim.
 *
 * Chart rendering and SSE wiring are a follow-up task; this module lays out the
 * tab shell - toolbar, tiles and empty panel cards with their chart mounts.
 */
import {escapeHtml} from '../../shared/markup.js';
import {formatTileValue} from '../../shared/format.js';

export const id = 'insights';
export const label = 'Insights';

let initialized = false;
let config = null;          // /config response
let currentContext = null;

export function isAvailable(data, features) {
    return Boolean(features?.insights);
}

export function render(container, data, context) {
    currentContext = context;
    if (initialized) return;   // SSE keeps this tab live; the 30s cycle must not rebuild it
    if (!container.classList.contains('active')) return;
    initialized = true;
    init(container, context);
}

async function init(container, context) {
    config = await context.client.get('/api/insights/config');
    renderToolbar(container);
    renderTiles(container);
    renderPanels(container);
    // chart + SSE wiring arrives in the next task
}

// --- Interval formatting --------------------------------------------------------------

/** Compact aggregation-level label, e.g. 250 -> "250ms", 1500 -> "1.5s", 3600000 -> "1h". */
function formatInterval(ms) {
    const short = value => (Number.isInteger(value) ? String(value) : value.toFixed(1));
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60000) return `${short(ms / 1000)}s`;
    if (ms < 3600000) return `${short(ms / 60000)}m`;
    return `${short(ms / 3600000)}h`;
}

function levelOptionsHtml() {
    return config.levels
        .map(level => `<option value="${level.index}">${escapeHtml(formatInterval(level.intervalMs))}</option>`)
        .join('');
}

// --- Toolbar ----------------------------------------------------------------------------

function renderToolbar(container) {
    const toolbar = container.querySelector('#insights-toolbar');
    toolbar.innerHTML = `
        <select id="insights-level">${levelOptionsHtml()}</select>
        <label><input type="checkbox" id="insights-percentiles"> Percentiles</label>
    `;
}

// --- Tiles ------------------------------------------------------------------------------

function renderTiles(container) {
    const tiles = container.querySelector('#insights-tiles');
    tiles.innerHTML = config.tiles.map(tile => `
        <div class="pk-insight-tile" data-tile-id="${escapeHtml(tile.id)}">
            <div class="pk-insight-tile-label">${escapeHtml(tile.label)}</div>
            <div class="pk-insight-tile-value">${escapeHtml(formatTileValue(tile.value, tile.format, currentContext))}</div>
        </div>
    `).join('');
}

// --- Panels -----------------------------------------------------------------------------

function renderPanels(container) {
    const panels = container.querySelector('#insights-panels');
    panels.innerHTML = config.panels.map(panel => `
        <div class="pk-insight-panel" data-panel-id="${escapeHtml(panel.id)}">
            <div class="pk-insight-panel__header">
                <h3 class="pk-insight-panel__title">${escapeHtml(panel.title)}</h3>
                <span class="pk-insight-current"></span>
                <select class="pk-insight-panel-level">${levelOptionsHtml()}</select>
            </div>
            <div class="pk-insight-chart"></div>
        </div>
    `).join('');

    panels.querySelectorAll('.pk-insight-panel').forEach(panelEl => {
        const panel = config.panels.find(p => p.id === panelEl.dataset.panelId);
        const levelSelect = panelEl.querySelector('.pk-insight-panel-level');
        levelSelect.value = String(panel.level ?? config.levels[0].index);
    });
}

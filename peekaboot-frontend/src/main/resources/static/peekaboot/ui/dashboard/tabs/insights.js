/**
 * The "Insights" tab: aggregated metric charts (uPlot) with tiles, a global
 * aggregation-level selector and SSE-driven live updates. All grouping and
 * ordering comes from /api/insights/config - this module renders it verbatim.
 *
 * The tab owns three things beyond its markup:
 *   - a client-side mirror of the server's ring buffers, one entry per loaded
 *     level, kept current by the tick/rollup events instead of by polling;
 *   - per-panel chart state, where a chart only exists once its card has been
 *     scrolled into view (see chartObserver) - a hidden panel keeps collecting
 *     data and redraws once, on re-entry;
 *   - the EventSource itself, created once per page: render() is called again on
 *     every 30s dashboard refresh and must not rebuild any of this.
 */
import {escapeHtml} from '../../shared/markup.js';
import {formatMetricValue, formatTileValue} from '../../shared/format.js';
import {createChart, ensureUplot} from './insights-chart.js';

export const id = 'insights';
export const label = 'Insights';

const STAT_NAMES = ['min', 'max', 'avg', 'median', 'p90', 'p95', 'p99'];

let initialized = false;
let config = null;          // /config response
let currentContext = null;

const levels = new Map();       // level index -> snapshot (see normalizeLevel)
const levelLoads = new Map();   // level index -> in-flight load promise
const panels = new Map();       // panel id -> panel state (see createPanelState)
const tileValues = new Map();   // tile id -> its value element

let source = null;
let resyncPending = false;
let chartObserver = null;
let sizeObserver = null;
let themeObserver = null;
let frame = null;
let pendingTiles = null;
let showPercentiles = false;

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
    try {
        config = await context.client.get('/api/insights/config');
        renderToolbar(container);
        renderTiles(container);
        renderPanels(container);
        initPanels(container);
        // the stream is opened before the first snapshot is fetched: tiles and
        // readouts then go live with the next tick instead of waiting on a
        // request that carries every series' whole ring. A tick arriving before
        // the snapshot lands has nowhere to go and is dropped - the snapshot it
        // is waiting for already contains that sample.
        connectStream();
        observeCharts();
        // level 0 backs the panel readouts and is where every tick lands, so it
        // is loaded up front rather than on a panel's demand; the charts coming
        // into view share this one request
        await ensureLevel(0);
    } catch (error) {
        console.warn('Insights tab failed to initialise:', error);
        teardown();
        initialized = false;   // a later refresh cycle retries from scratch
    }
}

/** Releases everything init() may have wired up; safe to call at any point of init. */
function teardown() {
    if (source) source.close();
    source = null;
    chartObserver?.disconnect();
    sizeObserver?.disconnect();
    themeObserver?.disconnect();
    chartObserver = sizeObserver = themeObserver = null;
    if (frame !== null) cancelAnimationFrame(frame);
    frame = null;
    panels.forEach(destroyChart);
    panels.clear();
    tileValues.clear();
    levels.clear();
    levelLoads.clear();
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
        <select id="insights-level" aria-label="Aggregation level for all panels">${levelOptionsHtml()}</select>
        <label><input type="checkbox" id="insights-percentiles"> Percentiles</label>
    `;

    toolbar.querySelector('#insights-level').addEventListener('change', event => {
        const level = Number(event.target.value);
        panels.forEach(panel => {
            if (panel.overridden) return;
            panel.levelSelect.value = String(level);
            setPanelLevel(panel, level);
        });
    });

    toolbar.querySelector('#insights-percentiles').addEventListener('change', event => {
        showPercentiles = event.target.checked;
        panels.forEach(panel => panel.chart?.setPercentiles(showPercentiles));
    });
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

    tiles.querySelectorAll('.pk-insight-tile').forEach(tile =>
            tileValues.set(tile.dataset.tileId, tile.querySelector('.pk-insight-tile-value')));
}

/** Restarts the CSS blink animation, which only replays if the class is re-added. */
function blink(element) {
    element.classList.remove('pk-blink');
    void element.offsetWidth;
    element.classList.add('pk-blink');
}

function updateText(element, text) {
    if (!element || element.textContent === text) return;
    element.textContent = text;
    blink(element);
}

function updateTiles(values) {
    config.tiles.forEach(tile => {
        if (!(tile.id in values)) return;
        updateText(tileValues.get(tile.id), formatTileValue(values[tile.id], tile.format, currentContext));
    });
}

// --- Panels -----------------------------------------------------------------------------

function renderPanels(container) {
    const panelsEl = container.querySelector('#insights-panels');
    panelsEl.innerHTML = config.panels.map(panel => `
        <div class="pk-insight-panel" data-panel-id="${escapeHtml(panel.id)}">
            <div class="pk-insight-panel__header">
                <h3 class="pk-insight-panel__title">${escapeHtml(panel.title)}</h3>
                <span class="pk-insight-current"></span>
                <select class="pk-insight-panel-level"
                        aria-label="${escapeHtml(panel.title)} aggregation level">${levelOptionsHtml()}</select>
            </div>
            <div class="pk-insight-chart"></div>
        </div>
    `).join('');
}

function initPanels(container) {
    container.querySelectorAll('#insights-panels .pk-insight-panel').forEach(element => {
        const definition = config.panels.find(panel => panel.id === element.dataset.panelId);
        const panel = createPanelState(definition, element);
        panels.set(definition.id, panel);

        panel.levelSelect.value = String(panel.level);
        panel.levelSelect.addEventListener('change', event => {
            panel.overridden = true;
            setPanelLevel(panel, Number(event.target.value));
        });
    });
}

function createPanelState(definition, element) {
    return {
        definition,
        element,
        mount: element.querySelector('.pk-insight-chart'),
        readout: element.querySelector('.pk-insight-current'),
        levelSelect: element.querySelector('.pk-insight-panel-level'),
        // a panel-level in the config is an initial override: the global selector
        // leaves it alone until its own selector has been touched
        level: definition.level ?? config.levels[0].index,
        overridden: definition.level != null,
        chart: null,
        creating: false,
        // bumped by every rebuild, so an async build that a level or theme switch
        // has overtaken can tell and stand down
        generation: 0,
        visible: false,
        dirty: false
    };
}

/** Latest non-null raw value of the panel's first series, whatever level it charts. */
function currentValue(panel) {
    const snapshot = levels.get(0);
    const values = snapshot?.series[panel.definition.series[0].id];
    if (!Array.isArray(values)) return null;
    for (let i = values.length - 1; i >= 0; i--) {
        if (values[i] != null) return values[i];
    }
    return null;
}

function updateReadouts() {
    panels.forEach(panel => {
        const first = panel.definition.series[0];
        const unit = first.unit || panel.definition.unit;
        updateText(panel.readout, formatMetricValue(currentValue(panel), unit));
    });
}

// --- Level data store ---------------------------------------------------------------------

/**
 * The /data response as a mutable mirror of the server ring: {@code series} holds
 * either the raw values (level 0) or one array per stat (levels >= 1), and grows
 * by one sample per tick/rollup until it reaches the level's configured size.
 */
function normalizeLevel(body) {
    const series = {};
    Object.entries(body.series).forEach(([key, entry]) => {
        series[key] = body.level === 0 ? (entry.values ?? []) : normalizeStats(entry.stats);
    });
    return {
        level: body.level,
        intervalMs: body.intervalMs,
        endEpochMs: body.endEpochMs,
        count: body.count,
        size: config.levels.find(level => level.index === body.level)?.size ?? body.count,
        series
    };
}

function normalizeStats(stats) {
    const result = {};
    STAT_NAMES.forEach(name => {
        result[name] = stats?.[name] ?? [];
    });
    return result;
}

/**
 * The query string is part of the path handed to client.get() on purpose: the
 * client de-duplicates concurrent calls per path, so passing the level as a
 * param would make a level-1 load cancel an in-flight level-0 load.
 */
async function loadLevel(level) {
    const body = await currentContext.client.get(`/api/insights/data?level=${level}`);
    if (body) levels.set(level, normalizeLevel(body));
}

/** Loads a level at most once; concurrent callers share the in-flight request. */
function ensureLevel(level) {
    if (levels.has(level)) return Promise.resolve();
    if (!levelLoads.has(level)) {
        levelLoads.set(level, loadLevel(level).finally(() => levelLoads.delete(level)));
    }
    return levelLoads.get(level);
}

function numberOrNull(value) {
    return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function pushCapped(values, value, size) {
    values.push(value);
    while (values.length > size) values.shift();
}

function seriesArray(snapshot, key) {
    if (!snapshot.series[key]) {
        snapshot.series[key] = snapshot.level === 0
            ? new Array(snapshot.count).fill(null)
            : normalizeStats(null);
    }
    return snapshot.series[key];
}

/**
 * An event is only applicable if it is newer than what the mirrored ring already
 * holds: a /data snapshot loaded while events were in flight (first subscribe, or
 * a reconnect resync) already contains them, and appending them again would
 * duplicate samples and shift the whole history.
 */
function isStale(snapshot, event) {
    return snapshot.endEpochMs > 0 && event.epochMs <= snapshot.endEpochMs;
}

/**
 * Boundaries the server skipped between the ring's end and this event. Samples
 * carry no timestamps - sample i sits (count - 1 - i) intervals before endEpochMs -
 * so a gap has to be pushed as nulls or every older sample shifts forward in time.
 * Capped at the ring size; beyond that everything visible is a gap anyway.
 */
function missedSamples(snapshot, event) {
    if (!snapshot.endEpochMs || !snapshot.intervalMs) return 0;
    const missed = Math.floor((event.epochMs - snapshot.endEpochMs) / snapshot.intervalMs) - 1;
    return Math.max(0, Math.min(missed, snapshot.size));
}

function appendTick(event) {
    const snapshot = levels.get(0);
    if (!snapshot || isStale(snapshot, event)) return;
    const values = event.values ?? {};
    Object.keys(values).forEach(key => seriesArray(snapshot, key));
    const missed = missedSamples(snapshot, event);
    Object.entries(snapshot.series).forEach(([key, series]) => {
        for (let i = 0; i < missed; i++) pushCapped(series, null, snapshot.size);
        pushCapped(series, numberOrNull(values[key]), snapshot.size);
    });
    snapshot.count = Math.min(snapshot.count + missed + 1, snapshot.size);
    snapshot.endEpochMs = event.epochMs;
}

function appendRollup(event) {
    const snapshot = levels.get(event.level);
    if (!snapshot || isStale(snapshot, event)) return;
    const entries = event.entries ?? {};
    Object.keys(entries).forEach(key => seriesArray(snapshot, key));
    const missed = missedSamples(snapshot, event);
    Object.entries(snapshot.series).forEach(([key, stats]) => {
        STAT_NAMES.forEach(name => {
            for (let i = 0; i < missed; i++) pushCapped(stats[name], null, snapshot.size);
            pushCapped(stats[name], numberOrNull(entries[key]?.[name]), snapshot.size);
        });
    });
    snapshot.count = Math.min(snapshot.count + missed + 1, snapshot.size);
    snapshot.endEpochMs = event.epochMs;
}

function markDirty(level) {
    panels.forEach(panel => {
        if (panel.level === level) panel.dirty = true;
    });
}

// --- Charts -------------------------------------------------------------------------------

/**
 * A chart is built the first time its card enters the viewport, and only kept up
 * to date while it stays there. One observer covers every mount; a card leaving
 * the viewport keeps its chart but stops redrawing until it comes back.
 */
function observeCharts() {
    chartObserver = new IntersectionObserver(entries => {
        entries.forEach(entry => {
            const panel = panels.get(entry.target.closest('.pk-insight-panel').dataset.panelId);
            if (!panel) return;
            panel.visible = entry.isIntersecting;
            if (entry.isIntersecting) showChart(panel);
        });
    });

    sizeObserver = new ResizeObserver(entries => {
        entries.forEach(entry => {
            if (entry.contentRect.width === 0) return;   // the tab was just hidden
            const panel = panels.get(entry.target.closest('.pk-insight-panel').dataset.panelId);
            panel?.chart?.setSize(entry.contentRect.width);
        });
    });

    panels.forEach(panel => {
        chartObserver.observe(panel.mount);
        sizeObserver.observe(panel.mount);
    });

    // uPlot bakes the resolved token colors into the canvas, so a theme switch has
    // to rebuild every chart rather than restyle it
    themeObserver = new MutationObserver(() => panels.forEach(rebuildChart));
    themeObserver.observe(document.documentElement, {attributes: true, attributeFilter: ['data-theme']});
}

function showChart(panel) {
    if (!panel.chart) {
        createPanelChart(panel);
    } else if (panel.dirty) {
        redraw(panel);
    }
}

/**
 * Loads what the panel needs (the library, its level) and then builds the chart.
 *
 * `creating` makes a second call bow out while the first is still awaiting, so a
 * rebuild that lands in that window would be silently dropped and the panel left
 * blank - it is this call that has to notice the bumped generation and start the
 * rebuild over. The retry cannot run away: it only happens when a *new* rebuild
 * arrived during this call's own await.
 */
async function createPanelChart(panel) {
    if (panel.chart || panel.creating) return;
    panel.creating = true;
    const generation = panel.generation;
    const level = panel.level;

    let snapshot;
    try {
        await ensureUplot();
        await ensureLevel(level);
        snapshot = levels.get(level);
    } catch (error) {
        panel.creating = false;
        // ensureUplot() drops its cached promise on failure, so the next time this
        // card enters the viewport the script load is attempted again
        console.warn(`Insights panel "${panel.definition.id}" could not be charted:`, error);
        return;
    }
    panel.creating = false;

    if (panel.generation !== generation) {
        if (panel.visible && !panel.chart) createPanelChart(panel);
        return;
    }
    // the card may have scrolled away, or its level may never have loaded
    if (!snapshot || !panel.visible || panel.chart) return;

    try {
        panel.chart = createChart({
            panel: panel.definition, mount: panel.mount, level, snapshot, showPercentiles
        });
        panel.dirty = false;
    } catch (error) {
        console.warn(`Insights panel "${panel.definition.id}" could not be charted:`, error);
    }
}

function destroyChart(panel) {
    panel.chart?.destroy();
    panel.chart = null;
    panel.mount.replaceChildren();
}

function redraw(panel) {
    const snapshot = levels.get(panel.level);
    if (snapshot) panel.chart.setData(snapshot);
    panel.dirty = false;
}

/** Level and theme changes both change chart geometry, so the chart is rebuilt, not updated. */
function rebuildChart(panel) {
    panel.generation++;
    destroyChart(panel);
    panel.dirty = true;
    if (panel.visible) createPanelChart(panel);
}

function setPanelLevel(panel, level) {
    if (panel.level === level) return;
    panel.level = level;
    rebuildChart(panel);
}

// --- Live updates ---------------------------------------------------------------------------

/** Every DOM write caused by one event happens in a single frame. */
function scheduleFlush() {
    if (frame !== null) return;
    frame = requestAnimationFrame(() => {
        frame = null;
        flush();
    });
}

function flush() {
    if (pendingTiles) {
        updateTiles(pendingTiles);
        pendingTiles = null;
    }
    updateReadouts();
    panels.forEach(panel => {
        if (panel.dirty && panel.visible && panel.chart) redraw(panel);
    });
}

function connectStream() {
    source = new EventSource(currentContext.client.basePath + '/api/insights/stream');

    source.addEventListener('tick', event => {
        const tick = JSON.parse(event.data);
        appendTick(tick);
        pendingTiles = tick.tiles ?? {};
        markDirty(0);
        scheduleFlush();
    });

    source.addEventListener('rollup', event => {
        const rollup = JSON.parse(event.data);
        appendRollup(rollup);
        markDirty(rollup.level);
        scheduleFlush();
    });

    // EventSource reconnects on its own, but the deltas missed while it was down
    // leave the mirrored rings out of step - every loaded level is re-snapshotted
    // before the next delta is applied
    source.addEventListener('error', () => {
        resyncPending = true;
    });
    source.addEventListener('open', () => {
        if (resyncPending) {
            resyncPending = false;
            resync();
        }
    });
}

async function resync() {
    for (const level of [...levels.keys()]) {
        // a load that is still in flight already returns post-reconnect data, and
        // a second request for the same path would only cancel it out
        if (levelLoads.has(level)) continue;
        await loadLevel(level);
        markDirty(level);
    }
    scheduleFlush();
}

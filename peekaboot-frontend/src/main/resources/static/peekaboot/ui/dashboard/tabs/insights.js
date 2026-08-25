/**
 * The "Insights" tab: aggregated metric charts (uPlot) with a global
 * aggregation-level switch and SSE-driven live updates. All grouping and
 * ordering comes from /api/insights/config - this module renders it verbatim.
 * The config's stat tiles are rendered by the Overview tab (see overview.js),
 * so the `tiles` payload the tick events carry is ignored here.
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
import {formatMetricValue} from '../../shared/format.js';
import {createChart, ensureUplot} from './insights-chart.js';

export const id = 'insights';
export const label = 'Insights';

const STAT_NAMES = ['min', 'max', 'avg', 'median', 'p90', 'p95', 'p99'];
const EMPTY_PANEL_CLASS = 'pk-insight-panel--empty';
const OVERRIDDEN_PANEL_CLASS = 'pk-insight-panel--overridden';
/** "Reset to global interval" - a counter-clockwise arrow, drawn in the button's own ink. */
const RESET_ICON = `<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor"
        stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/>
</svg>`;

let initialized = false;
let config = null;          // /config response
let currentContext = null;

const levels = new Map();       // level index -> snapshot (see normalizeLevel)
const levelLoads = new Map();   // level index -> in-flight load promise
const panels = new Map();       // panel id -> panel state (see createPanelState)

let source = null;
let resyncPending = false;
let chartObserver = null;
let sizeObserver = null;
let themeObserver = null;
let frame = null;
let showPercentiles = false;
// the level every panel charts at unless pinned to one of its own (see isOverridden)
let globalLevel = 0;
let levelGroup = null;

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
        // only a second init of this same tab can supersede this call now that the
        // Overview tab's tile row carries its own dedupe key; the next refresh
        // cycle retries from scratch
        if (!config) throw new Error('insights config request was superseded');
        globalLevel = config.levels[0].index;
        renderToolbar(container);
        renderPanels(container);
        initPanels(container);
        // the stream is opened before the first snapshot is fetched: the panel
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
    chartObserver = sizeObserver = themeObserver = levelGroup = null;
    if (frame !== null) cancelAnimationFrame(frame);
    frame = null;
    panels.forEach(destroyChart);
    panels.clear();
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

/**
 * The global switch is a radio-like button group rather than a <select>: there are
 * only ever a handful of levels and every one of them is one click away, instead of
 * two plus a scan of a dropdown.
 */
function levelButtonsHtml() {
    return config.levels.map(level => `
        <button type="button" class="pk-btn pk-btn--bucket pk-insight-level" data-level="${level.index}"
                aria-pressed="${level.index === globalLevel}"
        >${escapeHtml(formatInterval(level.intervalMs))}</button>
    `).join('');
}

function renderToolbar(container) {
    const toolbar = container.querySelector('#insights-toolbar');
    toolbar.innerHTML = `
        <div id="insights-level" class="pk-insight-levels" role="group"
             aria-label="Aggregation level">${levelButtonsHtml()}</div>
        <label><input type="checkbox" id="insights-percentiles"> Percentiles</label>
    `;

    levelGroup = toolbar.querySelector('#insights-level');
    levelGroup.addEventListener('click', event => {
        const button = event.target.closest('.pk-insight-level');
        if (button) setGlobalLevel(Number(button.dataset.level));
    });

    toolbar.querySelector('#insights-percentiles').addEventListener('change', event => {
        showPercentiles = event.target.checked;
        panels.forEach(panel => panel.chart?.setPercentiles(showPercentiles));
    });
}

/**
 * Switches every panel that was still following the global level, and leaves the
 * pinned ones (see isOverridden) alone. The previous global level is what identifies
 * a follower, so it has to be read before the new one is stored.
 */
function setGlobalLevel(level) {
    if (globalLevel === level) return;
    const previous = globalLevel;
    globalLevel = level;
    levelGroup.querySelectorAll('.pk-insight-level').forEach(button =>
            button.setAttribute('aria-pressed', String(Number(button.dataset.level) === level)));
    panels.forEach(panel => {
        if (panel.level === previous) selectPanelLevel(panel, level);
        markOverride(panel);
    });
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
                <button type="button" class="pk-btn pk-btn--icon pk-insight-panel-reset hidden"
                        title="Reset to global interval"
                        aria-label="Reset ${escapeHtml(panel.title)} to global interval">${RESET_ICON}</button>
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
        markOverride(panel);

        panel.levelSelect.addEventListener('change', event => {
            setPanelLevel(panel, Number(event.target.value));
            markOverride(panel);
        });
        panel.resetButton.addEventListener('click', () => {
            selectPanelLevel(panel, globalLevel);
            markOverride(panel);
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
        resetButton: element.querySelector('.pk-insight-panel-reset'),
        // a panel-level in the config is an initial override: it already differs
        // from the global level, so the global switch leaves it alone from the start
        level: definition.level ?? globalLevel,
        chart: null,
        creating: false,
        // set while the card shows "no data" instead of a chart (see hasData)
        empty: false,
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

    // A panel whose subsystem is absent - no connection pool, no Hibernate - never
    // receives a datapoint, and axes and a legend drawn over nothing but nulls read
    // as a broken chart. The card says "no data" instead, and is charted after all
    // if the meter turns up later (meters register lazily, on first use).
    if (!hasData(panel)) {
        setEmpty(panel, true);
        return;
    }
    setEmpty(panel, false);

    try {
        panel.chart = createChart({
            panel: panel.definition, mount: panel.mount, level, snapshot, showPercentiles
        });
        panel.dirty = false;
    } catch (error) {
        console.warn(`Insights panel "${panel.definition.id}" could not be charted:`, error);
    }
}

/**
 * Whether any of the panel's series has ever carried a real value. Read from the
 * level-0 store rather than the panel's charted level: level 0 holds every series
 * and grows with every tick, so it answers "does this meter exist at all?" without
 * waiting out the first roll-up of a slower level.
 *
 * A store with no samples at all - an app opened within its first tick - answers
 * yes: nothing is known about any panel yet, and calling them all empty would be
 * a wrong answer where charting them is merely a premature one.
 */
function hasData(panel) {
    const snapshot = levels.get(0);
    if (!snapshot || !snapshot.count) return true;
    return panel.definition.series.some(series => {
        const values = snapshot.series[series.id];
        if (!Array.isArray(values)) return false;
        for (let i = values.length - 1; i >= 0; i--) {
            if (values[i] != null) return true;
        }
        return false;
    });
}

/** Swaps the chart mount between the "no data" message and an empty mount, ready for a chart. */
function setEmpty(panel, empty) {
    panel.empty = empty;
    panel.element.classList.toggle(EMPTY_PANEL_CLASS, empty);
    panel.mount.replaceChildren();
    if (!empty) return;
    const message = document.createElement('div');
    message.className = 'pk-insight-empty';
    message.textContent = 'No data';
    panel.mount.appendChild(message);
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

/** setPanelLevel plus the select that has to show it - the panel did not do the asking. */
function selectPanelLevel(panel, level) {
    panel.levelSelect.value = String(level);
    setPanelLevel(panel, level);
}

/**
 * A panel is pinned exactly while its level differs from the global one - there is no
 * separate flag, so a pinned panel that the global switch catches up with simply falls
 * back in line rather than staying silently pinned to a level it already shows.
 */
function isOverridden(panel) {
    return panel.level !== globalLevel;
}

/** Tints the panel's level select and reveals its reset button while the panel is pinned. */
function markOverride(panel) {
    const overridden = isOverridden(panel);
    panel.element.classList.toggle(OVERRIDDEN_PANEL_CLASS, overridden);
    panel.resetButton.classList.toggle('hidden', !overridden);
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
    updateReadouts();
    panels.forEach(panel => {
        if (panel.chart) {
            if (panel.dirty && panel.visible) redraw(panel);
        } else if (panel.empty && hasData(panel)) {
            setEmpty(panel, false);
            if (panel.visible) createPanelChart(panel);
        }
    });
}

function connectStream() {
    source = new EventSource(currentContext.client.basePath + '/api/insights/stream');

    // the event's `tiles` payload is the Overview tab's business (overview.js),
    // which reads them off /api/insights/config on the 30s cycle instead
    source.addEventListener('tick', event => {
        const tick = JSON.parse(event.data);
        appendTick(tick);
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

/**
 * Verified by hand (stop the app, let the browser's EventSource reconnect, watch
 * every loaded level re-snapshot before the next delta lands): killing and
 * re-establishing the stream on cue is not something the Playwright suite can do
 * deterministically, and a test that only usually reconnects in time is worse than
 * none.
 */
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

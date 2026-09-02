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
import {formatInterval, formatMetricValue} from '../../shared/format.js';
import {reconcileFilterWithUrl} from '../../shared/url-filter.js';
import {createChart, ensureUplot} from './insights-chart.js';
import {normalizeLevel, lastValue, appendTick, appendRollup} from './insights-store.js';

export const id = 'insights';
export const label = 'Insights';

const EMPTY_PANEL_CLASS = 'pk-insight-panel--empty';
/** Every param this tab owns in the URL (see reconcileUrlState / writeUrlParams). */
const URL_KEYS = ['level', 'percentiles', 'restarts', 'panels'];
const OVERRIDDEN_PANEL_CLASS = 'pk-insight-panel--overridden';
/** A counter-clockwise arrow, drawn in the button's own ink - every "undo this" reset control. */
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
let lifecycleEvents = [];
let showMarkers = true;
// the level every panel charts at unless pinned to one of its own (see isOverridden)
let globalLevel = 0;
let levelGroup = null;
let percentilesCheckbox = null;
let markersCheckbox = null;
// the x-axis window a drag-select zoom pinned every chart to, in uPlot's time scale
// (epoch seconds) - null while every chart is auto-fitting its own data as usual
let zoomWindow = null;
let zoomResetButton = null;

export function isAvailable(data, features) {
    return Boolean(features?.insights);
}

export function render(container, data, context) {
    currentContext = context;
    if (initialized) {
        // SSE keeps this tab live; the 30s cycle must not rebuild it. Only the
        // URL-owned state is reconciled (active-tab guard, see main.js's renderTab).
        if (config && context.active) reconcileUrlState(context);
        return;
    }
    if (!context.active) return;
    initialized = true;
    init(container, context);
}

/**
 * The URL's level param as a configured level index, or `fallback` for anything else -
 * a stale link, a typo, a level from an older config. Exported for the browser tests.
 */
export function levelFromUrl(params, configuredLevels, fallback) {
    const level = Number(params?.level);
    return configuredLevels.some(configured => configured.index === level) ? level : fallback;
}

/**
 * The URL's panels param ("<id>:<level>[,...]") as {panel id -> level index}. Only pairs
 * naming a configured panel at a configured level survive - a stale panel id, an
 * unconfigured level or hand-mangled syntax is dropped, so that panel simply follows the
 * global level again. Exported for the browser tests.
 */
export function panelOverridesFromUrl(params, {panels, levels}) {
    const overrides = {};
    for (const pair of (params?.panels ?? '').split(',')) {
        const [id, levelText, ...excess] = pair.split(':');
        const level = Number(levelText);
        if (excess.length || !/^\d+$/.test(levelText ?? '')
                || !panels.some(panel => panel.id === id)
                || !levels.some(configured => configured.index === level)) continue;
        overrides[id] = level;
    }
    return overrides;
}

/**
 * Reconciles everything this tab keeps in the URL - the global aggregation level, the
 * percentiles/restarts checkboxes and the per-panel level overrides - with the same
 * two-direction rule every filter tab applies (see shared/url-filter.js's doc comment).
 * Seeding ends with a write-back, so a bogus or non-canonical value is corrected in the
 * URL to the state that actually restored.
 */
function reconcileUrlState(context) {
    reconcileFilterWithUrl(context, URL_KEYS, {
        seed: params => {
            seedFromUrl(params);
            writeUrlParams();
        },
        hasNonDefaultState: () => globalLevel !== config.levels[0].index
                || showPercentiles || !showMarkers
                || [...panels.values()].some(isOverridden),
        writeBack: writeUrlParams
    });
}

/** Restores every URL-owned piece of state from the given params, missing ones to their defaults. */
function seedFromUrl(params) {
    setGlobalLevel(levelFromUrl(params, config.levels, config.levels[0].index));
    setShowPercentiles(params.percentiles === '1');
    setShowMarkers(params.restarts !== '0');
    const overrides = panelOverridesFromUrl(params, config);
    panels.forEach(panel => {
        selectPanelLevel(panel, overrides[panel.definition.id] ?? globalLevel);
        markOverride(panel);
    });
}

/**
 * Writes this tab's URL params, omitting every default - the first configured level,
 * percentiles off, restarts on, panels following the global level - so a clean state
 * yields a clean "#insights" hash. A replace, never a push - see url-state.js.
 */
function writeUrlParams() {
    const params = {};
    if (globalLevel !== config.levels[0].index) params.level = String(globalLevel);
    if (showPercentiles) params.percentiles = '1';
    if (!showMarkers) params.restarts = '0';
    const overridden = [...panels.values()].filter(isOverridden)
            .map(panel => `${panel.definition.id}:${panel.level}`).join(',');
    if (overridden) params.panels = overridden;
    currentContext.setUrlParams(params);
}

async function init(container, context) {
    try {
        config = await context.client.get('/api/insights/config');
        // only a second init of this same tab can supersede this call (the Overview
        // tab's tile row carries its own dedupe key); the next refresh cycle retries
        // from scratch
        if (!config) throw new Error('insights config request was superseded');
        // a deep link may name the state to start in (each value validated - see
        // levelFromUrl / panelOverridesFromUrl; anything bogus falls back to the default)
        globalLevel = levelFromUrl(context.urlParams, config.levels, config.levels[0].index);
        showPercentiles = context.urlParams.percentiles === '1';
        showMarkers = context.urlParams.restarts !== '0';
        renderToolbar(container);
        renderPanels(container);
        initPanels(container, panelOverridesFromUrl(context.urlParams, config));
        // correct a bogus or non-canonical deep link to the state that actually restored
        if (URL_KEYS.some(key => key in context.urlParams)) writeUrlParams();
        // the stream is opened before the first snapshot is fetched: the panel
        // readouts then go live with the next tick instead of waiting on a
        // request that carries every series' whole ring. A tick arriving before
        // the snapshot lands has nowhere to go and is dropped - the snapshot it
        // is waiting for already contains that sample.
        connectStream();
        observeCharts();
        await loadLifecycleEvents();
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
    chartObserver = sizeObserver = themeObserver = levelGroup = zoomResetButton = null;
    percentilesCheckbox = markersCheckbox = null;
    zoomWindow = null;
    if (frame !== null) cancelAnimationFrame(frame);
    frame = null;
    panels.forEach(destroyChart);
    panels.clear();
    levels.clear();
    levelLoads.clear();
    lifecycleEvents = [];
    showPercentiles = false;
    showMarkers = true;
}

// --- Lifecycle events -------------------------------------------------------------------

/**
 * The application's own start/stop history. Absent (lifecycle disabled, or an older
 * backend) simply means no markers - never a failed tab.
 */
async function loadLifecycleEvents() {
    try {
        const body = await currentContext.client.get('/api/lifecycle/events');
        lifecycleEvents = body?.events ?? [];
    } catch (error) {
        console.warn('Insights: restart markers unavailable:', error);
        lifecycleEvents = [];
    }
}

// --- Toolbar ----------------------------------------------------------------------------

/**
 * A radio-like button group rather than a <select>: there are only ever a handful of
 * levels and every one of them is one click away, instead of two plus a scan of a
 * dropdown. Shared by the toolbar's own switch and every panel's - `buttonClass` is
 * what tells them apart visually (the toolbar's carries more weight; a panel's sits in
 * a card header and stays subtler).
 */
function levelButtonsHtml(activeLevel, buttonClass) {
    return config.levels.map(level => `
        <button type="button" class="pk-btn ${buttonClass} pk-insight-level" data-level="${level.index}"
                aria-pressed="${level.index === activeLevel}"
        >${escapeHtml(formatInterval(level.intervalMs))}</button>
    `).join('');
}

function updateLevelButtons(group, activeLevel) {
    group.querySelectorAll('.pk-insight-level').forEach(button =>
            button.setAttribute('aria-pressed', String(Number(button.dataset.level) === activeLevel)));
}

function renderToolbar(container) {
    const toolbar = container.querySelector('#insights-toolbar');
    // the zoom-reset button sits last: it is the only toolbar control that toggles
    // hidden/shown at runtime, and trailing keeps that from shifting anything else
    toolbar.innerHTML = `
        <div id="insights-level" class="pk-insight-levels" role="group"
             aria-label="Aggregation level">${levelButtonsHtml(globalLevel, 'pk-btn--bucket')}</div>
        <label><input type="checkbox" id="insights-percentiles"${showPercentiles ? ' checked' : ''}> Percentiles</label>
        <label><input type="checkbox" id="insights-markers"${showMarkers ? ' checked' : ''}> Restarts</label>
        <button type="button" id="insights-zoom-reset" class="pk-btn pk-btn--icon hidden"
                title="Reset zoom" aria-label="Reset zoom">${RESET_ICON}</button>
    `;

    levelGroup = toolbar.querySelector('#insights-level');
    levelGroup.addEventListener('click', event => {
        const button = event.target.closest('.pk-insight-level');
        if (!button) return;
        setGlobalLevel(Number(button.dataset.level));
        writeUrlParams();
    });

    zoomResetButton = toolbar.querySelector('#insights-zoom-reset');
    zoomResetButton.addEventListener('click', handleZoomReset);

    percentilesCheckbox = toolbar.querySelector('#insights-percentiles');
    percentilesCheckbox.addEventListener('change', event => {
        setShowPercentiles(event.target.checked);
        writeUrlParams();
    });

    markersCheckbox = toolbar.querySelector('#insights-markers');
    markersCheckbox.addEventListener('change', event => {
        setShowMarkers(event.target.checked);
        writeUrlParams();
    });
}

function setShowPercentiles(show) {
    if (showPercentiles === show) return;
    showPercentiles = show;
    percentilesCheckbox.checked = show;
    panels.forEach(panel => panel.chart?.setPercentiles(show));
}

function setShowMarkers(show) {
    if (showMarkers === show) return;
    showMarkers = show;
    markersCheckbox.checked = show;
    panels.forEach(panel => panel.chart?.setMarkers(show));
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
    updateLevelButtons(levelGroup, level);
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
                <div class="pk-insight-levels pk-insight-panel-levels" role="group"
                     aria-label="${escapeHtml(panel.title)} aggregation level"
                >${levelButtonsHtml(panel.level ?? globalLevel, 'pk-btn--small')}</div>
                <button type="button" class="pk-btn pk-btn--icon pk-insight-panel-reset hidden"
                        title="Reset to global interval"
                        aria-label="Reset ${escapeHtml(panel.title)} to global interval">${RESET_ICON}</button>
            </div>
            <div class="pk-insight-chart"></div>
        </div>
    `).join('');
}

function initPanels(container, urlOverrides) {
    container.querySelectorAll('#insights-panels .pk-insight-panel').forEach(element => {
        const definition = config.panels.find(panel => panel.id === element.dataset.panelId);
        const panel = createPanelState(definition, element, urlOverrides[definition.id]);
        panels.set(definition.id, panel);

        updateLevelButtons(panel.levelGroup, panel.level);
        markOverride(panel);

        panel.levelGroup.addEventListener('click', event => {
            const button = event.target.closest('.pk-insight-level');
            if (!button) return;
            selectPanelLevel(panel, Number(button.dataset.level));
            markOverride(panel);
            writeUrlParams();
        });
        panel.resetButton.addEventListener('click', () => {
            selectPanelLevel(panel, globalLevel);
            markOverride(panel);
            writeUrlParams();
        });
    });
}

function createPanelState(definition, element, urlLevel) {
    return {
        definition,
        element,
        mount: element.querySelector('.pk-insight-chart'),
        readout: element.querySelector('.pk-insight-current'),
        levelGroup: element.querySelector('.pk-insight-panel-levels'),
        resetButton: element.querySelector('.pk-insight-panel-reset'),
        // a deep-linked override wins over a panel-level in the config; either one is
        // an initial override, already differing from the global level, so the global
        // switch leaves it alone from the start
        level: urlLevel ?? definition.level ?? globalLevel,
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
    return lastValue(levels.get(0)?.series[panel.definition.series[0].id]);
}

function updateReadouts() {
    panels.forEach(panel => {
        const first = panel.definition.series[0];
        const unit = first.unit || panel.definition.unit;
        updateText(panel.readout, formatMetricValue(currentValue(panel), unit));
    });
}

// --- Level data store ---------------------------------------------------------------------

/** One dedupe key per level, so a level-1 load cannot cancel an in-flight level-0 load (see shared/api.js). */
async function loadLevel(level) {
    const body = await currentContext.client.get('/api/insights/data', {params: {level}, dedupeKey: `insights-data-${level}`});
    if (body) levels.set(level, normalizeLevel(body, config.levels.find(configured => configured.index === level)?.size));
}

/** Loads a level at most once; concurrent callers share the in-flight request. */
function ensureLevel(level) {
    if (levels.has(level)) return Promise.resolve();
    if (!levelLoads.has(level)) {
        levelLoads.set(level, loadLevel(level).finally(() => levelLoads.delete(level)));
    }
    return levelLoads.get(level);
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
            panel: panel.definition, mount: panel.mount, level, snapshot, showPercentiles,
            events: lifecycleEvents, showMarkers,
            dateOptions: () => ({locale: currentContext.locale, timeZone: currentContext.timeZone}),
            onZoom: handleZoom, onZoomReset: handleZoomReset
        });
        // a chart built while a zoom is already active (first scroll into view, or a
        // level/theme rebuild) starts life as a brand-new uPlot instance and has to be
        // brought back in line with what every other chart is already showing
        if (zoomWindow) panel.chart.setXScale(zoomWindow.min, zoomWindow.max);
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
    return panel.definition.series.some(series => lastValue(snapshot.series[series.id]) != null);
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
    // resetScales=false while zoomed holds the current window and skips uPlot's
    // repaint entirely (see insights-chart.js's setData) rather than snapping a
    // manually zoomed chart back to auto-fit on every live tick/rollup - the canvas
    // catches up in one repaint once the zoom is reset (see handleZoomReset)
    if (snapshot) panel.chart.setData(snapshot, !zoomWindow);
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

/** setPanelLevel plus the button group that has to show it - the panel did not do the asking. */
function selectPanelLevel(panel, level) {
    updateLevelButtons(panel.levelGroup, level);
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

/** Highlights the panel's own level group and reveals its reset button while the panel is pinned. */
function markOverride(panel) {
    const overridden = isOverridden(panel);
    panel.element.classList.toggle(OVERRIDDEN_PANEL_CLASS, overridden);
    panel.resetButton.classList.toggle('hidden', !overridden);
}

// --- Zoom -------------------------------------------------------------------------------

/**
 * A drag-select on any one chart's x-axis (see insights-chart.js's setSelect hook)
 * pins every chart - whatever level it charts at - to the same absolute epoch window;
 * uPlot's x scale is seconds-since-epoch on every chart regardless of level, so the
 * same {min, max} pair applies unchanged everywhere. What each chart actually landed
 * on is read back independently via its own setScale hook (data-zoom-min/-max on the
 * panel's element, see insights-chart.js) rather than trusted from the call here.
 */
function handleZoom(min, max) {
    zoomWindow = {min, max};
    panels.forEach(panel => panel.chart?.setXScale(min, max));
    updateZoomResetVisibility();
}

/**
 * Restores every chart to auto-fitting its own data, live-following new ticks again.
 * Wired to the toolbar's reset control and to every chart's own double-click (see
 * insights-chart.js) - either one un-zooms all of them, not just the chart clicked.
 */
function handleZoomReset() {
    if (!zoomWindow) return;
    zoomWindow = null;
    panels.forEach(panel => panel.chart?.resetXScale());
    updateZoomResetVisibility();
}

function updateZoomResetVisibility() {
    zoomResetButton.classList.toggle('hidden', !zoomWindow);
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
        appendTick(levels.get(0), tick);
        markDirty(0);
        scheduleFlush();
    });

    source.addEventListener('rollup', event => {
        const rollup = JSON.parse(event.data);
        appendRollup(levels.get(rollup.level), rollup);
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
        if (!resyncPending) return;
        resyncPending = false;
        // the application may still be coming up - a failed snapshot is retried on the
        // next reconnect rather than leaving the mirrored rings silently out of step
        resync().catch(error => {
            console.warn('Insights: resync after reconnect failed, retrying on the next reconnect:', error);
            resyncPending = true;
        });
    });
}

/** Re-snapshots every loaded level after a reconnect. */
async function resync() {
    // an application that restarted under an open dashboard has a new event to
    // draw, fetched before the level loop so every chart is updated in one pass
    await loadLifecycleEvents();
    for (const level of [...levels.keys()]) {
        // a load that is still in flight already returns post-reconnect data, and
        // a second request for the same path would only cancel it out
        if (levelLoads.has(level)) continue;
        await loadLevel(level);
        markDirty(level);
    }
    panels.forEach(panel => panel.chart?.setEvents(lifecycleEvents));
    scheduleFlush();
}

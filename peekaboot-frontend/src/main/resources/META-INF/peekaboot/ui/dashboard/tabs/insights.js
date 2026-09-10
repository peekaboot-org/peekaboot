/**
 * The "Insights" tab: aggregated metric charts (uPlot) with a global
 * aggregation-level switch and SSE-driven live updates. All grouping and
 * ordering comes from /api/insights/config - this module renders it verbatim.
 * The tick events carry series values only; the config's stat tiles are the Overview
 * tab's business (see overview.js), which reads them off /api/insights/config.
 *
 * This module is the tab contract, the toolbar and the URL glue. The two halves with a
 * life of their own sit beside it, created once per init() and released by teardown():
 *   - insights-stream.js: the client-side mirror of the server's ring buffers and the
 *     EventSource that keeps it current instead of polling;
 *   - insights-panels.js: the panel cards and their chart lifecycle, where a chart only
 *     exists once its card has been scrolled into view.
 * render() is called again on every 30s dashboard refresh and must not rebuild any of
 * this.
 */
import {reconcileFilterWithUrl} from '../../shared/url-filter.js';
import {createInsightsStream} from './insights-stream.js';
import {createInsightsPanels, levelButtonsHtml, updateLevelButtons, RESET_ICON} from './insights-panels.js';

export const id = 'insights';

/** Every param this tab owns in the URL (see reconcileUrlState / writeUrlParams). */
const URL_KEYS = ['level', 'percentiles', 'restarts', 'panels'];

let initialized = false;
let config = null;          // /config response
let currentContext = null;
let stream = null;
let panels = null;
let toolbar = null;
let levelGroup = null;
let percentilesCheckbox = null;
let markersCheckbox = null;
let zoomResetButton = null;
// shown while the browser has given up on the stream (see insights-stream.js)
let streamStoppedNote = null;

export function isAvailable(data, features) {
    return Boolean(features?.insights);
}

export function render(container, data, context) {
    currentContext = context;
    if (initialized) {
        // SSE keeps this tab live; the 30s cycle must not rebuild it. Only the
        // URL-owned state is reconciled (active-tab guard, see main.js's renderTab).
        if (config && context.active) reconcileUrlState(context);
        // ...and a stream the browser gave up on is given another go each cycle - the
        // endpoint may be back, and nothing else would ever try again
        stream?.reconnectIfClosed();
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
export function panelOverridesFromUrl(params, {panels: configured, levels}) {
    const overrides = {};
    for (const pair of (params?.panels ?? '').split(',')) {
        const [panelId, levelText, ...excess] = pair.split(':');
        const level = Number(levelText);
        if (excess.length || !/^\d+$/.test(levelText ?? '')
                || !configured.some(panel => panel.id === panelId)
                || !levels.some(entry => entry.index === level)) continue;
        overrides[panelId] = level;
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
        hasNonDefaultState: () => panels.globalLevel() !== config.levels[0].index
                || panels.percentiles() || !panels.markers()
                || Object.keys(panels.overrides()).length > 0,
        writeBack: writeUrlParams
    });
}

/** Restores every URL-owned piece of state from the given params, missing ones to their defaults. */
function seedFromUrl(params) {
    setGlobalLevel(levelFromUrl(params, config.levels, config.levels[0].index));
    setShowPercentiles(params.percentiles === '1');
    setShowMarkers(params.restarts !== '0');
    panels.applyOverrides(panelOverridesFromUrl(params, config));
}

/**
 * Writes this tab's URL params, omitting every default - the first configured level,
 * percentiles off, restarts on, panels following the global level - so a clean state
 * yields a clean "#insights" hash. A replace, never a push - see url-state.js.
 */
function writeUrlParams() {
    const params = {};
    if (panels.globalLevel() !== config.levels[0].index) params.level = String(panels.globalLevel());
    if (panels.percentiles()) params.percentiles = '1';
    if (!panels.markers()) params.restarts = '0';
    const overridden = Object.entries(panels.overrides()).map(([panelId, level]) => `${panelId}:${level}`).join(',');
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
        stream = createInsightsStream({
            client: context.client,
            sizeOf: level => config.levels.find(configured => configured.index === level)?.size,
            onDirty: level => panels.markDirty(level),
            onResynced: () => panels.setEvents(stream.events()),
            onClosed: () => streamStoppedNote.classList.remove('hidden'),
            onOpen: () => streamStoppedNote.classList.add('hidden')
        });
        // a deep link may name the state to start in (each value validated - see
        // levelFromUrl / panelOverridesFromUrl; anything bogus falls back to the default)
        panels = createInsightsPanels({
            container,
            config,
            stream,
            initial: {
                globalLevel: levelFromUrl(context.urlParams, config.levels, config.levels[0].index),
                showPercentiles: context.urlParams.percentiles === '1',
                showMarkers: context.urlParams.restarts !== '0',
                overrides: panelOverridesFromUrl(context.urlParams, config)
            },
            // read at hover time, so a timezone toggle reaches a chart that is not rebuilt for it
            dateOptions: () => ({locale: currentContext.locale, timeZone: currentContext.timeZone}),
            onPanelLevelChange: writeUrlParams,
            onZoomChange: zoomed => zoomResetButton.classList.toggle('hidden', !zoomed)
        });
        renderToolbar(container);
        // correct a bogus or non-canonical deep link to the state that actually restored
        if (URL_KEYS.some(key => key in context.urlParams)) writeUrlParams();
        // the stream is opened before the first snapshot is fetched: the panel
        // readouts then go live with the next tick instead of waiting on a
        // request that carries every series' whole ring. A tick arriving before
        // the snapshot lands has nowhere to go and is dropped - the snapshot it
        // is waiting for already contains that sample.
        stream.connect();
        await stream.loadLifecycleEvents();
        // level 0 backs the panel readouts and is where every tick lands, so it
        // is loaded up front rather than on a panel's demand; the charts coming
        // into view share this one request
        await stream.ensureLevel(0);
    } catch (error) {
        console.warn('Insights tab failed to initialise:', error);
        teardown();
        initialized = false;   // a later refresh cycle retries from scratch
    }
}

/** Releases everything init() may have wired up; safe to call at any point of init. */
function teardown() {
    stream?.close();
    panels?.destroy();
    // the toolbar's four listeners read `panels`, so its markup goes with them rather than
    // staying clickable against torn-down state until the next render
    toolbar?.replaceChildren();
    stream = panels = toolbar = null;
    levelGroup = percentilesCheckbox = markersCheckbox = zoomResetButton = streamStoppedNote = null;
}

// --- Toolbar ----------------------------------------------------------------------------

function renderToolbar(container) {
    toolbar = container.querySelector('#insights-toolbar');
    // the zoom-reset button sits last: it is the only toolbar control that toggles
    // hidden/shown at runtime, and trailing keeps that from shifting anything else
    toolbar.innerHTML = `
        <div id="insights-level" class="pk-insight-levels" role="group"
             aria-label="Aggregation level">${levelButtonsHtml(config.levels, panels.globalLevel(), 'pk-btn--bucket')}</div>
        <label><input type="checkbox" id="insights-percentiles"${panels.percentiles() ? ' checked' : ''}> Percentiles</label>
        <label><input type="checkbox" id="insights-markers"${panels.markers() ? ' checked' : ''}> Restarts</label>
        <button type="button" id="insights-zoom-reset" class="pk-btn pk-btn--icon hidden"
                title="Reset zoom" aria-label="Reset zoom">${RESET_ICON}</button>
        <span id="insights-stream-stopped" class="pk-insight-stream-note hidden" role="status">Live updates stopped</span>
    `;
    streamStoppedNote = toolbar.querySelector('#insights-stream-stopped');

    levelGroup = toolbar.querySelector('#insights-level');
    levelGroup.addEventListener('click', event => {
        const button = event.target.closest('.pk-insight-level');
        if (!button) return;
        setGlobalLevel(Number(button.dataset.level));
        writeUrlParams();
    });

    zoomResetButton = toolbar.querySelector('#insights-zoom-reset');
    zoomResetButton.addEventListener('click', () => panels.resetZoom());

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

/** The panels' setting plus the toolbar control that has to show it - a seed from the URL did not click it. */
function setGlobalLevel(level) {
    panels.setGlobalLevel(level);
    updateLevelButtons(levelGroup, level);
}

function setShowPercentiles(show) {
    panels.setPercentiles(show);
    percentilesCheckbox.checked = show;
}

function setShowMarkers(show) {
    panels.setMarkers(show);
    markersCheckbox.checked = show;
}

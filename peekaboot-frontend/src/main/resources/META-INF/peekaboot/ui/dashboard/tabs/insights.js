/**
 * The "Insights" tab: aggregated metric charts (uPlot) with a global
 * aggregation-level switch and SSE-driven live updates. All grouping and
 * ordering comes from /api/insights/config - this module renders it verbatim.
 * The tick events carry series values only; the config's stat tiles are the Overview
 * tab's business (see overview.js), which reads them off /api/insights/config.
 *
 * This module is the tab instance, the toolbar and the URL glue. The two halves with a
 * life of their own sit beside it, created once per init() and released by teardown():
 *   - insights-stream.js: the client-side mirror of the server's ring buffers and the
 *     EventSource that keeps it current instead of polling;
 *   - insights-panels.js: the panel cards and their chart lifecycle, where a chart only
 *     exists once its card has been scrolled into view.
 * update() is called on every 30s dashboard refresh while the tab is showing and must not
 * rebuild any of this. hide() closes the stream and show() reopens it, so a tab nobody is
 * looking at holds no connection.
 */
import {urlState} from '../../shared/url-filter.js';
import {createInsightsStream} from './insights-stream.js';
import {createInsightsPanels, levelButtonsHtml, updateLevelButtons, RESET_ICON} from './insights-panels.js';

export const id = 'insights';

/** Every param this tab owns in the URL (see viewState). */
const URL_KEYS = ['level', 'percentiles', 'restarts', 'panels'];

export function isAvailable(data, features) {
    return Boolean(features?.insights);
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

export function create(container) {
    let initialized = false;
    let showing = false;
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

    function show(data, context) {
        showing = true;
        // paused by hide(), or never opened by an init that finished behind another tab
        stream?.resume();
        return update(data, context);
    }

    function hide() {
        showing = false;
        stream?.pause();
    }

    function update(data, context) {
        currentContext = context;
        if (initialized) {
            // SSE keeps this tab live; the 30s cycle must not rebuild it. Only the
            // URL-owned state is reconciled.
            if (config) viewState.reconcile(context);
            // ...and a stream the browser gave up on is given another go each cycle - the
            // endpoint may be back, and nothing else would ever try again
            stream?.reconnectIfClosed();
            return;
        }
        initialized = true;
        return init(context);
    }

    /**
     * Everything this tab keeps in the URL - the global aggregation level, the
     * percentiles/restarts checkboxes and the per-panel level overrides - as URL state (see
     * shared/url-filter.js). Only usable once the config has arrived and the panels exist.
     */
    const viewState = urlState({
        keys: URL_KEYS,
        read: stateFromUrl,
        apply: applyState,
        params: stateParams
    });

    /**
     * Every URL-owned piece of state as the given params name it, each validated (see
     * levelFromUrl / panelOverridesFromUrl), missing or bogus ones at their defaults.
     */
    function stateFromUrl(params) {
        return {
            globalLevel: levelFromUrl(params, config.levels, config.levels[0].index),
            showPercentiles: params.percentiles === '1',
            showMarkers: params.restarts !== '0',
            overrides: panelOverridesFromUrl(params, config)
        };
    }

    /** Makes the given state the current one, on the panels and on the toolbar. */
    function applyState(state) {
        setGlobalLevel(state.globalLevel);
        setShowPercentiles(state.showPercentiles);
        setShowMarkers(state.showMarkers);
        panels.applyOverrides(state.overrides);
    }

    /**
     * The current state as URL params, omitting every default - the first configured level,
     * percentiles off, restarts on, panels following the global level - so a clean state
     * yields a clean "#insights" hash.
     */
    function stateParams() {
        const params = {};
        if (panels.globalLevel() !== config.levels[0].index) params.level = String(panels.globalLevel());
        if (panels.percentiles()) params.percentiles = '1';
        if (!panels.markers()) params.restarts = '0';
        const overridden = Object.entries(panels.overrides()).map(([panelId, level]) => `${panelId}:${level}`).join(',');
        if (overridden) params.panels = overridden;
        return params;
    }

    /** Called by every control that changes the state. */
    function saveViewState() {
        viewState.save(currentContext);
    }

    async function init(context) {
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
            // a deep link may name the state to start in
            panels = createInsightsPanels({
                container,
                config,
                stream,
                initial: stateFromUrl(context.urlParams),
                // read at hover time, so a timezone toggle reaches a chart that is not rebuilt for it
                dateOptions: () => ({locale: currentContext.locale, timeZone: currentContext.timeZone}),
                onPanelLevelChange: saveViewState,
                onZoomChange: zoomed => zoomResetButton.classList.toggle('hidden', !zoomed)
            });
            renderToolbar();
            // correct a bogus or non-canonical deep link to the state that actually restored
            if (URL_KEYS.some(key => key in context.urlParams)) saveViewState();
            // the stream is opened before the first snapshot is fetched: the panel
            // readouts then go live with the next tick instead of waiting on a
            // request that carries every series' whole ring. A tick arriving before
            // the snapshot lands is replayed onto it, since the server may have served
            // the snapshot before that sample (see insights-stream.js). A reader who left
            // while the config was on its way gets the stream from the next show().
            if (showing) stream.connect();
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
        stream?.pause();
        panels?.destroy();
        // the toolbar's four listeners read `panels`, so its markup goes with them rather than
        // staying clickable against torn-down state until the next update
        toolbar?.replaceChildren();
        stream = panels = toolbar = null;
        levelGroup = percentilesCheckbox = markersCheckbox = zoomResetButton = streamStoppedNote = null;
    }

    // --- Toolbar ------------------------------------------------------------------------

    function renderToolbar() {
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
            saveViewState();
        });

        zoomResetButton = toolbar.querySelector('#insights-zoom-reset');
        zoomResetButton.addEventListener('click', () => panels.resetZoom());

        percentilesCheckbox = toolbar.querySelector('#insights-percentiles');
        percentilesCheckbox.addEventListener('change', event => {
            setShowPercentiles(event.target.checked);
            saveViewState();
        });

        markersCheckbox = toolbar.querySelector('#insights-markers');
        markersCheckbox.addEventListener('change', event => {
            setShowMarkers(event.target.checked);
            saveViewState();
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

    return {show, update, hide};
}

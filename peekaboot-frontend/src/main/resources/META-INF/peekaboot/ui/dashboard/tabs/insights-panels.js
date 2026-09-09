/**
 * The Insights tab's panel cards: their markup, the per-panel chart lifecycle and the
 * display settings the charts follow. A chart only exists once its card has been scrolled
 * into view (see the IntersectionObserver in observe()); a hidden panel keeps collecting
 * data through the stream's mirror and redraws once, on re-entry. Every panel charts at
 * the global level unless pinned to one of its own (see isOverridden).
 *
 *   stream               -> insights-stream.js's mirror, the source of every snapshot
 *   dateOptions()        -> the dashboard's current {locale, timeZone}, read at hover time
 *   onPanelLevelChange() -> a panel's own level switch or reset was clicked
 *   onZoomChange(zoomed) -> a drag-select zoom was applied or lifted
 */
import {escapeHtml} from '../../shared/markup.js';
import {formatInterval, formatMetricValue} from '../../shared/format.js';
import {lastValue} from './insights-store.js';
import {createChart, ensureUplot} from './insights-chart.js';

const EMPTY_PANEL_CLASS = 'pk-insight-panel--empty';
const OVERRIDDEN_PANEL_CLASS = 'pk-insight-panel--overridden';
/** A counter-clockwise arrow, drawn in the button's own ink - every "undo this" reset control. */
export const RESET_ICON = `<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor"
        stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/>
</svg>`;

/**
 * A radio-like button group rather than a <select>: there are only ever a handful of
 * levels and every one of them is one click away, instead of two plus a scan of a
 * dropdown. Shared by the toolbar's own switch and every panel's - `buttonClass` is
 * what tells them apart visually (the toolbar's carries more weight; a panel's sits in
 * a card header and stays subtler).
 */
export function levelButtonsHtml(levels, activeLevel, buttonClass) {
    return levels.map(level => `
        <button type="button" class="pk-btn ${buttonClass} pk-insight-level" data-level="${level.index}"
                aria-pressed="${level.index === activeLevel}"
        >${escapeHtml(formatInterval(level.intervalMs))}</button>
    `).join('');
}

export function updateLevelButtons(group, activeLevel) {
    group.querySelectorAll('.pk-insight-level').forEach(button =>
            button.setAttribute('aria-pressed', String(Number(button.dataset.level) === activeLevel)));
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

export function createInsightsPanels({
    container, config, stream, initial, dateOptions, onPanelLevelChange, onZoomChange
}) {
    const panels = new Map();       // panel id -> panel state (see createPanelState)
    let globalLevel = initial.globalLevel;
    let showPercentiles = initial.showPercentiles;
    let showMarkers = initial.showMarkers;
    // the x-axis window a drag-select zoom pinned every chart to, in uPlot's time scale
    // (epoch seconds) - null while every chart is auto-fitting its own data as usual
    let zoomWindow = null;
    let chartObserver = null;
    let sizeObserver = null;
    let themeObserver = null;
    let frame = null;

    // --- Markup ---------------------------------------------------------------------------

    function renderPanels() {
        const panelsEl = container.querySelector('#insights-panels');
        panelsEl.innerHTML = config.panels.map(panel => `
            <div class="pk-insight-panel" data-panel-id="${escapeHtml(panel.id)}">
                <div class="pk-insight-panel__header">
                    <h3 class="pk-insight-panel__title">${escapeHtml(panel.title)}</h3>
                    <span class="pk-insight-current"></span>
                    <div class="pk-insight-levels pk-insight-panel-levels" role="group"
                         aria-label="${escapeHtml(panel.title)} aggregation level"
                    >${levelButtonsHtml(config.levels, panel.level ?? globalLevel, 'pk-btn--small')}</div>
                    <button type="button" class="pk-btn pk-btn--icon pk-insight-panel-reset hidden"
                            title="Reset to global interval"
                            aria-label="Reset ${escapeHtml(panel.title)} to global interval">${RESET_ICON}</button>
                </div>
                <div class="pk-insight-chart"></div>
            </div>
        `).join('');
    }

    function initPanels(urlOverrides) {
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
                onPanelLevelChange();
            });
            panel.resetButton.addEventListener('click', () => {
                selectPanelLevel(panel, globalLevel);
                markOverride(panel);
                onPanelLevelChange();
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

    // --- Readouts -------------------------------------------------------------------------

    /** Latest non-null raw value of the panel's first series, whatever level it charts. */
    function currentValue(panel) {
        return lastValue(stream.level(0)?.series[panel.definition.series[0].id]);
    }

    function updateReadouts() {
        panels.forEach(panel => {
            const first = panel.definition.series[0];
            const unit = first.unit || panel.definition.unit;
            updateText(panel.readout, formatMetricValue(currentValue(panel), unit));
        });
    }

    // --- Charts ---------------------------------------------------------------------------

    /**
     * A chart is built the first time its card enters the viewport, and only kept up
     * to date while it stays there. One observer covers every mount; a card leaving
     * the viewport keeps its chart but stops redrawing until it comes back.
     */
    function observe() {
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
            await stream.ensureLevel(level);
            snapshot = stream.level(level);
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
                events: stream.events(), showMarkers, dateOptions,
                onZoom: zoomTo, onZoomReset: resetZoom
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
        const snapshot = stream.level(0);
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
        const snapshot = stream.level(panel.level);
        // resetScales=false while zoomed holds the current window and skips uPlot's
        // repaint entirely (see insights-chart.js's setData) rather than snapping a
        // manually zoomed chart back to auto-fit on every live tick/rollup - the canvas
        // catches up in one repaint once the zoom is reset (see resetZoom)
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

    // --- Levels ---------------------------------------------------------------------------

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

    /**
     * Switches every panel that was still following the global level, and leaves the
     * pinned ones (see isOverridden) alone. The previous global level is what identifies
     * a follower, so it has to be read before the new one is stored.
     */
    function setGlobalLevel(level) {
        if (globalLevel === level) return;
        const previous = globalLevel;
        globalLevel = level;
        panels.forEach(panel => {
            if (panel.level === previous) selectPanelLevel(panel, level);
            markOverride(panel);
        });
    }

    /** Every panel to its override, or back to the global level - the seed from a URL. */
    function applyOverrides(overrides) {
        panels.forEach(panel => {
            selectPanelLevel(panel, overrides[panel.definition.id] ?? globalLevel);
            markOverride(panel);
        });
    }

    /** The pinned panels' levels, keyed by panel id - what the URL's panels param carries. */
    function overrides() {
        const result = {};
        panels.forEach(panel => {
            if (isOverridden(panel)) result[panel.definition.id] = panel.level;
        });
        return result;
    }

    // --- Display settings -----------------------------------------------------------------

    function setPercentiles(show) {
        if (showPercentiles === show) return;
        showPercentiles = show;
        panels.forEach(panel => panel.chart?.setPercentiles(show));
    }

    function setMarkers(show) {
        if (showMarkers === show) return;
        showMarkers = show;
        panels.forEach(panel => panel.chart?.setMarkers(show));
    }

    // --- Zoom -----------------------------------------------------------------------------

    /**
     * A drag-select on any one chart's x-axis (see insights-chart.js's setSelect hook)
     * pins every chart - whatever level it charts at - to the same absolute epoch window;
     * uPlot's x scale is seconds-since-epoch on every chart regardless of level, so the
     * same {min, max} pair applies unchanged everywhere. What each chart actually landed
     * on is read back independently via its own setScale hook (data-zoom-min/-max on the
     * panel's element, see insights-chart.js) rather than trusted from the call here.
     */
    function zoomTo(min, max) {
        zoomWindow = {min, max};
        panels.forEach(panel => panel.chart?.setXScale(min, max));
        onZoomChange(true);
    }

    /**
     * Restores every chart to auto-fitting its own data, live-following new ticks again.
     * Wired to the toolbar's reset control and to every chart's own double-click (see
     * insights-chart.js) - either one un-zooms all of them, not just the chart clicked.
     */
    function resetZoom() {
        if (!zoomWindow) return;
        zoomWindow = null;
        panels.forEach(panel => panel.chart?.resetXScale());
        onZoomChange(false);
    }

    // --- Live updates ---------------------------------------------------------------------

    function markDirty(level) {
        panels.forEach(panel => {
            if (panel.level === level) panel.dirty = true;
        });
        scheduleFlush();
    }

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

    function setEvents(events) {
        panels.forEach(panel => panel.chart?.setEvents(events));
        scheduleFlush();
    }

    function destroy() {
        chartObserver?.disconnect();
        sizeObserver?.disconnect();
        themeObserver?.disconnect();
        if (frame !== null) cancelAnimationFrame(frame);
        frame = null;
        panels.forEach(destroyChart);
        panels.clear();
    }

    renderPanels();
    initPanels(initial.overrides);
    observe();

    return {
        globalLevel: () => globalLevel,
        setGlobalLevel,
        percentiles: () => showPercentiles,
        setPercentiles,
        markers: () => showMarkers,
        setMarkers,
        overrides,
        applyOverrides,
        markDirty,
        setEvents,
        resetZoom,
        destroy
    };
}

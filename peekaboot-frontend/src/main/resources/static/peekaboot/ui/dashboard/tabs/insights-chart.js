/**
 * uPlot glue for the Insights tab: lazy loading of the vendored library and the
 * construction of one chart per panel.
 *
 * uPlot is only fetched the first time a chart actually has to be drawn (see
 * insights.js's IntersectionObserver) - a dashboard that never opens the tab, or
 * only scrolls its top row, never pays for the library.
 *
 * A chart is a handle rather than a bare uPlot instance: the mapping from the
 * per-level data store to uPlot's column-major data (which stat of which series
 * feeds which column) is fixed at construction and reused by every setData, so
 * callers only ever hand over a level snapshot.
 */
import {formatMetricValue} from '../../shared/format.js';

const UPLOT_SCRIPT = new URL('../../vendor/uplot/uplot.iife.min.js', import.meta.url);
const UPLOT_STYLES = new URL('../../vendor/uplot/uplot.min.css', import.meta.url);

const CHART_HEIGHT = 180;
const MIN_CHART_WIDTH = 200;
const PERCENTILES = ['p90', 'p95', 'p99'];
/** uPlot copies series.class onto the series' legend row; dashboard.css hides these. */
const MUTED_LEGEND_CLASS = 'pk-insight-legend-muted';
const BAND_ALPHA = '2b';                       // ~17% - the min/max band under the avg stroke
const FALLBACK_BAND_FILL = 'rgba(128, 128, 128, 0.17)';

/**
 * Series strokes, resolved per chart from the design tokens so charts follow the
 * active theme. Text-tuned tokens are preferred where they exist: a 2px stroke is
 * a graphical object and needs the same 3:1 contrast against the card background
 * that the fill-tuned tokens (--pk-primary, --pk-warning) do not reach in light
 * mode. Only one green is used, so a green line is never ambiguous.
 */
const STROKE_TOKENS = [
    ['--pk-primary-text', '#487e1b'],
    ['--pk-info-text', '#0a6e7f'],
    ['--pk-warning-text', '#a16207'],
    ['--pk-purple', '#7c3aed'],
    ['--pk-danger', '#dc2626'],
    ['--pk-text-muted', '#6b7280']
];

let uplotReady = null;

/** Resolves once window.uPlot is usable; injects the vendored script/stylesheet on first call. */
export function ensureUplot() {
    if (window.uPlot) return Promise.resolve();
    if (!uplotReady) {
        const styles = document.createElement('link');
        styles.rel = 'stylesheet';
        styles.href = UPLOT_STYLES.href;
        document.head.appendChild(styles);

        uplotReady = new Promise((resolve, reject) => {
            const script = document.createElement('script');
            script.src = UPLOT_SCRIPT.href;
            script.onload = () => resolve();
            script.onerror = () => {
                uplotReady = null;             // let a later chart retry the load
                reject(new Error('uPlot failed to load'));
            };
            document.head.appendChild(script);
        });
    }
    return uplotReady;
}

// --- Token resolution ----------------------------------------------------------------------

function token(styles, name, fallback) {
    return styles.getPropertyValue(name).trim() || fallback;
}

function themeColors() {
    const styles = getComputedStyle(document.documentElement);
    return {
        strokes: STROKE_TOKENS.map(([name, fallback]) => token(styles, name, fallback)),
        axis: token(styles, '--pk-text-muted', '#6b7280'),
        grid: token(styles, '--pk-border', '#d1d5db'),
        font: '12px ' + token(styles, '--pk-font', 'system-ui, sans-serif')
    };
}

/** The stroke color at ~17% opacity, for the min/max band fill. */
function bandFill(stroke) {
    if (/^#[0-9a-f]{6}$/i.test(stroke)) return stroke + BAND_ALPHA;
    if (/^#[0-9a-f]{3}$/i.test(stroke)) {
        return '#' + [...stroke.slice(1)].map(c => c + c).join('') + BAND_ALPHA;
    }
    return FALLBACK_BAND_FILL;
}

// --- Chart construction --------------------------------------------------------------------

function unitOf(panel, series) {
    return series.unit || panel.unit;
}

function chartWidth(mount) {
    return Math.max(mount.clientWidth || 0, MIN_CHART_WIDTH);
}

function timeAxis(colors) {
    return {side: 2, stroke: colors.axis, font: colors.font,
        grid: {stroke: colors.grid, width: 1}, ticks: {stroke: colors.grid}};
}

function valueAxis(scale, unit, side, colors) {
    return {
        scale, side, size: 60, stroke: colors.axis, font: colors.font,
        grid: {show: side === 3, stroke: colors.grid, width: 1},
        ticks: {stroke: colors.grid},
        values: (plot, splits) => splits.map(value => formatMetricValue(value, unit))
    };
}

/**
 * Creates one chart for {@code panel} inside {@code mount}.
 *
 * Level 0 draws the raw tick value of every panel series. Levels >= 1 draw, per
 * panel series, a min/max band (a uPlot band between two stroke-less edge series)
 * with the average on top, plus p90/p95/p99 hairlines the caller toggles as a
 * group. Bars are a level-0 shape only - a bar cannot carry a min/max band, so an
 * aggregated bars/bars-line panel falls back to lines.
 *
 * A drag-selection on the x-axis calls back into {@code onZoom(min, max)} with the
 * selected window in uPlot's time scale (epoch seconds, the same unit on every
 * chart regardless of level - see toData/timestamps below) instead of zooming this
 * chart alone: the caller is the one that knows about every other panel and pins
 * them all to the same window. Native zoom-on-drag is therefore switched off
 * (cursor.drag.setScale: false) so this hook is the only thing that ever moves the
 * x scale. A double-click - uPlot's own built-in gesture for undoing a zoom -
 * likewise calls back into {@code onZoomReset()} rather than resetting just itself.
 */
export function createChart({panel, mount, level, snapshot, showPercentiles, onZoom, onZoomReset}) {
    const colors = themeColors();
    const columns = [];                        // data column i feeds uPlot series i + 1
    const series = [{}];                       // [0] is the x series
    const bands = [];
    const percentileIndices = [];
    let secondaryUnit = null;

    const line = (label, stroke, scale, paths) => ({
        label, stroke, scale, width: 2, paths, points: {show: false}, spanGaps: false
    });
    // band edges and percentile hairlines share their panel series' color and are
    // kept out of the legend (see MUTED_LEGEND_CLASS) - one legend entry per panel
    // series, exactly as at level 0
    const edge = (label, stroke, scale) => ({
        label, stroke, scale, width: 0, class: MUTED_LEGEND_CLASS,
        points: {show: false}, spanGaps: false
    });
    const hairline = (label, stroke, scale) => ({
        label, stroke, scale, width: 1, dash: [4, 3], show: showPercentiles,
        class: MUTED_LEGEND_CLASS, points: {show: false}, spanGaps: false
    });

    panel.series.forEach((definition, index) => {
        const unit = unitOf(panel, definition);
        const stroke = colors.strokes[index % colors.strokes.length];
        let scale = 'y';
        if (unit !== panel.unit) {
            scale = '2';
            secondaryUnit = unit;
        }

        if (level === 0) {
            const bars = panel.chart === 'bars' || (panel.chart === 'bars-line' && index === 0);
            columns.push({key: definition.id, stat: null});
            series.push(line(definition.label, stroke, scale,
                    bars ? window.uPlot.paths.bars({size: [0.6, 100]}) : null));
            return;
        }

        const minIndex = series.length;
        columns.push({key: definition.id, stat: 'min'});
        series.push(edge(`${definition.label} min`, stroke, scale));
        columns.push({key: definition.id, stat: 'max'});
        series.push(edge(`${definition.label} max`, stroke, scale));
        // uPlot fills a band while drawing its first series, clipped to the second
        bands.push({series: [minIndex + 1, minIndex], fill: bandFill(stroke)});

        columns.push({key: definition.id, stat: 'avg'});
        series.push(line(definition.label, stroke, scale, null));

        PERCENTILES.forEach(stat => {
            percentileIndices.push(series.length);
            columns.push({key: definition.id, stat});
            series.push(hairline(`${definition.label} ${stat}`, stroke, scale));
        });
    });

    const axes = [timeAxis(colors), valueAxis('y', panel.unit, 3, colors)];
    if (secondaryUnit) axes.push(valueAxis('2', secondaryUnit, 1, colors));

    const options = {
        width: chartWidth(mount),
        height: CHART_HEIGHT,
        series,
        bands,
        axes,
        // values live in the panel header readout and the axis labels; a live legend
        // would repeat them per series and, at level >= 1, dwarf the card
        legend: {show: true, live: false},
        cursor: {
            points: {show: false},
            // x-only drag, and uPlot must not zoom this chart on its own - the
            // setSelect hook below reports the selection to the caller instead, which
            // applies it to every chart (including this one)
            drag: {x: true, y: false, setScale: false}
        },
        hooks: {
            setSelect: [u => {
                if (u.select.width === 0) return;
                const min = u.posToVal(u.select.left, 'x');
                const max = u.posToVal(u.select.left + u.select.width, 'x');
                // clears the drag rectangle without re-firing this hook (fireHook=false)
                u.setSelect({left: 0, top: 0, width: 0, height: 0}, false);
                onZoom(min, max);
            }]
        }
    };

    const plot = new window.uPlot(options, toData(columns, snapshot), mount);
    // uPlot's own reset-on-double-click gesture, redirected to reset every chart
    plot.over.ondblclick = () => onZoomReset();

    return {
        setData(next, resetScales = true) {
            plot.setData(toData(columns, next), resetScales);
        },
        setSize(width) {
            plot.setSize({width: Math.max(width, MIN_CHART_WIDTH), height: CHART_HEIGHT});
        },
        setPercentiles(show) {
            percentileIndices.forEach(index => plot.setSeries(index, {show}));
        },
        setXScale(min, max) {
            plot.setScale('x', {min, max});
        },
        resetXScale() {
            plot.setScale('x', {min: null, max: null});
        },
        destroy() {
            plot.destroy();
        }
    };
}

// --- Snapshot -> uPlot data ------------------------------------------------------------------

/**
 * The ring holds no timestamps: sample i is exactly (count - 1 - i) intervals
 * before the ring's end. uPlot's time scale is in seconds, not milliseconds.
 */
function timestamps(snapshot) {
    const {count, endEpochMs, intervalMs} = snapshot;
    const xs = new Array(count);
    for (let i = 0; i < count; i++) {
        xs[i] = (endEpochMs - (count - 1 - i) * intervalMs) / 1000;
    }
    return xs;
}

function toData(columns, snapshot) {
    const xs = timestamps(snapshot);
    return [xs, ...columns.map(column => columnValues(column, snapshot, xs.length))];
}

/**
 * uPlot requires every column to match the x column's length; a series the server
 * does not (yet) know, or one whose ring is a sample behind, is padded at the
 * front - the newest sample must stay aligned with the newest timestamp.
 */
function columnValues({key, stat}, snapshot, length) {
    const entry = snapshot.series[key];
    const values = stat ? entry?.[stat] : entry;
    if (!Array.isArray(values)) return new Array(length).fill(null);
    if (values.length === length) return values;
    if (values.length > length) return values.slice(values.length - length);
    return new Array(length - values.length).fill(null).concat(values);
}

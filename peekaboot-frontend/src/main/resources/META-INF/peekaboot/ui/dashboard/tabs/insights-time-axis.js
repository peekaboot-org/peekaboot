/**
 * The Insights charts' time axis: the label under each tick, in the dashboard's locale and
 * timezone, the tick spacing those labels need, and tick placement in that timezone.
 *
 * A label has uPlot's two-row layout. The first row names the tick at the precision of the
 * tick increment. The second gives the context the first row leaves out (the date under a
 * time, the year under a date), where that context changes and once at the axis start.
 *
 * uPlot spaces ticks by a fixed minimum distance that knows nothing of the text drawn under
 * them, and locale formats vary in width, so the spacing is fitted to the real labels: see
 * fittedTickSpace.
 */
import {formatDateTimeWith} from '../../shared/format.js';

const MINUTE = 60;
const DAY = 86400;
const MONTH = 28 * DAY;
const YEAR = 365 * DAY;

const DATE = {year: '2-digit', month: 'numeric', day: 'numeric'};
const TIME = {hour: 'numeric', minute: '2-digit'};
const YEAR_ONLY = {year: 'numeric'};

/** uPlot's own default for an x axis: the least room a tick gets. */
const MIN_TICK_SPACE = 50;
/** The clear space kept between two neighbouring labels in one row. */
const LABEL_GAP = 8;

const formatAt = (epochSeconds, options, display) => formatDateTimeWith(epochSeconds * 1000, options, display);

/** ":ss", or ":ss.mmm" under a sub-second increment; no timezone has a seconds offset, so this is zone-free. */
function secondsOf(epochSeconds, incr) {
    const millis = Math.round(epochSeconds * 1000);
    const seconds = ':' + String(Math.floor(millis / 1000) % 60).padStart(2, '0');
    return incr < 1 ? seconds + '.' + String(millis % 1000).padStart(3, '0') : seconds;
}

/**
 * Per tick increment, coarsest first: the first row, and the second row's context levels,
 * coarsest first. A second row names every level from the coarsest one that changed since
 * the previous tick, so a new day under a seconds axis shows the date as well as the time.
 */
const UNITS = [
    {from: YEAR, tick: (at, display) => formatAt(at, YEAR_ONLY, display), context: []},
    {from: MONTH, tick: (at, display) => formatAt(at, {month: 'short'}, display), context: [YEAR_ONLY]},
    {from: DAY, tick: (at, display) => formatAt(at, {month: 'numeric', day: 'numeric'}, display), context: [YEAR_ONLY]},
    {from: MINUTE, tick: (at, display) => formatAt(at, TIME, display), context: [DATE]},
    {from: 0, tick: (at, display, incr) => secondsOf(at, incr), context: [DATE, TIME]}
];

/**
 * The labels for the ticks at {@code splits} (epoch seconds), {@code incr} seconds apart.
 * {@code display} is the dashboard's {locale, timeZone}.
 *
 * Rollovers of the context are at least two ticks apart at every increment uPlot picks, but
 * the axis start is not a rollover: when the next tick is one, the start leaves its second
 * row to it, so no two second rows sit side by side.
 */
export function timeLabels(splits, incr, display) {
    const unit = UNITS.find(candidate => incr >= candidate.from);
    const contexts = splits.map(at => unit.context.map(options => formatAt(at, options, display)));
    const changedLevel = i => contexts[i].findIndex((value, level) => value !== contexts[i - 1][level]);
    const firstContext = splits.length > 1 && changedLevel(1) !== -1 ? 1 : 0;

    return splits.map((at, i) => {
        const tick = unit.tick(at, display, incr);
        if (unit.context.length === 0 || i < firstContext) return tick;
        const from = i === firstContext ? 0 : changedLevel(i);
        if (from === -1) return tick;
        return tick + '\n' + formatAt(at, Object.assign({}, ...unit.context.slice(from)), display);
    });
}

/** The distance each row's labels need, scaled to the tick spacing: `found` when they already fit. */
function spaceNeeded(labels, positions, found, textWidth) {
    const rows = [];
    labels.forEach((label, tick) => label.split('\n').forEach((text, row) => {
        (rows[row] ??= []).push({x: positions[tick], width: textWidth(text)});
    }));

    let needed = found;
    for (const row of rows) {
        for (let i = 1; i < row.length; i++) {
            const required = (row[i - 1].width + row[i].width) / 2 + LABEL_GAP;
            needed = Math.max(needed, found * required / (row[i].x - row[i - 1].x));
        }
    }
    return needed;
}

/**
 * An axis `space` hook: the least tick spacing at which no two labels in a row touch.
 * Walks uPlot's own increments the way it picks one (the smallest that leaves at least
 * `space` pixels per tick), labels that increment's real ticks and widens `space` until
 * they fit. `textWidth` measures a string in the axis font.
 */
export function fittedTickSpace(u, axisIdx, min, max, plotDim, display, textWidth) {
    const axis = u.axes[axisIdx];
    const span = max - min;
    let space = MIN_TICK_SPACE;
    for (const incr of axis.incrs(u, axisIdx, min, max, plotDim, space)) {
        const found = plotDim * incr / span;
        if (found < space) continue;
        const splits = axis.splits(u, axisIdx, min, max, incr, found);
        const positions = splits.map(at => (at - min) / span * plotDim);
        const needed = spaceNeeded(timeLabels(splits, incr, display), positions, found, textWidth);
        if (needed <= found) return space;
        space = needed;
    }
    return space;
}

/**
 * A uPlot `tzDate` hook: ticks land on the hours and days of the dashboard's timezone, the
 * zone their labels are written in. A zone Intl rejects falls back to the browser's rather
 * than break the chart.
 */
export function zonedDate(dateOptions) {
    return epochSeconds => {
        const date = new Date(Math.round(epochSeconds * 1000));
        const {timeZone} = dateOptions();
        if (!timeZone) return date;
        try {
            return window.uPlot.tzDate(date, timeZone);
        } catch {
            return date;
        }
    };
}

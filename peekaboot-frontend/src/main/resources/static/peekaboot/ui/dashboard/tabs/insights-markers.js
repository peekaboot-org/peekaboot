/**
 * Restart markers for the Insights charts: a vertical line per application start and
 * stop, a tint over the time in between, and one tooltip listing whatever the server
 * says changed with that start.
 *
 * Drawn on the canvas rather than as DOM nodes: a 30-day chart can hold more restarts
 * than it has pixels, and a per-event element would cost one node each while the hover
 * still had to cluster them. The cursor hook clusters instead, so a dense stretch of
 * restarts lists in a single tooltip.
 *
 * Markers are chrome, not data: they are drawn in --pk-text-muted, the token the axes
 * already use, because every one of the six series strokes is a real series colour on
 * some panel.
 *
 * uPlot's canvas spans the whole chart, axis gutters included, so its 2D context draws
 * in canvas-pixel space with the origin at the canvas's own corner - not at the
 * plotting area's corner. u.valToPos(val, 'x', true) returns a position in that space
 * (it is what uPlot's own series paths use internally); the CSS-pixel form (no third
 * argument, or false) instead returns a position relative to the plotting area itself,
 * which is the space u.cursor.left and every DOM node inside u.over live in. The two
 * are not related by a plain *pxRatio - the plotting area is also offset from the
 * canvas corner by the axis gutters (u.bbox.left/top) - so this module always asks
 * valToPos for whichever space a given call site needs rather than converting one into
 * the other by hand.
 */
import {escapeHtml} from '../../shared/markup.js';
import {formatDateTime} from '../../shared/format.js';
import {themeToken, withAlpha} from './insights-colors.js';

const BAND_ALPHA = '1f';                  // ~12% - the downtime tint
const FALLBACK_INK = '#6b7280';
const FALLBACK_BAND = 'rgba(107, 114, 128, 0.12)';
const HIT_RADIUS = 5;                     // CSS px around a marker that shows its tooltip
const FLAG_WIDTH = 3;                     // half-width of a start's top flag
const FLAG_HEIGHT = 5;
const TIMESTAMP_OPTIONS = {
    year: 'numeric', month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit', second: '2-digit'
};

/**
 * Resolves the marker colours from the theme tokens. Called once per chart, at
 * construction: getComputedStyle forces a style recalc, and a theme switch rebuilds
 * every chart from scratch anyway, so there is nothing a per-draw reread could catch.
 */
function ink() {
    const stroke = themeToken('--pk-text-muted', FALLBACK_INK);
    return {stroke, band: withAlpha(stroke, BAND_ALPHA, FALLBACK_BAND)};
}

/** Stop -> next start pairs, in uPlot's time scale (epoch seconds). */
function downtimes(events) {
    const spans = [];
    for (let i = 0; i < events.length - 1; i++) {
        if (events[i].type === 'stop' && events[i + 1].type === 'start') {
            spans.push([events[i].epochMs / 1000, events[i + 1].epochMs / 1000]);
        }
    }
    return spans;
}

/**
 * Draws restart markers on a chart. `intervalMs` is the charted level's own sample
 * interval, in milliseconds - it is never derived from the chart's x data, because a
 * chart with a single sample (a freshly started application, which has exactly one
 * sample and only its own start marker to show in the first place) has no second
 * point to subtract a spacing from. Deriving the interval from the data would give
 * zero in exactly that case and silently drop the one marker that matters most. The
 * interval is fixed for a chart's lifetime - a level change rebuilds the chart - so
 * taking it once, at construction, is sound.
 */
export function createMarkerLayer({intervalMs}) {
    const intervalSeconds = intervalMs / 1000;
    const colors = ink();
    let events = [];
    let visible = true;
    let tooltip = null;
    let placed = [];                      // {left, event} in CSS px, refreshed on every draw

    function panelOf(u) {
        return u.root.closest('.pk-insight-panel');
    }

    /**
     * The event's clamped position in the x scale's own value domain (epoch seconds),
     * or null when it is outside the window. An event up to one interval in front of
     * the first sample is pinned to the scale's minimum: a run's own start always
     * precedes the first thing it could sample, and dropping it would hide every
     * marker on a chart that has not yet been restarted. Anything older than that
     * genuinely is outside the window and is not drawn.
     */
    function clamp(u, epochSeconds) {
        const min = u.scales.x.min;
        const max = u.scales.x.max;
        if (min == null || max == null || epochSeconds > max) return null;
        if (epochSeconds < min) {
            return epochSeconds >= min - intervalSeconds ? min : null;
        }
        return epochSeconds;
    }

    function publish(u) {
        const panel = panelOf(u);
        if (!panel) return;
        panel.dataset.markerCount = String(placed.length);
        panel.dataset.markerX = placed.map(marker => Math.round(marker.left)).join(',');
    }

    function draw(u) {
        placed = [];
        if (!visible || events.length === 0) {
            publish(u);
            return;
        }
        // pxRatio is a static on the uPlot class (window.uPlot.pxRatio), not a
        // property of a chart instance - u.pxRatio is always undefined, and reading
        // it would silently draw every stroke at half its intended device-pixel
        // width on a HiDPI screen. devicePixelRatio is the fallback if uPlot is
        // somehow not the global that set it.
        const ratio = window.uPlot?.pxRatio || window.devicePixelRatio || 1;
        const {left, top, width, height} = u.bbox;
        const ctx = u.ctx;

        ctx.save();
        ctx.beginPath();
        ctx.rect(left, top, width, height);
        ctx.clip();

        ctx.fillStyle = colors.band;
        for (const [from, to] of downtimes(events)) {
            const fromVal = clamp(u, from);
            const toVal = clamp(u, to);
            if (fromVal === null || toVal === null) continue;
            // canvas-pixel space (see the module comment) - ctx draws nowhere else
            const start = u.valToPos(fromVal, 'x', true);
            const end = u.valToPos(toVal, 'x', true);
            ctx.fillRect(start, top, Math.max(end - start, ratio), height);
        }

        ctx.lineWidth = ratio;
        ctx.strokeStyle = colors.stroke;
        ctx.fillStyle = colors.stroke;
        for (const event of events) {
            const val = clamp(u, event.epochMs / 1000);
            if (val === null) continue;
            // CSS-pixel space: this is what the tooltip's hit test and the panel's
            // data-marker-x (a later browser test hovers in this same space) both use
            placed.push({left: u.valToPos(val, 'x'), event});

            // half the (device-px) line width, so the 1-CSS-px stroke centers on a
            // whole device pixel instead of straddling two and blurring
            const canvasX = Math.round(u.valToPos(val, 'x', true)) + ratio / 2;
            ctx.setLineDash(event.type === 'stop' ? [4 * ratio, 3 * ratio] : []);
            ctx.beginPath();
            ctx.moveTo(canvasX, top);
            ctx.lineTo(canvasX, top + height);
            ctx.stroke();

            if (event.type === 'start') {
                ctx.beginPath();
                ctx.moveTo(canvasX - FLAG_WIDTH * ratio, top);
                ctx.lineTo(canvasX + FLAG_WIDTH * ratio, top);
                ctx.lineTo(canvasX, top + FLAG_HEIGHT * ratio);
                ctx.closePath();
                ctx.fill();
            }
        }
        ctx.setLineDash([]);
        ctx.restore();
        publish(u);
    }

    function describe({event}) {
        const when = formatDateTime(event.epochMs, TIMESTAMP_OPTIONS);
        const rows = [];
        if (event.version) rows.push(['Version', event.version]);
        if (event.branch) rows.push(['Branch', event.branch]);
        if (event.shortCommitId) rows.push(['Commit', event.shortCommitId]);
        if (event.buildTimeEpochMs) rows.push(['Built', formatDateTime(event.buildTimeEpochMs, TIMESTAMP_OPTIONS)]);

        const title = `${event.type === 'stop' ? 'Stopped' : 'Started'} ${escapeHtml(when)}`;
        const details = rows
            .map(([label, value]) => `<dt>${label}</dt><dd>${escapeHtml(String(value))}</dd>`)
            .join('');
        const unclean = event.uncleanPrevious
            ? '<p class="pk-insight-marker-tip__note">Previous run ended without a clean shutdown</p>'
            : '';
        return `<p class="pk-insight-marker-tip__title">${title}</p>${
            details ? `<dl>${details}</dl>` : ''}${unclean}`;
    }

    function setCursor(u) {
        if (!tooltip) return;
        const cursorLeft = u.cursor.left;
        if (!visible || cursorLeft == null || cursorLeft < 0) {
            tooltip.hidden = true;
            return;
        }
        const hits = placed.filter(marker => Math.abs(marker.left - cursorLeft) <= HIT_RADIUS);
        if (hits.length === 0) {
            tooltip.hidden = true;
            return;
        }
        tooltip.innerHTML = hits.map(describe).join('<hr>');
        tooltip.hidden = false;
        // flip to the cursor's left rather than overflow the plot
        const overflows = cursorLeft + tooltip.offsetWidth + 12 > u.over.clientWidth;
        tooltip.style.left = `${Math.max(overflows ? cursorLeft - tooltip.offsetWidth - 8 : cursorLeft + 8, 0)}px`;
    }

    return {
        plugin: {
            hooks: {
                init: u => {
                    tooltip = document.createElement('div');
                    tooltip.className = 'pk-insight-marker-tip';
                    tooltip.hidden = true;
                    u.over.appendChild(tooltip);
                },
                draw,
                setCursor
            }
        },
        setEvents(next) {
            events = Array.isArray(next) ? next : [];
        },
        setVisible(next) {
            visible = next;
            if (!next && tooltip) tooltip.hidden = true;
        }
    };
}

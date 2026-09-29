/**
 * Trace-detail overlay - one captured log as both the Logs tab and a span's details panel show
 * it: time, level, message and the folded stack trace with its reveal control. The Logs tab
 * passes its span cell (filter-to-span, tree link, copyable id) to sit after the time; a span's
 * panel passes none, since every entry there already belongs to that span.
 */
import {el, pressedToggle} from '../shared/dom.js';
import {formatTimeOfDay} from '../shared/format.js';

export function logEntry(log, dateOptions, spanCell = null) {
    const row = el('div', {className: 'pk-log__row'},
        el('span', {className: 'pk-log__time', text: formatTimeOfDay(log.timestamp, dateOptions)}),
        spanCell,
        el('span', {className: `pk-log__level pk-log__level--${String(log.level).toLowerCase()}`, text: log.level}),
        el('span', {className: 'pk-log__message', text: log.message}));

    const entry = el('div', {className: 'pk-log', attrs: {'data-level': log.level, 'data-span-id': log.spanId || ''}}, row);

    if (log.stackTrace) {
        const trace = traceBlock(log);
        // A control that reveals nothing must not appear - see shared/unmask-control.js.
        if ((log.hiddenFrames || []).length > 0) entry.append(revealControl(trace));
        entry.append(trace);
    }

    return entry;
}

/**
 * The trace a log record carried, with the runs the backend marked hidden closed behind a
 * <details>. The ranges come from the server, which owns the exclusion list; this renders what
 * it is told and classifies nothing. hiddenFrames arrives empty when folding is switched off
 * (the server empties its own exclusion list), so that state needs no flag of its own here -
 * every line just renders in the open, ranges-driven the same as the folded case.
 */
function traceBlock(log) {
    const lines = log.stackTrace.split('\n');
    const app = new Set();
    (log.applicationFrames || []).forEach(r => {
        for (let i = r.start; i < r.endExclusive; i++) app.add(i);
    });
    const hidden = new Map((log.hiddenFrames || []).map(r => [r.start, r]));

    const pre = el('pre', {className: 'pk-log__trace', attrs: {tabindex: '0', 'aria-label': 'Stack trace'}});
    for (let i = 0; i < lines.length; ) {
        const run = hidden.get(i);
        if (run) {
            // Drained on match, the way StackTraceHtml.java polls its ranges off a Deque:
            // a malformed range (endExclusive <= start) then advances i by zero or backward,
            // but can never match this same entry again, so the walk still terminates.
            hidden.delete(i);
            const details = el('details', {className: 'pk-log__hidden'});
            const count = run.endExclusive - run.start;
            details.append(el('summary', {
                className: 'pk-log__hidden-summary',
                text: `${count} ${count === 1 ? 'frame' : 'frames'} hidden`
            }));
            for (let j = run.start; j < run.endExclusive; j++) details.append(frameLine(lines[j], app.has(j)));
            pre.append(details);
            i = run.endExclusive;
        } else {
            pre.append(frameLine(lines[i], app.has(i)));
            i++;
        }
    }
    return pre;
}

function frameLine(text, applicationFrame) {
    return el('span', {className: applicationFrame ? 'pk-log__frame pk-log__frame--app' : 'pk-log__frame', text});
}

/** One control per log row: opens or closes every hidden run inside that row's trace at once. */
function revealControl(pre) {
    return pressedToggle({className: 'pk-btn pk-btn--small pk-log__reveal', text: 'Show full stack trace'}, (open, control) => {
        control.textContent = open ? 'Hide framework frames' : 'Show full stack trace';
        pre.querySelectorAll('details.pk-log__hidden').forEach(run => {
            run.open = open;
        });
    });
}

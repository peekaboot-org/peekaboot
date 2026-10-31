/**
 * Trace-detail overlay - one captured log as both the Logs tab and a span's details panel show
 * it: time, level, message and the folded stack trace with its reveal control. The Logs tab
 * passes its span cell (filter-to-span, tree link, copyable id) to sit after the time; a span's
 * panel passes none, since every entry there already belongs to that span. The span's panel
 * also renders a failed span's exception through stackTraceParts.
 */
import {el, pressedToggle} from '../shared/dom.js';
import {formatTimeOfDay} from '../shared/format.js';
import {logLevelVariant} from '../shared/severity.js';

export function logEntry(log, dateOptions, spanCell = null) {
    const row = el('div', {className: 'pk-log__row'},
        el('span', {className: 'pk-log__time', text: formatTimeOfDay(log.timestamp, dateOptions)}),
        spanCell,
        el('span', {className: `pk-log__level pk-log__level--${logLevelVariant(log.level)}`, text: log.level}),
        el('span', {className: 'pk-log__message', text: log.message}));

    return el('div', {className: 'pk-log', attrs: {'data-level': log.level, 'data-span-id': log.spanId || ''}},
        row, ...stackTraceParts(log));
}

/**
 * A captured stack trace, a log record's or a failed span's exception, as its reveal control
 * followed by the folded trace. Empty where it carries no stackTrace.
 */
export function stackTraceParts(folded) {
    if (!folded.stackTrace) return [];
    const trace = traceBlock(folded);
    // A control that reveals nothing must not appear (shared/unmask-control.js).
    return (folded.hiddenFrames || []).length > 0 ? [revealControl(trace), trace] : [trace];
}

/**
 * The trace with the runs the backend marked hidden closed behind a <details>. The ranges
 * come from the server, which owns the exclusion list; this renders what it is told and
 * classifies nothing. hiddenFrames arrives empty when folding is switched off (the server
 * empties its own exclusion list), so that state needs no flag of its own here. Every line
 * renders in the open, driven by the ranges the same as the folded case.
 */
function traceBlock(folded) {
    const lines = folded.stackTrace.split('\n');
    const app = new Set();
    (folded.applicationFrames || []).forEach(r => {
        for (let i = r.start; i < r.endExclusive; i++) app.add(i);
    });
    const hidden = new Map((folded.hiddenFrames || []).map(r => [r.start, r]));

    const pre = el('pre', {className: 'pk-stack-trace pk-stack-trace-block', attrs: {tabindex: '0', 'aria-label': 'Stack trace'}});
    for (let i = 0; i < lines.length; ) {
        const run = hidden.get(i);
        if (run) {
            // Drained on match, the way StackTraceHtml.java polls its ranges off a Deque:
            // a malformed range (endExclusive <= start) then advances i by zero or backward,
            // but can never match this same entry again, so the walk still terminates.
            hidden.delete(i);
            const details = el('details', {className: 'pk-stack-trace__hidden'});
            const count = run.endExclusive - run.start;
            details.append(el('summary', {
                className: 'pk-stack-trace__hidden-summary',
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
    return el('span', {className: applicationFrame ? 'pk-stack-trace__frame pk-stack-trace__frame--app' : 'pk-stack-trace__frame', text});
}

/** One control per trace: opens or closes every hidden run inside it at once. */
function revealControl(pre) {
    return pressedToggle({className: 'pk-btn pk-btn--small pk-stack-trace-reveal', text: 'Show full stack trace'}, open => {
        pre.querySelectorAll('details.pk-stack-trace__hidden').forEach(run => {
            run.open = open;
        });
    });
}

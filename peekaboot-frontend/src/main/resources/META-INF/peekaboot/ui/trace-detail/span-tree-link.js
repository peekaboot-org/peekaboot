/**
 * Trace-detail overlay - the link from a Queries entry or a Logs row back to its span's row in
 * the Spans tab tree (trace-detail.js's goToSpan). One builder, so the two tabs show the same
 * control. `label` is its accessible name and tooltip, since the glyph alone would be announced
 * by its Unicode name.
 */
import {button} from '../shared/dom.js';

export function spanTreeLink(spanId, goToSpan, label) {
    const link = button({
        className: 'pk-unbutton pk-icon-btn pk-span-tree-link',
        text: '⤶',
        title: label,
        attrs: {'data-span-id': spanId, 'aria-label': label}
    });
    // Fresh elements on every render, so per-element listeners cannot accumulate on the
    // shared tab-content container the way a delegated one would.
    link.addEventListener('click', () => goToSpan(spanId));
    return link;
}

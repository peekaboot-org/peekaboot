/**
 * Copy-to-clipboard identifier: renders a labelled, full-length trace or span id that
 * copies itself when clicked.
 *
 * Ids appear on three surfaces - the dashboard document, the toolbar's shadow root and
 * the overlay's shadow root - and the click is handled by a single delegated listener per
 * root: content re-renders freely without leaking listeners or needing to re-bind.
 */
import {el, button} from './dom.js';

const COPY_ICON = '⧉';
const COPIED_ICON = '✓';
const COPIED_FEEDBACK_MS = 1500;

const boundRoots = new WeakSet();
/** The feedback timer of each control, keyed by the element so the DOM carries no expando. */
const feedbackTimers = new WeakMap();

/**
 * The control as a detached element. `label` names the kind of id ("traceId", "spanId")
 * and is shown as a prefix, so the value is never a bare hex string with no explanation.
 */
export function copyableId(value, {label, truncate = false} = {}) {
    if (!value) {
        return el('span', {className: 'pk-copy pk-copy--empty', text: `${label || ''} -`});
    }
    const id = String(value);
    return button({
        className: 'pk-copy' + (truncate ? ' pk-copy--truncate' : ''),
        title: `Copy ${label || ''}`,
        attrs: {'data-pk-copy': id, 'aria-label': `Copy ${label || ''} ${id}`}
    },
    label ? el('span', {className: 'pk-copy__label', text: label}) : null,
    el('span', {className: 'pk-copy__value', text: id}),
    el('span', {className: 'pk-copy__icon', text: COPY_ICON, attrs: {'aria-hidden': 'true'}}),
    el('span', {className: 'pk-copy__status', attrs: {role: 'status'}}));
}

/**
 * Attaches the one delegated click listener a root needs. Safe to call repeatedly -
 * a root is only ever bound once. Pass the document for the dashboard, or the shadow
 * root for the toolbar and the overlay.
 */
export function bindCopyables(root) {
    if (!root || boundRoots.has(root)) {
        return;
    }
    boundRoots.add(root);
    // Capture, not bubble. The clickable things an id sits inside - the toolbar bar, a
    // trace row - listen on elements *below* this root, so a bubbling listener here would
    // run after them and stopPropagation would come too late to stop the overlay opening.
    root.addEventListener('click', event => {
        const target = event.target;
        const button = target && target.closest ? target.closest('.pk-copy[data-pk-copy]') : null;
        if (!button || !root.contains(button)) {
            return;
        }
        // copying an id is not a request to also open or filter whatever contains it
        event.stopPropagation();
        event.preventDefault();
        copyText(button.dataset.pkCopy).then(ok => showResult(button, ok));
    }, true);
}

function showResult(button, ok) {
    const icon = button.querySelector('.pk-copy__icon');
    const status = button.querySelector('.pk-copy__status');
    if (icon) icon.textContent = ok ? COPIED_ICON : COPY_ICON;
    if (status) status.textContent = ok ? 'Copied' : 'Copy failed';
    button.classList.toggle('pk-copy--copied', ok);
    button.classList.toggle('pk-copy--failed', !ok);

    clearTimeout(feedbackTimers.get(button));
    feedbackTimers.set(button, setTimeout(() => {
        if (icon) icon.textContent = COPY_ICON;
        if (status) status.textContent = '';
        button.classList.remove('pk-copy--copied', 'pk-copy--failed');
    }, COPIED_FEEDBACK_MS));
}

/**
 * navigator.clipboard exists only in a secure context, which rules out an app served
 * over plain HTTP from anything but localhost - exactly where a dev toolbar tends to
 * run. Falls back to the legacy selection copy there.
 */
function copyText(value) {
    if (navigator.clipboard && window.isSecureContext) {
        return navigator.clipboard.writeText(value).then(() => true, () => legacyCopy(value));
    }
    return Promise.resolve(legacyCopy(value));
}

function legacyCopy(value) {
    // must live in the document, not a shadow root: execCommand acts on the document
    // selection, which cannot address nodes inside a shadow tree
    const area = document.createElement('textarea');
    area.value = value;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.top = '-1000px';
    area.style.opacity = '0';
    document.body.appendChild(area);
    try {
        area.select();
        return document.execCommand('copy');
    } catch {
        return false;
    } finally {
        area.remove();
    }
}

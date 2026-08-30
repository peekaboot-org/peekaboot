/**
 * Copy-to-clipboard identifier: renders a labelled, full-length trace or span id that
 * copies itself when clicked.
 *
 * Ids appear on three surfaces - the dashboard document, the toolbar's shadow root and
 * the overlay's shadow root - and two of them build markup as HTML strings rather than
 * DOM nodes. So the primitive ships in both shapes over one piece of markup, and the
 * click is handled by a single delegated listener per root: content re-renders freely
 * without leaking listeners or needing to re-bind.
 */
import {escapeHtml} from './markup.js';

const COPY_ICON = '⧉';      // ⧉
const COPIED_ICON = '✓';    // ✓
const COPIED_FEEDBACK_MS = 1500;

const boundRoots = new WeakSet();

/**
 * Markup for a copyable id. `label` names the kind of id ("traceId", "spanId") and is
 * shown as a prefix, so the value is never a bare hex string with no explanation.
 *
 * `displayValue`, when given, replaces only the visible `.pk-copy__value` text (e.g. a
 * shortened id) - the copy payload, accessible name and title all still name the full
 * `value`, so what gets copied and announced is never the truncated form.
 */
export function copyableIdHtml(value, {label, truncate = false, displayValue} = {}) {
    if (!value) {
        return `<span class="pk-copy pk-copy--empty">${escapeHtml(label || '')} -</span>`;
    }
    const safeValue = escapeHtml(String(value));
    const safeDisplayValue = displayValue != null ? escapeHtml(String(displayValue)) : safeValue;
    const safeLabel = label ? escapeHtml(label) : '';
    return `<button type="button" class="pk-copy${truncate ? ' pk-copy--truncate' : ''}"`
         + ` data-pk-copy="${safeValue}"`
         + ` aria-label="Copy ${safeLabel} ${safeValue}" title="Copy ${safeLabel}">`
         + (safeLabel ? `<span class="pk-copy__label">${safeLabel}</span>` : '')
         + `<span class="pk-copy__value">${safeDisplayValue}</span>`
         + `<span class="pk-copy__icon" aria-hidden="true">${COPY_ICON}</span>`
         + `<span class="pk-copy__status" role="status"></span>`
         + `</button>`;
}

/** The same control as a detached element, for call sites that build DOM. */
export function copyableId(value, options = {}) {
    const holder = document.createElement('span');
    holder.innerHTML = copyableIdHtml(value, options);
    return holder.firstElementChild;
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

    clearTimeout(button.pkCopyTimer);
    button.pkCopyTimer = setTimeout(() => {
        if (icon) icon.textContent = COPY_ICON;
        if (status) status.textContent = '';
        button.classList.remove('pk-copy--copied', 'pk-copy--failed');
    }, COPIED_FEEDBACK_MS);
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

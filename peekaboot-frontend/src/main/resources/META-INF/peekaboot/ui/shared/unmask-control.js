/**
 * The masked-secrets reveal control shared by the Environment and Config tabs. Both
 * tabs render off the same combined `/api/actuator/all/insights` payload fetched once
 * by main.js, so "revealed" is a single per-view boolean main.js owns
 * (`context.unmaskRequested` / `context.toggleUnmask()`) rather than two independently
 * toggleable copies that could disagree about what the shared payload actually holds -
 * flipping it on the Environment tab also reveals the Config tab, and vice versa.
 *
 * Rendered only when `features.unmaskingEnabled` is true - a control that cannot work
 * must never appear (see peekaboot-frontend/README.md's accessibility invariants). When
 * the feature is off, `slot` is left empty rather than holding a hidden button, so the
 * control is genuinely absent from the DOM, not merely invisible.
 */
import {el, button} from './dom.js';

export function renderUnmaskControl(slot, context) {
    if (!slot) return;

    if (!context?.features?.unmaskingEnabled) {
        slot.replaceChildren();
        return;
    }

    let control = slot.querySelector('.pk-unmask-toggle');
    if (!control) {
        control = buildButton(context);
        slot.replaceChildren(control);
    }
    updateButton(control, Boolean(context.unmaskRequested));
}

function buildButton(context) {
    const control = button({className: 'pk-btn pk-btn--small pk-unmask-toggle'},
        el('span', {className: 'pk-unmask-toggle__icon', text: '\u{1F441}', attrs: {'aria-hidden': 'true'}}), // eye
        el('span', {className: 'pk-unmask-toggle__label', text: 'Show secrets'}));
    // context.toggleUnmask is a stable function reference owned by main.js (it reads/
    // writes its own module-scoped state directly) - safe to bind once here even though
    // renderUnmaskControl() itself is called again, with a fresh context object, on
    // every subsequent render.
    control.addEventListener('click', () => context.toggleUnmask());
    return control;
}

function updateButton(control, revealed) {
    control.setAttribute('aria-pressed', String(revealed));
}

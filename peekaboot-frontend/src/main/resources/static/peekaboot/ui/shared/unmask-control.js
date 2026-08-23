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
export function renderUnmaskControl(slot, context) {
    if (!slot) return;

    if (!context?.features?.unmaskingEnabled) {
        slot.replaceChildren();
        return;
    }

    let button = slot.querySelector('.pk-unmask-toggle');
    if (!button) {
        button = buildButton(context);
        slot.replaceChildren(button);
    }
    updateButton(button, Boolean(context.unmaskRequested));
}

function buildButton(context) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'pk-btn pk-btn--small pk-unmask-toggle';

    const icon = document.createElement('span');
    icon.className = 'pk-unmask-toggle__icon';
    icon.setAttribute('aria-hidden', 'true');
    icon.textContent = '\u{1F441}'; // eye

    const label = document.createElement('span');
    label.className = 'pk-unmask-toggle__label';

    button.append(icon, label);
    // context.toggleUnmask is a stable function reference owned by main.js (it reads/
    // writes its own module-scoped state directly) - safe to bind once here even though
    // renderUnmaskControl() itself is called again, with a fresh context object, on
    // every subsequent render.
    button.addEventListener('click', () => context.toggleUnmask());
    return button;
}

function updateButton(button, revealed) {
    button.setAttribute('aria-pressed', String(revealed));
    button.classList.toggle('pk-unmask-toggle--active', revealed);
    button.querySelector('.pk-unmask-toggle__label').textContent =
        revealed ? 'Secrets shown — click to hide' : 'Show secrets';
}

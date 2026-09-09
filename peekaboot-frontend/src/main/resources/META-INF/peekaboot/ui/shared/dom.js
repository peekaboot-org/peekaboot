/**
 * The element builder behind every surface that renders as DOM. Text and children are
 * appended as nodes, never parsed, so nothing built here reaches innerHTML; a caller with
 * a highlighted string still goes through markup.js's highlightText on its own.
 *
 *   el(tag, {className, text, title, attrs}, ...children)
 *
 * `attrs` is a plain name -> value map (`aria-*`, `data-*`, `id`, `role`); a null or
 * undefined value is skipped, as is a null child, so a caller can spell an optional part
 * inline. `button(props, ...children)` is `el('button')` with `type="button"` set.
 */
export function el(tag, {className, text, title, attrs} = {}, ...children) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (title != null) element.title = title;
    if (attrs) {
        Object.entries(attrs).forEach(([name, value]) => {
            if (value != null) element.setAttribute(name, value);
        });
    }
    if (text != null) element.textContent = text;
    element.append(...children.filter(child => child != null));
    return element;
}

export function button(props = {}, ...children) {
    return el('button', {...props, attrs: {type: 'button', ...props.attrs}}, ...children);
}

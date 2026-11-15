import {highlightParts} from './markup.js';

const SVG_NAMESPACE = 'http://www.w3.org/2000/svg';

/**
 * The element builder behind every surface that renders as DOM. Text and children are
 * appended as nodes, never parsed, so a builder call can carry backend data as it is.
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
    if (attrs) setAttributes(element, attrs);
    if (text != null) element.textContent = text;
    element.append(...children.filter(child => child != null));
    return element;
}

function setAttributes(element, attrs) {
    Object.entries(attrs).forEach(([name, value]) => {
        if (value != null) element.setAttribute(name, value);
    });
}

export function button(props = {}, ...children) {
    return el('button', {...props, attrs: {type: 'button', ...props.attrs}}, ...children);
}

/**
 * A toggle button: `aria-pressed` starts false and flips on every click, and `onChange`
 * receives the new state and the control.
 */
export function pressedToggle(props, onChange) {
    const control = button({...props, attrs: {...props.attrs, 'aria-pressed': 'false'}});
    control.addEventListener('click', () => {
        const pressed = control.getAttribute('aria-pressed') !== 'true';
        control.setAttribute('aria-pressed', String(pressed));
        onChange(pressed, control);
    });
    return control;
}

/**
 * Sets `element.dataset[key]` unless it already holds `value`. A caller that runs on every
 * chart draw then writes an attribute only when it changed.
 */
export function setDataAttribute(element, key, value) {
    if (element.dataset[key] !== value) element.dataset[key] = value;
}

/** `text` as child nodes: every match of `query` (see markup.js's highlightParts) in a <mark>, the rest as text. */
export function highlighted(text, query) {
    return highlightParts(text, query).map(part => (part.match ? el('mark', {text: part.text}) : part.text));
}

/**
 * A decorative line icon on a 24x24 grid, drawn in the surrounding text colour so it follows
 * the theme with no token of its own. `shapes` is `[[tag, attributes], ...]`, one SVG shape each.
 */
export function svgIcon(shapes, {size = 18, strokeWidth = 1.75, className} = {}) {
    const icon = svgElement('svg', {
        class: className, viewBox: '0 0 24 24', width: size, height: size,
        fill: 'none', stroke: 'currentColor', 'stroke-width': strokeWidth,
        'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true'
    });
    icon.append(...shapes.map(([tag, attributes]) => svgElement(tag, attributes)));
    return icon;
}

function svgElement(tag, attributes) {
    const element = document.createElementNS(SVG_NAMESPACE, tag);
    setAttributes(element, attributes);
    return element;
}

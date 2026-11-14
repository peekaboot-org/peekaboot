/**
 * Brings a parent's children, or one element's content, up to date without rebuilding what
 * did not change, so a refresh leaves what the reader holds where it is: focus, scroll
 * position, an open group. What an element was built or filled from is remembered here per
 * element, never written to the DOM.
 */

/** Element -> the signature renderIfChanged last filled it with. */
const filledWith = new WeakMap();

/** Element -> the {key, signature} of the item keyedChildren built it from. */
const builtFrom = new WeakMap();

/**
 * Calls `fill(element)` unless `signature`, a string, is the one `element` was last filled
 * with. Focus that sat inside `element` moves to the node at the same child-index path in the
 * new content, when there is one. Returns whether it filled.
 */
export function renderIfChanged(element, signature, fill) {
    if (filledWith.get(element) === signature) return false;
    keepingFocus(element, () => fill(element));
    filledWith.set(element, signature);
    return true;
}

/**
 * Brings `parent`'s children in line with `items`, one child per item.
 *
 *   key(item)             -> the item's identity, a string; items that share one are told
 *                            apart by their order
 *   build(item)           -> the item's element
 *   signature(item)       -> a string that differs once the element is out of date;
 *                            JSON.stringify by default
 *   update(element, item) -> optional; brings a kept element up to date in place
 *
 * Children no item names, and children built elsewhere, are removed first. A kept child with
 * an unchanged signature is not touched. A changed one goes to `update`, or is replaced by a
 * new build with focus moved as in renderIfChanged. A new child is inserted at its position.
 * A kept child is re-inserted only when the items' own order changed: moving a node drops the
 * focus inside it.
 */
export function keyedChildren(parent, items, {key, build, signature = JSON.stringify, update}) {
    const occurrences = new Map();
    const wanted = items.map(item => {
        const itemKey = key(item);
        const occurrence = occurrences.get(itemKey) ?? 0;
        occurrences.set(itemKey, occurrence + 1);
        return {item, source: {key: `${occurrence}:${itemKey}`, signature: signature(item)}};
    });
    const kept = removeUnnamedChildren(parent, new Set(wanted.map(({source}) => source.key)));

    // the child the next item's element belongs in front of
    let next = parent.firstChild;
    wanted.forEach(({item, source}) => {
        const existing = kept.get(source.key);
        if (!existing) {
            parent.insertBefore(tracked(build(item), source), next);
            return;
        }
        if (existing === next) next = existing.nextSibling;
        else parent.insertBefore(existing, next);

        if (builtFrom.get(existing).signature === source.signature) return;
        if (update) {
            update(existing, item);
            builtFrom.set(existing, source);
        } else {
            const replacement = tracked(build(item), source);
            keepingFocus(existing, () => existing.replaceWith(replacement), replacement);
        }
    });
}

function tracked(element, source) {
    builtFrom.set(element, source);
    return element;
}

/** Removes the children keyedChildren did not build or no key in `keys` names, and returns the others by key. */
function removeUnnamedChildren(parent, keys) {
    const kept = new Map();
    Array.from(parent.childNodes).forEach(child => {
        const childKey = builtFrom.get(child)?.key;
        if (keys.has(childKey)) kept.set(childKey, child);
        else child.remove();
    });
    return kept;
}

/**
 * Runs `change`, which may take away content under `before`. When the focused node went with
 * it, focus moves to the node at the same child-index path under `after`, when there is one.
 */
function keepingFocus(before, change, after = before) {
    const focused = before.getRootNode().activeElement;
    const path = focused && before.contains(focused) ? childIndexPath(before, focused) : null;
    change();
    if (path && after.getRootNode().activeElement !== focused) {
        path.reduce((node, index) => node?.children[index], after)?.focus();
    }
}

function childIndexPath(root, descendant) {
    const path = [];
    for (let node = descendant; node !== root; node = node.parentElement) {
        path.unshift([...node.parentElement.children].indexOf(node));
    }
    return path;
}

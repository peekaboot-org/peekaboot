/**
 * Shared URL <-> filter-control reconciliation, used by every dashboard tab whose filter
 * state doubles as its own query params (config.js, environment.js, meters.js, loggers.js;
 * traces.js's bucket/type/op filter is structurally different enough - three independently
 * combinable keys instead of one or two flat fields - that it keeps its own hand-rolled
 * version rather than being forced through this).
 *
 * `reconcileFilterWithUrl` is the shared "which direction wins" decision for every one of
 * those tabs. Two directions, picked by whether
 * this render is URL-authoritative (context.urlIsAuthoritative - a genuine hash change: a
 * deep link, Back/Forward, or a hand-edited hash, as opposed to a programmatic tab switch -
 * see main.js's urlChangeInProgress) or the URL already carries any of this filter's own
 * param keys either way:
 *  - URL-authoritative, or the URL has one of `urlKeys` -> the URL wins, including a bare
 *    one: `seed(params)` restores control state from it, and must itself reset to defaults
 *    when none of `urlKeys` are present (a hand-edited hash with the param(s) removed means
 *    the user asked to clear the filter, and that has to actually clear it, not just leave
 *    the controls untouched). A no-op once the controls already match, which is the common
 *    case - every change writes straight back via `writeBack`, so an ordinary auto-refresh
 *    render (never URL-authoritative) finds nothing to seed here.
 *  - Otherwise (a programmatic, non-authoritative render with a bare URL) -> this filter's
 *    own current control state wins instead, when `hasNonDefaultState()` says there's
 *    something to preserve. A bare hash here almost always just means the tab strip switched
 *    tabs (main.js's onSelect pushes a plain "#<tab>" hash with no params), not that the user
 *    asked to clear the filter, and whatever they set before switching away is still sitting
 *    right there. `writeBack()` is what makes the filter survive switching away and back to
 *    this tab, and makes the URL truthful again instead of silently drifting out of sync with
 *    what's actually filtered.
 */
export function reconcileFilterWithUrl(context, urlKeys, {seed, hasNonDefaultState, writeBack}) {
    const params = context.urlParams || {};
    const urlHasFilterParams = urlKeys.some(key => key in params);

    if (urlHasFilterParams || context.urlIsAuthoritative) {
        seed(params);
    } else if (hasNonDefaultState()) {
        writeBack();
    }
}

/**
 * The single-text-input case `reconcileFilterWithUrl` covers for config.js/environment.js/
 * meters.js: one input, one "q" param, restore-or-write-back with no other state involved.
 */
export function reconcileTextFilter(input, context) {
    if (!input) return;
    reconcileFilterWithUrl(context, ['q'], {
        seed: params => {
            const urlValue = params.q || '';
            if (urlValue !== input.value.trim()) input.value = urlValue;
        },
        hasNonDefaultState: () => Boolean(input.value.trim()),
        writeBack: () => writeTextFilter(input, context)
    });
}

/** Writes a text input's current value back to the URL's "q" param, omitting it entirely
    when empty so a cleared filter yields a clean hash with no stray "q=" left behind. */
export function writeTextFilter(input, context) {
    const value = input.value.trim();
    context.setUrlParams(value ? {q: value} : {});
}

/**
 * A dashboard tab's state that doubles as its own query params, kept in step with the URL:
 * traces.js, loggers.js, insights.js and lifecycle.js each describe theirs to `urlState`,
 * and config.js, environment.js and meters.js get `textFilterState` from
 * filtered-group-tab.js.
 *
 *   keys             -> the URL params this state owns
 *   read(urlParams)  -> the state those params name, a missing or bogus one at its default
 *   apply(state)     -> sets the tab's state and its controls
 *   params()         -> the current state as URL params, every default left out
 *
 * `reconcile(context)` decides which side wins:
 *  - The URL names one of `keys`, or the render is URL-authoritative
 *    (context.urlIsAuthoritative: a deep link, Back/Forward or a hand-edited hash, see
 *    main.js's urlChangeInProgress): the URL wins, a bare one included, because a hash
 *    with the params taken out asks for the defaults. The URL is then written from the
 *    state that took effect, which corrects a bogus or non-canonical value.
 *  - Otherwise the URL is bare on a programmatic render, which almost always means the tab
 *    strip switched tabs (main.js's onSelect pushes a plain "#<tab>"): the state wins and
 *    is written back, so a filter survives switching away and back.
 *
 * `save(context)` writes the state to the URL, for a control that just changed it.
 */
export function urlState({keys, read, apply, params}) {
    function save(context) {
        context.setUrlParams(params());
    }

    function reconcile(context) {
        const urlParams = context.urlParams ?? {};
        if (keys.some(key => key in urlParams) || context.urlIsAuthoritative) {
            apply(read(urlParams));
            save(context);
        } else if (Object.keys(params()).length > 0) {
            save(context);
        }
    }

    return {reconcile, save};
}

/** The one-input case: the input's trimmed text is the "q" param, an empty one leaves it out. */
export function textFilterState(input) {
    return urlState({
        keys: ['q'],
        read: urlParams => urlParams.q || '',
        apply: query => {
            // an input that already says the same keeps the spaces being typed around it
            if (query !== input.value.trim()) input.value = query;
        },
        params: () => {
            const query = input.value.trim();
            return query ? {q: query} : {};
        }
    });
}

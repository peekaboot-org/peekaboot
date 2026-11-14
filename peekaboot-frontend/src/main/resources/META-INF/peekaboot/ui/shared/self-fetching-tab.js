/**
 * The shell of a dashboard tab whose data comes from its own endpoint instead of the
 * shared payload main.js fetches: traces.js, lifecycle.js, the Overview tab's tile row and
 * filtered-group-tab.js's fetchData path. One place for the contract every such tab
 * follows. It fetches from load() until stop(), which a tab calls from its hide(): an
 * answer still in flight then renders nothing. A response an overlapping newer request has
 * overtaken resolves to null (see api.js) and renders nothing; a rejection, or a result
 * that renderResult throws on, renders the error in place of the result.
 *
 *   fetch(context)                     -> the data, or null when superseded
 *   loading(container, {firstLoad})    -> optional; runs before every fetch
 *   renderResult(container, result, context)
 *   renderError(container, error, context)
 *
 * The returned shell exposes load(container, context), which a tab calls from its show()
 * and update() and which resolves once the answer is rendered; refetch(), for a control
 * the tab wires itself (a bucket click); stop(); and the most recent load's container()
 * and context(), for a re-render that needs no fetch (a pager click).
 */
export function selfFetchingTab({fetch, loading, renderResult, renderError}) {
    // The most recent load() call's container/context - read by listeners the tab wires
    // once, so a later locale or timezone change, or a later fetch, always uses fresh
    // values instead of whatever was current the first time the tab was shown.
    let currentContainer = null;
    let currentContext = null;
    let loaded = false;
    let stopped = true;

    function load(container, context) {
        currentContainer = container;
        currentContext = context;
        stopped = false;
        return refetch();
    }

    async function refetch() {
        // A control the tab wired outlives stop(), and can fire with nothing to fetch for.
        if (stopped) return;
        const container = currentContainer;
        const context = currentContext;

        loading?.(container, {firstLoad: !loaded});
        try {
            const result = await fetch(context);
            if (stopped || result === null) return; // left meanwhile, or superseded by a newer request
            loaded = true;
            renderResult(container, result, context);
        } catch (error) {
            if (!stopped) renderError(container, error, context);
        }
    }

    function stop() {
        stopped = true;
    }

    return {load, refetch, stop, container: () => currentContainer, context: () => currentContext};
}

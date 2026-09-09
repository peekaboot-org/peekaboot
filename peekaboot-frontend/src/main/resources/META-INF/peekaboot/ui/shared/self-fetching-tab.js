/**
 * The shell of a dashboard tab whose data comes from its own endpoint instead of the
 * shared payload main.js fetches: traces.js, lifecycle.js, the Overview tab's tile row and
 * filtered-group-tab.js's fetchData path. One place for the contract every such tab
 * follows. A render while the tab is not the visible one skips the round trip (main.js
 * renders the tab again the moment it is switched to); a response an overlapping newer
 * request has overtaken resolves to null (see api.js) and renders nothing; a rejection
 * renders the error in place of the result.
 *
 *   fetch(context)                     -> the data, or null when superseded
 *   reconcile(container, context)      -> optional; runs before the fetch, and only while
 *                                         the tab is active, to seed state from the URL
 *   loading(container, {firstLoad})    -> optional; runs before every fetch
 *   renderResult(container, result, context)
 *   renderError(container, error, context)
 *
 * The returned tab exposes render(container, data, context), the tab-module contract;
 * refetch(), for a control the tab wires itself (a bucket click); and the most recent
 * render's container() and context(), for a re-render that needs no fetch (a pager click).
 */
export function selfFetchingTab({fetch, reconcile, loading, renderResult, renderError}) {
    // The most recent render() call's container/context - read by listeners the tab wires
    // once, so a later locale or timezone change, or a later fetch, always uses fresh
    // values instead of whatever was current the first time the tab was rendered.
    let currentContainer = null;
    let currentContext = null;
    let loaded = false;

    function render(container, data, context) {
        currentContainer = container;
        currentContext = context;
        if (context.active) reconcile?.(container, context);
        refetch();
    }

    async function refetch() {
        const container = currentContainer;
        const context = currentContext;
        if (!context.active) return;

        loading?.(container, {firstLoad: !loaded});
        let result;
        try {
            result = await fetch(context);
        } catch (error) {
            renderError(container, error, context);
            return;
        }
        if (result === null) return; // superseded by a newer request

        loaded = true;
        renderResult(container, result, context);
    }

    return {render, refetch, container: () => currentContainer, context: () => currentContext};
}

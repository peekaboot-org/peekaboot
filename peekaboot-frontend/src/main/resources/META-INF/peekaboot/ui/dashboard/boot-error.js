/**
 * Reloads the dashboard once when main.js never loads, and raises the error banner when the
 * reload does not help either.
 *
 * No build step means main.js is one module script over a graph of forty-odd separate
 * fetches, and losing any one of them leaves the whole graph unevaluated: nothing hides
 * #loading, nothing raises #error, and the page sits on the spinner for good with nothing in
 * the console but a failed request. Chromium drops every request in flight, with
 * net::ERR_NETWORK_CHANGED, whenever the host's network configuration changes - a container
 * taking a veth interface up or down is enough, and so is a VPN connecting - so a page open
 * on a working machine meets this with nothing broken. The change is an instant, not a state,
 * which is why one reload fetches the whole graph again and gets it.
 *
 * A classic script outside the graph it watches, for the same reasons theme-boot.js is one: a
 * module would share the failure it reports, and an inline block is dropped by a strict
 * Content-Security-Policy. The browser fires one 'error' event at the module script element
 * for the whole graph, and only in the capture phase, since resource errors do not bubble.
 */
(function () {
    var RETRY_MARKER = 'peekaboot-dashboard-retried';

    // Read and cleared on every load, so the reload below finds itself marked and reports
    // instead of reloading again, while a load that works leaves the next one a retry of its
    // own. Storage that throws - private browsing, an embedder policy - reports straight away
    // rather than reloading on a marker that was never written.
    var alreadyRetried = false;
    try {
        alreadyRetried = sessionStorage.getItem(RETRY_MARKER) !== null;
        sessionStorage.removeItem(RETRY_MARKER);
    } catch (e) { /* storage blocked */ }

    function markRetry() {
        try {
            sessionStorage.setItem(RETRY_MARKER, '1');
            return true;
        } catch (e) {
            return false;
        }
    }

    // Never while the browser is still finishing this navigation: a reload started then
    // replaces it, which anything waiting on that navigation reads as an interrupted one.
    function reloadOnce() {
        // The browser logs the failed request but not who reacted to it, and a page that
        // reloads itself saying nothing is not something anyone can debug afterwards.
        console.warn('Peekaboot: the dashboard did not load, reloading once');
        if (document.readyState === 'complete') location.reload();
        else window.addEventListener('load', function () { location.reload(); });
    }

    function reportFailure() {
        var loading = document.getElementById('loading');
        if (loading) loading.classList.add('hidden');
        var banner = document.getElementById('error');
        if (!banner) return;
        banner.querySelector('.message').textContent =
            'The dashboard could not start - one of its scripts did not load. Reload the page.';
        banner.classList.remove('hidden');
    }

    window.addEventListener('error', function (event) {
        var script = event.target;
        if (!script || script.tagName !== 'SCRIPT' || script.type !== 'module') return;
        if (!alreadyRetried && markRetry()) reloadOnce();
        else reportFailure();
    }, true);
})();

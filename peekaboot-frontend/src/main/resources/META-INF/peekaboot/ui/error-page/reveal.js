/**
 * Opens every folded run of framework frames at once. The per-run <details> work without
 * this, so the page degrades to them when a host forbids the script.
 *
 * Shipped twice, inline and linked, because the two answer to different halves of a
 * Content-Security-Policy and a host may refuse either - the same split InlinedStylesheets
 * makes for the page's CSS. Guarded so the second copy to arrive does nothing.
 */
(function () {
    if (window.__peekabootReveal) return;
    window.__peekabootReveal = true;
    document.addEventListener('click', function (event) {
        var control = event.target.closest('.pk-error__reveal');
        if (!control) return;
        var open = control.getAttribute('aria-pressed') !== 'true';
        control.setAttribute('aria-pressed', String(open));
        control.textContent = open ? 'Hide framework frames' : 'Show full stack trace';
        document.querySelectorAll('details.pk-error__hidden').forEach(function (run) {
            run.open = open;
        });
    });
})();

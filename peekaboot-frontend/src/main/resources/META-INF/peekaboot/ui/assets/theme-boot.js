/**
 * Stamps data-theme on the dashboard document before its stylesheets apply, so a
 * dark-theme reader never sees the light palette painted first.
 *
 * A classic, non-deferred script rather than a module or an inline block: a module runs
 * after first paint, and an inline block is dropped by a host whose CSP omits
 * script-src 'unsafe-inline'. Neither can it import shared/theme.js, whose resolveTheme()
 * this mirrors - same storage key, same 'light'|'dark' validation, same OS fallback, same
 * guard around storage that private browsing or an embedder policy can make throw.
 */
(function () {
    var stored = null;
    try { stored = localStorage.getItem('peekaboot-theme'); } catch (e) { /* storage blocked */ }
    var theme = stored === 'light' || stored === 'dark'
        ? stored
        : (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    document.documentElement.setAttribute('data-theme', theme);
})();

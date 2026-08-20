const SHARED_SHEETS = ['tokens.css', 'base.css', 'components.css'];
const REVEAL_TIMEOUT_MS = 1000;

/**
 * Links the shared stylesheets plus the surface's own sheet into a shadow root.
 *
 * A linked sheet in a shadow root loads asynchronously, so the host is held at
 * visibility:hidden until the last one settles. The timeout guarantees a failed
 * or blocked stylesheet cannot leave the UI permanently invisible.
 */
export function attachSharedStyles(shadowRoot, hostElement, basePath, ownSheetHref) {
    hostElement.style.visibility = 'hidden';

    const hrefs = SHARED_SHEETS.map(name => `${basePath}/ui/assets/${name}`);
    if (ownSheetHref) hrefs.push(ownSheetHref);

    const loads = hrefs.map(href => new Promise(resolve => {
        const link = document.createElement('link');
        link.rel = 'stylesheet';
        link.href = href;
        link.addEventListener('load', resolve, {once: true});
        link.addEventListener('error', resolve, {once: true});
        shadowRoot.appendChild(link);
    }));

    const reveal = () => { hostElement.style.visibility = ''; };

    return Promise.race([
        Promise.all(loads),
        new Promise(resolve => setTimeout(resolve, REVEAL_TIMEOUT_MS))
    ]).then(reveal);
}

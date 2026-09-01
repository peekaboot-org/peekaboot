package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Exercises attachSharedStyles against a real open shadow root. This is the mechanism that
 * lets the trace-detail overlay consume the same tokens/base/components stylesheets as the
 * dashboard, instead of hardcoding a dark palette. The toolbar no longer uses it: its shadow
 * root is declarative and DevToolbarFilter carries the sheets it cannot live without inline,
 * so that the bar still renders when /peekaboot/** is behind an authorization gate.
 */
class ShadowStylesAttachmentIT extends PlaywrightTestBase {

    private Object evalShadowStyles(String script) {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html");
        return page.evaluate(script);
    }

    @Test
    void hostIsHiddenWhileSheetsLoadAndRevealedAfterwards() {
        Object result = evalShadowStyles("""
            async () => {
                const m = await import('/peekaboot/ui/shared/shadow-styles.js');
                const host = document.createElement('div');
                document.body.appendChild(host);
                const shadowRoot = host.attachShadow({mode: 'open'});
                const attachPromise = m.attachSharedStyles(shadowRoot, host, '/peekaboot', null);
                const hiddenDuringLoad = host.style.visibility;
                await attachPromise;
                return {hiddenDuringLoad, revealedAfter: host.style.visibility};
            }
            """);
        Map<?, ?> map = (Map<?, ?>) result;
        assertThat(map.get("hiddenDuringLoad")).isEqualTo("hidden");
        assertThat(map.get("revealedAfter")).isEqualTo("");
    }

    @Test
    void linksTheThreeSharedSheetsPlusTheOwnSheet() {
        Object hrefs = evalShadowStyles("""
            async () => {
                const m = await import('/peekaboot/ui/shared/shadow-styles.js');
                const host = document.createElement('div');
                document.body.appendChild(host);
                const shadowRoot = host.attachShadow({mode: 'open'});
                await m.attachSharedStyles(shadowRoot, host, '/peekaboot', '/peekaboot/ui/toolbar/toolbar.css');
                return Array.from(shadowRoot.querySelectorAll('link')).map(l => l.getAttribute('href'));
            }
            """);
        @SuppressWarnings("unchecked")
        List<String> hrefList = (List<String>) hrefs;
        assertThat(hrefList)
                .containsExactlyInAnyOrder(
                        "/peekaboot/ui/assets/tokens.css",
                        "/peekaboot/ui/assets/base.css",
                        "/peekaboot/ui/assets/components.css",
                        "/peekaboot/ui/toolbar/toolbar.css");
    }

    @Test
    void omitsTheOwnSheetLinkWhenNoneIsGiven() {
        Object count = evalShadowStyles("""
            async () => {
                const m = await import('/peekaboot/ui/shared/shadow-styles.js');
                const host = document.createElement('div');
                document.body.appendChild(host);
                const shadowRoot = host.attachShadow({mode: 'open'});
                await m.attachSharedStyles(shadowRoot, host, '/peekaboot', null);
                return shadowRoot.querySelectorAll('link').length;
            }
            """);
        assertThat(count).isEqualTo(3);
    }

    /**
     * A 404 own-sheet must resolve promptly via the link's error listener, not hang until the
     * 1000ms timeout. A broken implementation that only listens for 'load' would take the full
     * timeout here instead.
     */
    @Test
    void aFailedSheetStillResolvesInsteadOfHangingUntilTheTimeout() {
        Object elapsedMs = evalShadowStyles("""
            async () => {
                const m = await import('/peekaboot/ui/shared/shadow-styles.js');
                const host = document.createElement('div');
                document.body.appendChild(host);
                const shadowRoot = host.attachShadow({mode: 'open'});
                const start = performance.now();
                await m.attachSharedStyles(shadowRoot, host, '/peekaboot', '/does/not/exist.css');
                return performance.now() - start;
            }
            """);
        assertThat(((Number) elapsedMs).doubleValue()).isLessThan(900);
    }

    /**
     * Even when every sheet is missing, the host must still be revealed by the 1000ms
     * timeout race rather than staying hidden forever.
     */
    @Test
    void revealsWithinTheTimeoutEvenWhenAllSheetsAreMissing() {
        Object visibilityAfter = evalShadowStyles("""
            async () => {
                const m = await import('/peekaboot/ui/shared/shadow-styles.js');
                const host = document.createElement('div');
                document.body.appendChild(host);
                const shadowRoot = host.attachShadow({mode: 'open'});
                await m.attachSharedStyles(shadowRoot, host, '/does/not/exist', '/still/missing.css');
                return host.style.visibility;
            }
            """);
        assertThat(visibilityAfter).isEqualTo("");
    }
}

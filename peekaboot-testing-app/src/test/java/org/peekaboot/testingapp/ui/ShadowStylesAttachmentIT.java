package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Route;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Exercises attachSharedStyles against a real open shadow root. This is the mechanism that
 * lets the trace-detail overlay consume the same tokens/base/components stylesheets as the
 * dashboard, instead of hardcoding a dark palette. The toolbar does not use it: its shadow
 * root is declarative and DevToolbarFilter carries the sheets it cannot live without inline,
 * so that the bar still renders when /peekaboot/** is behind an authorization gate.
 */
class ShadowStylesAttachmentIT extends PlaywrightTestBase {

    /** Runs {@code body}, a function body over the module {@code m}, against a fresh host on the blank fixture. */
    private Object evalShadowStyles(String body) {
        return importModule("shared/shadow-styles.js", "(async () => {" + body + "})()");
    }

    @Test
    void hostIsHiddenWhileSheetsLoadAndRevealedAfterwards() {
        Object result = evalShadowStyles("""
                const host = document.createElement('div');
                document.body.appendChild(host);
                const shadowRoot = host.attachShadow({mode: 'open'});
                const attachPromise = m.attachSharedStyles(shadowRoot, host, '/peekaboot', null);
                const hiddenDuringLoad = host.style.visibility;
                await attachPromise;
                return {hiddenDuringLoad, revealedAfter: host.style.visibility};
            """);
        Map<?, ?> map = (Map<?, ?>) result;
        assertThat(map.get("hiddenDuringLoad")).isEqualTo("hidden");
        assertThat(map.get("revealedAfter")).isEqualTo("");
    }

    @Test
    void linksTheThreeSharedSheetsPlusTheOwnSheet() {
        Object hrefs = evalShadowStyles("""
                const host = document.createElement('div');
                document.body.appendChild(host);
                const shadowRoot = host.attachShadow({mode: 'open'});
                await m.attachSharedStyles(shadowRoot, host, '/peekaboot', '/peekaboot/ui/toolbar/toolbar.css');
                return Array.from(shadowRoot.querySelectorAll('link')).map(l => l.getAttribute('href'));
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
                const host = document.createElement('div');
                document.body.appendChild(host);
                const shadowRoot = host.attachShadow({mode: 'open'});
                await m.attachSharedStyles(shadowRoot, host, '/peekaboot', null);
                return shadowRoot.querySelectorAll('link').length;
            """);
        assertThat(count).isEqualTo(3);
    }

    /**
     * A sheet that never settles cannot keep the host hidden. The three shared sheets are
     * parked (their requests intercepted and held, so neither load nor error ever fires for
     * them) and the surface's own sheet is a 404; the promise still settles and the host is
     * revealed while the parked requests are still held, which no load could have done. No
     * elapsed-time assertion: whether the own sheet's error listener or the timeout settled
     * the race is not the contract, the reveal is.
     */
    @Test
    void aBlockedSheetCannotKeepTheHostHidden() {
        List<Route> parked = new CopyOnWriteArrayList<>();
        page.route("**/peekaboot/ui/assets/*.css", parked::add);

        Object visibilityAfter = evalShadowStyles("""
                const host = document.createElement('div');
                document.body.appendChild(host);
                const shadowRoot = host.attachShadow({mode: 'open'});
                await m.attachSharedStyles(shadowRoot, host, '/peekaboot', '/does/not/exist.css');
                return host.style.visibility;
            """);

        assertThat(visibilityAfter).isEqualTo("");
        assertThat(parked).as("the shared sheets were held for the whole wait").hasSize(3);
        parked.forEach(Route::abort);
    }

    /**
     * Even when every sheet is missing, the host must still be revealed by the 1000ms
     * timeout race rather than staying hidden forever.
     */
    @Test
    void revealsWithinTheTimeoutEvenWhenAllSheetsAreMissing() {
        Object visibilityAfter = evalShadowStyles("""
                const host = document.createElement('div');
                document.body.appendChild(host);
                const shadowRoot = host.attachShadow({mode: 'open'});
                await m.attachSharedStyles(shadowRoot, host, '/does/not/exist', '/still/missing.css');
                return host.style.visibility;
            """);
        assertThat(visibilityAfter).isEqualTo("");
    }
}

package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Every surface renders in the bundled Geist, served from the jar and never from a CDN.
 *
 * <p>The toolbar and the overlay each get their own test because an {@code @font-face} rule
 * cannot reach them. CSS Shadow Parts scopes font family names to the tree that declares
 * them, with upward fallback only: a document-level {@code @font-face} is visible inside a
 * shadow tree, one declared inside a shadow root is ignored. Peekaboot contributes no
 * document-level CSS to a host page, so tokens.css styles the dashboard and would silently
 * leave both injected surfaces on system fonts - a regression that looks like nothing at all
 * unless each shadow-rooted surface is asserted separately.
 *
 * <p>Each probe asserts the family is really being used, not merely named: it measures the
 * same string with the family and against the default monospace, since a computed
 * {@code font-family} reads back the CSS list whether or not any of it resolved.
 */
class BundledFontIT extends PlaywrightTestBase {

    private static final String SANS = "Geist";
    private static final String MONO = "Geist Mono";

    /** Where the woff2 files are served from; a request to anywhere else is a CDN and fails the suite. */
    private static final String FONT_PATH = "/peekaboot/ui/vendor/geist/";

    /**
     * Loads {@code family}, then reports whether it is loaded, whether it renders differently
     * from the default monospace (proof the glyphs are the bundled face rather than a
     * fallback), and what {@code selector} computes its family to. {@code root} is the
     * document or a shadow root; the probe is appended into that same tree, so a shadow
     * surface is measured in the scope it actually renders in.
     */
    private static final String FONT_PROBE = """
            async (root, args) => {
                const [family, selector] = args;
                const styled = root.querySelector(selector);
                const size = getComputedStyle(styled).fontSize;
                const shorthand = size + ' "' + family + '"';
                await document.fonts.load(shorthand);
                const probe = document.createElement('span');
                probe.textContent = 'Peekaboot 0123456789';
                probe.style.cssText =
                    'position:absolute;visibility:hidden;white-space:pre;font-size:' + size;
                (root.body ?? root).appendChild(probe);
                probe.style.fontFamily = '"' + family + '", monospace';
                const withFamily = probe.getBoundingClientRect().width;
                probe.style.fontFamily = 'monospace';
                const withoutFamily = probe.getBoundingClientRect().width;
                probe.remove();
                return {
                    loaded: document.fonts.check(shorthand),
                    rendersDifferentlyFromTheFallback: withFamily > 0 && withFamily !== withoutFamily,
                    declared: getComputedStyle(styled).fontFamily
                };
            }
            """;

    @Test
    void theDashboardRendersInTheBundledSans() {
        openDashboard();

        assertRendersIn(probeDocument(SANS, "body"), SANS);
    }

    @Test
    void theDashboardRendersMonospaceTextInTheBundledMono() {
        openDashboard();

        Map<String, Object> probe = probeDocument(MONO, "body");
        assertThat(probe.get("loaded")).as("%s is loaded from the jar", MONO).isEqualTo(true);
        assertThat(probe.get("rendersDifferentlyFromTheFallback"))
                .as("%s renders its own glyphs, not the fallback's", MONO)
                .isEqualTo(true);
        assertThat(cssVar(":root", "--pk-font-mono"))
                .as("the mono token leads with the bundled family")
                .startsWith(MONO);
    }

    /** The bar is shadow-rooted, so tokens.css's @font-face never reaches it. */
    @Test
    void theToolbarRendersInTheBundledSans() {
        openPersonsPage();
        toolbar.traceId();

        assertRendersIn(toolbar.evaluate(FONT_PROBE, List.of(SANS, ".pk-toolbar")), SANS);
    }

    /** The overlay is shadow-rooted too, and opens over host pages the dashboard's CSS never touched. */
    @Test
    void theOverlayRendersInTheBundledSans() {
        openPersonsPage();
        toolbar.openOverlay();

        assertRendersIn(overlay.evaluate(FONT_PROBE, List.of(SANS, ".pk-overlay")), SANS);
    }

    /**
     * The faces come from the jar over the app's own origin. Asserted on the responses the
     * dashboard actually made, so a rule that named a CDN - or a path typo that fell back to
     * a system font without a console error - fails here rather than passing quietly.
     */
    @Test
    void theFontFilesAreServedFromTheJar() {
        captureBrowserSignals();
        Map<String, Integer> statuses = new ConcurrentHashMap<>();
        List<String> failures = new CopyOnWriteArrayList<>();
        page.onResponse(response -> {
            if (response.url().contains(FONT_PATH)) {
                statuses.put(response.url(), response.status());
            }
        });
        page.onRequestFailed(request -> {
            if (request.url().contains(FONT_PATH)) {
                failures.add(request.url() + " -> " + request.failure());
            }
        });

        openDashboard();

        assertThat(failures).as("no font request failed").isEmpty();
        assertThat(statuses)
                .as("the dashboard actually requested the bundled faces")
                .isNotEmpty();
        assertThat(statuses)
                .allSatisfy((url, status) -> assertThat(status).as(url).isEqualTo(200));
        assertThat(statuses.keySet()).allSatisfy(url -> assertThat(url).startsWith(baseUrl + FONT_PATH));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> probeDocument(String family, String selector) {
        return (Map<String, Object>)
                page.evaluate("args => (" + FONT_PROBE + ")(document, args)", List.of(family, selector));
    }

    @SuppressWarnings("unchecked")
    private static void assertRendersIn(Object probeResult, String family) {
        Map<String, Object> probe = (Map<String, Object>) probeResult;
        assertThat(probe.get("loaded")).as("%s is loaded from the jar", family).isEqualTo(true);
        assertThat(probe.get("rendersDifferentlyFromTheFallback"))
                .as("%s renders its own glyphs, not a system fallback's", family)
                .isEqualTo(true);
        assertThat((String) probe.get("declared"))
                .as("the surface asks for %s ahead of the system stack", family)
                .startsWith(family);
    }
}

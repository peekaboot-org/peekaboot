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
 * cannot reach them. CSS scopes font family names to the tree that declares them, with
 * upward fallback only: a document-level {@code @font-face} is visible inside a shadow tree,
 * one declared inside a shadow root is ignored. Peekaboot contributes no document-level CSS
 * to a host page, so tokens.css styles the dashboard and would silently leave both injected
 * surfaces on system fonts - a regression that looks like nothing at all unless each
 * shadow-rooted surface is asserted separately.
 *
 * <p>Each probe measures text the surface really renders, not a span of its own: the same
 * node with the family the cascade gives it, then again with the bundled family struck out
 * of that list. A face that loaded but never reached the page measures the same both ways
 * and fails here, which is the case {@code font-display: optional} licences outright.
 */
class BundledFontIT extends PlaywrightTestBase {

    /** Where the woff2 files are served from; a request to anywhere else is a CDN and fails the suite. */
    private static final String FONT_PATH = "/peekaboot/ui/vendor/geist/";

    @Test
    void theDashboardRendersInTheBundledSans() {
        openDashboard();

        assertRendersIn(probeDocument(Fonts.SANS, ".pk-header h1"), Fonts.SANS);
    }

    @Test
    void theDashboardRendersMonospaceTextInTheBundledMono() {
        openDashboard();

        assertRendersIn(probeDocument(Fonts.MONO, "#build-info .pk-kv__key"), Fonts.MONO);
    }

    /** The bar is shadow-rooted, so tokens.css's @font-face never reaches it. */
    @Test
    void theToolbarRendersInTheBundledSans() {
        openPersonsPage();
        toolbar.traceId();

        assertRendersIn(toolbar.evaluate(Fonts.PROBE, List.of(Fonts.SANS, ".pk-toolbar__path")), Fonts.SANS);
    }

    /** The overlay is shadow-rooted too, and opens over host pages the dashboard's CSS never touched. */
    @Test
    void theOverlayRendersInTheBundledSans() {
        openPersonsPage();
        toolbar.openOverlay();

        assertRendersIn(overlay.evaluate(Fonts.PROBE, List.of(Fonts.SANS, ".pk-overlay__title-method")), Fonts.SANS);
    }

    /** A span row's query toggle is a button, which takes the engine's own button font unless told to inherit. */
    @Test
    void aSpanRowsToggleButtonRendersInTheBundledSans() {
        openPersonsPage();
        toolbar.openOverlay();
        overlay.waitFor(".pk-span-query-toggle");

        assertRendersIn(overlay.evaluate(Fonts.PROBE, List.of(Fonts.SANS, ".pk-span-query-toggle")), Fonts.SANS);
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

    private Map<String, Object> probeDocument(String family, String selector) {
        return asProbe(page.evaluate("args => (" + Fonts.PROBE + ")(document, args)", List.of(family, selector)));
    }

    private static void assertRendersIn(Object probeResult, String family) {
        Map<String, Object> probe = asProbe(probeResult);
        double painted = ((Number) probe.get("paintedWidth")).doubleValue();
        double fallback = ((Number) probe.get("fallbackWidth")).doubleValue();

        assertThat(probe.get("loadedFace"))
                .as("a face named %s is registered on the document and has loaded", family)
                .isEqualTo(true);
        assertThat(probe.get("firstDeclared"))
                .as("the surface asks for %s ahead of the system stack", family)
                .isEqualTo(family);
        assertThat(painted).as("the measured node renders some text").isGreaterThan(0.0);
        assertThat(painted)
                .as(
                        "the node's own text measures differently without %s in its stack; the same "
                                + "width both ways means the page painted the fallback",
                        family)
                .isNotEqualTo(fallback);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asProbe(Object probeResult) {
        return (Map<String, Object>) probeResult;
    }
}

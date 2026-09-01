package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.options.BoundingBox;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The markers are canvas drawing, so the assertions hang off the readback the layer
 * publishes onto the panel (data-marker-count/-x) and off the tooltip, which is the
 * only part of the feature that is a DOM node - the same approach the zoom tests take.
 *
 * <p>{@code PlaywrightTestBase}'s {@code @SpringBootTest} configuration is shared
 * verbatim by every UI test class, so Spring caches and reuses one application context
 * across the whole suite - this class would otherwise run against an application that
 * may have started minutes earlier, aging its start marker out of even level 1's 150s
 * window. The {@code peekaboot.enabled=true} below restates the default every neighbour
 * already gets from application-test.yml - it changes nothing at runtime - but it makes
 * this class's {@code @TestPropertySource} differ from every other class's, which is
 * part of Spring's context-cache key (see {@code MergedContextConfiguration}). That
 * earns this class its own, separately cached context, booted fresh the first time this
 * class runs, without an {@code @DirtiesContext} that would evict the shared context out
 * from under whatever other class the parallel suite is running at the same moment. Do
 * not delete this property as dead weight - doing so folds this class back into the
 * shared context and reintroduces the aged-out marker failure.
 *
 * <p>A fresh boot alone is not enough on its own for a multi-test class, though: level
 * 0's window is only 22.5s (250ms x 90 samples, see application-test.yml), which the
 * class's own three tests can burn through before the last one runs. Every test
 * switches to level 1 (1.5s x 100 samples = 150s) before reading anything off the
 * chart, for headroom the class can't outrun.
 */
@TestPropertySource(properties = "peekaboot.enabled=true")
class InsightsMarkersIT extends PlaywrightTestBase {

    private static final String PANEL = "#insights-panels .pk-insight-panel";

    // Charts fail invisibly (a swallowed script error leaves the page mute in test
    // output), so every browser-side signal is collected and dumped at teardown -
    // the only way to see what headless Chromium actually did on a CI runner.
    private final List<String> browserLog = new CopyOnWriteArrayList<>();

    @BeforeEach
    void captureBrowserConsole() {
        page.onConsoleMessage(msg -> browserLog.add("console." + msg.type() + ": " + msg.text()));
        page.onPageError(error -> browserLog.add("pageerror: " + error));
        page.onRequestFailed(request -> browserLog.add("requestfailed: " + request.url() + " -> " + request.failure()));
        page.onResponse(response -> {
            if (response.status() >= 400) {
                browserLog.add("http" + response.status() + ": " + response.url());
            }
        });
    }

    @AfterEach
    void dumpBrowserLog() {
        if (!browserLog.isEmpty()) {
            System.out.println("[browser] " + String.join("\n[browser] ", new ArrayList<>(browserLog)));
        }
    }

    private void openInsights() {
        openDashboard();
        page.click("#insights-tab-btn");
        page.waitForSelector(PANEL + " .u-over");
    }

    /**
     * Switches every panel off level 0's 22.5s window onto level 1's 150s one, and
     * waits out the level switch's chart rebuild (a level change destroys and
     * recreates the chart, see insights.js's rebuildChart) before anything is
     * measured off it - re-querying {@code .u-over} rather than reusing a handle
     * from before the switch, since the rebuild replaces it with a new element.
     */
    private void useALevelWithHeadroomForTheStartMarker() {
        page.click("#insights-level .pk-insight-level[data-level='1']");
        page.waitForSelector(PANEL + " .u-over");
        page.waitForFunction("() => Number(document.querySelector('" + PANEL + "')?.dataset.markerCount ?? 0) > 0");
    }

    @Test
    void theRunningApplicationsOwnStartIsMarkedOnTheChart() {
        openInsights();
        useALevelWithHeadroomForTheStartMarker();

        assertThat(page.locator(PANEL).first().getAttribute("data-marker-x")).isNotBlank();
    }

    @Test
    void hoveringAMarkerNamesTheBuildThatStarted() {
        openInsights();
        useALevelWithHeadroomForTheStartMarker();

        double markerX = Double.parseDouble(
                page.locator(PANEL).first().getAttribute("data-marker-x").split(",")[0]);
        // A marker pinned to the plot's left edge publishes x=0 (or near it); hovering
        // exactly there can land on the boundary uPlot treats as "outside the plot"
        // (setCursor hides the tooltip once cursorLeft < 0), so nudge a couple of CSS
        // pixels into the plot area rather than sitting right on the edge.
        markerX = Math.max(markerX, 2);
        BoundingBox plot = page.locator(PANEL + " .u-over").first().boundingBox();
        page.mouse().move(plot.x + markerX, plot.y + plot.height / 2.0);

        page.waitForSelector(PANEL + " .pk-insight-marker-tip:not([hidden])");
        assertThat(page.locator(PANEL + " .pk-insight-marker-tip").first().textContent())
                .contains("Started")
                .contains("Version");
    }

    @Test
    void theRestartsToggleClearsEveryMarker() {
        openInsights();
        useALevelWithHeadroomForTheStartMarker();

        page.uncheck("#insights-markers");

        page.waitForFunction("() => document.querySelector('" + PANEL + "')?.dataset.markerCount === '0'");
    }
}

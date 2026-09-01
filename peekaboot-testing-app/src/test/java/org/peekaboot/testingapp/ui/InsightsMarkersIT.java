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
 * <p>Level 1 is widened to an hour (1.5s x 2400 samples) so the application's start
 * marker cannot age out of the chart between this context's boot and the last test here,
 * however long the concurrent suite takes around it. A list property is bound from the
 * first property source that holds any element of it, so the override restates every
 * level of application-test.yml rather than just the widened size.
 */
@TestPropertySource(
        properties = {
            "peekaboot.insights.levels[0].interval=250ms",
            "peekaboot.insights.levels[0].size=90",
            "peekaboot.insights.levels[1].interval=1500ms",
            "peekaboot.insights.levels[1].size=2400",
            "peekaboot.insights.levels[2].interval=9s",
            "peekaboot.insights.levels[2].size=50"
        })
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
     * Switches every panel off level 0's 22.5s window onto level 1's hour-long one, and
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

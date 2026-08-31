package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.options.BoundingBox;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The markers are canvas drawing, so the assertions hang off the readback the layer
 * publishes onto the panel (data-marker-count/-x) and off the tooltip, which is the
 * only part of the feature that is a DOM node - the same approach the zoom tests take.
 */
class InsightsMarkersTest extends PlaywrightTestBase {

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

    /** A subclass @AfterEach runs before the base class's, so the stream is gone by teardown. */
    @AfterEach
    void closeInsightsStream() {
        if (!browserLog.isEmpty()) {
            System.out.println("[browser] " + String.join("\n[browser] ", new ArrayList<>(browserLog)));
        }
        closeLiveStreams();
    }

    private void openInsights() {
        openDashboard();
        page.click("#insights-tab-btn");
        page.waitForSelector(PANEL + " .u-over");
    }

    @Test
    void theRunningApplicationsOwnStartIsMarkedOnTheChart() {
        openInsights();

        page.waitForFunction("() => Number(document.querySelector('" + PANEL + "')?.dataset.markerCount ?? 0) > 0");

        assertThat(page.locator(PANEL).first().getAttribute("data-marker-x")).isNotBlank();
    }

    @Test
    void hoveringAMarkerNamesTheBuildThatStarted() {
        openInsights();
        page.waitForFunction("() => Number(document.querySelector('" + PANEL + "')?.dataset.markerCount ?? 0) > 0");

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
        page.waitForFunction("() => Number(document.querySelector('" + PANEL + "')?.dataset.markerCount ?? 0) > 0");

        page.uncheck("#insights-markers");

        page.waitForFunction("() => document.querySelector('" + PANEL + "')?.dataset.markerCount === '0'");
    }
}

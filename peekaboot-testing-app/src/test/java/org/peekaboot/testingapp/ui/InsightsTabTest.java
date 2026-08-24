package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InsightsTabTest extends PlaywrightTestBase {

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
        page.waitForSelector("#insights-panels .pk-insight-panel");
    }

    @Test
    void insightsTabIsAvailableAndOrdersPanelsByConfig() {
        openInsights();

        Object panelIds = page.evaluate("() => [...document.querySelectorAll('#insights-panels .pk-insight-panel')]"
                + ".map(el => el.dataset.panelId)");
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) panelIds;

        // must exactly match the server config order - first four suffice as a strong signal
        assertThat(ids).startsWith("cpu", "load", "heap", "nonheap");
        assertThat(ids).doesNotContain("thread-states");
    }

    @Test
    void tilesRenderWithFormattedValues() {
        openInsights();
        page.waitForSelector("#insights-tiles .pk-insight-tile[data-tile-id='uptime']");

        String uptime =
                page.textContent("#insights-tiles .pk-insight-tile[data-tile-id='uptime'] .pk-insight-tile-value");

        assertThat(uptime).isNotEqualTo("-"); // live tile resolves in a real app
    }

    @Test
    void levelSelectorListsConfiguredLevels() {
        openInsights();

        Object options = page.evaluate(
                "() => [...document.querySelectorAll('#insights-level option')].map(el => el.textContent)");
        @SuppressWarnings("unchecked")
        List<String> labels = (List<String>) options;

        assertThat(labels).hasSize(3);
    }

    @Test
    void chartsRenderOnlyInViewportAndLazilyBelowFold() {
        page.setViewportSize(1000, 600);
        openInsights();
        page.waitForSelector("#insights-panels .pk-insight-panel[data-panel-id='cpu'] canvas");

        // the last panel starts below the fold in this viewport -> no chart instantiated yet
        String lastPanel = "#insights-panels .pk-insight-panel[data-panel-id='log-events']";
        assertThat(page.locator(lastPanel + " canvas").count()).isZero();

        page.locator(lastPanel).scrollIntoViewIfNeeded();
        page.waitForSelector(lastPanel + " canvas");
    }

    /**
     * A panel whose meter this app does not have (insights-test-panels.yml adds one on
     * purpose) says so, instead of drawing axes and a legend over nothing but nulls.
     */
    @Test
    void panelWithoutAnyDataSaysSoInsteadOfCharting() {
        openInsights();
        String absent = "#insights-panels .pk-insight-panel[data-panel-id='absent-subsystem']";
        page.locator(absent).scrollIntoViewIfNeeded();

        page.waitForSelector(absent + ".pk-insight-panel--empty .pk-insight-empty");
        assertThat(page.textContent(absent + " .pk-insight-empty")).isEqualTo("No data");
        assertThat(page.locator(absent + " canvas").count()).isZero();

        // ...while the panels whose meters do exist are charted as usual
        page.waitForSelector("#insights-panels .pk-insight-panel[data-panel-id='cpu'] canvas");
    }

    @Test
    void tickPushBlinksTileValue() {
        openInsights();
        String value = "#insights-tiles [data-tile-id='uptime'] .pk-insight-tile-value";
        String before = page.textContent(value);

        // The changed value and the blink have to be observed together: .pk-blink is only
        // added for a value that actually changed, but it outlives its animation, so
        // finding it on its own says nothing about any tick after this read. The test
        // profile ticks every 250ms; the budget is deliberately generous, since what is
        // under test is that a pushed tick reaches the DOM, not how fast a loaded CI host
        // gets it there.
        page.waitForFunction(
                "([selector, previous]) => {"
                        + "  const element = document.querySelector(selector);"
                        + "  return !!element && element.textContent !== previous"
                        + "      && element.classList.contains('pk-blink');"
                        + "}",
                List.of(value, before),
                new Page.WaitForFunctionOptions().setTimeout(15000));

        assertThat(page.textContent(value)).as("uptime only ever grows").isNotEqualTo(before);
    }

    @Test
    void switchingGlobalLevelRefetchesDataAndRebuildsCharts() {
        openInsights();
        page.waitForSelector("#insights-panels .pk-insight-panel[data-panel-id='cpu'] canvas");

        Request request =
                page.waitForRequest("**/api/insights/data?level=1", () -> page.selectOption("#insights-level", "1"));

        assertThat(request.url()).contains("level=1");
        // the rebuilt chart (min/max bands + avg) must come back up on the new level
        page.waitForSelector("#insights-panels .pk-insight-panel[data-panel-id='cpu'] canvas");
    }

    @Test
    void rapidLevelSwitchingLeavesEveryPanelCharted() {
        openInsights();
        page.waitForSelector("#insights-panels .pk-insight-panel[data-panel-id='cpu'] canvas");

        // both switches must land inside the first rebuild's data fetch - dispatched in
        // one task, since two selectOption round trips can straddle it instead
        page.evaluate("() => {"
                + "const select = document.querySelector('#insights-level');"
                + "for (const level of ['1', '0']) {"
                + "  select.value = level;"
                + "  select.dispatchEvent(new Event('change'));"
                + "}}");

        page.waitForSelector("#insights-panels .pk-insight-panel[data-panel-id='cpu'] canvas");
    }
}

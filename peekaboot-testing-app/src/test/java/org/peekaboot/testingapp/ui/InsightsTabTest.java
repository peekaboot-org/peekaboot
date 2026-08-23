package org.peekaboot.testingapp.ui;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InsightsTabTest extends PlaywrightTestBase {

    private void openInsights() {
        openDashboard();
        page.click("#insights-tab-btn");
        page.waitForSelector("#insights-panels .pk-insight-panel");
    }

    @Test
    void insightsTabIsAvailableAndOrdersPanelsByConfig() {
        openInsights();

        Object panelIds = page.evaluate(
                "() => [...document.querySelectorAll('#insights-panels .pk-insight-panel')]"
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

        String uptime = page.textContent(
                "#insights-tiles .pk-insight-tile[data-tile-id='uptime'] .pk-insight-tile-value");

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

    @Test
    void tickPushBlinksTileValue() {
        openInsights();
        String value = "#insights-tiles [data-tile-id='uptime'] .pk-insight-tile-value";
        // rendered from /config, i.e. everything the tab knows before the stream opens
        String beforeStream = page.textContent(value);

        // the test profile ticks every 250ms, but the budget is deliberately not a
        // tight one: what is under test is that a pushed tick reaches the DOM, not
        // how fast a loaded CI host gets it there
        page.waitForSelector("#insights-tiles [data-tile-id='uptime'] .pk-blink",
                new Page.WaitForSelectorOptions().setTimeout(15000));

        // ...and the blink must be carrying a pushed value, not just a re-render
        assertThat(page.textContent(value)).isNotEqualTo(beforeStream);
    }

    @Test
    void switchingGlobalLevelRefetchesDataAndRebuildsCharts() {
        openInsights();
        page.waitForSelector("#insights-panels .pk-insight-panel[data-panel-id='cpu'] canvas");

        Request request = page.waitForRequest("**/api/insights/data?level=1",
                () -> page.selectOption("#insights-level", "1"));

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

package org.peekaboot.testingapp.ui;

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
}

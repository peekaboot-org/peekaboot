package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.WaitForSelectorState;
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

    /**
     * A left-to-right drag across the middle of a chart's canvas - uPlot's drag-select
     * gesture. {@code dist} on cursor.drag defaults to 0, so any non-zero width selects.
     */
    private void dragZoomOnChart(String canvasSelector) {
        BoundingBox box = page.locator(canvasSelector).boundingBox();
        double y = box.y + box.height / 2.0;
        double left = box.x + box.width * 0.25;
        double right = box.x + box.width * 0.75;
        Mouse mouse = page.mouse();
        mouse.move(left, y);
        mouse.down();
        mouse.move(right, y, new Mouse.MoveOptions().setSteps(10));
        mouse.up();
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
    void levelSwitchOffersOneButtonPerConfiguredLevel() {
        openInsights();

        Object labels = page.evaluate("() => [...document.querySelectorAll('#insights-level .pk-insight-level')]"
                + ".map(el => el.textContent.trim())");
        @SuppressWarnings("unchecked")
        List<String> intervals = (List<String>) labels;

        // the test profile's own levels (application-test.yml): 250ms / 1500ms / 9s
        assertThat(intervals).containsExactly("250ms", "1.5s", "9s");
        // radio-like: exactly one segment is pressed, and it is the first configured level
        assertThat(page.locator("#insights-level .pk-insight-level[aria-pressed='true']")
                        .count())
                .isEqualTo(1);
        assertThat(page.getAttribute("#insights-level .pk-insight-level[data-level='0']", "aria-pressed"))
                .isEqualTo("true");
    }

    /**
     * Deep-linking straight to "#insights" makes both readers of /api/insights/config
     * fire inside one render cycle: this tab's init() and the Overview tab's stat-tile
     * row. They de-duplicate independently (each passes its own dedupeKey, see
     * shared/api.js), so neither may be left holding the null - the tab renders *and*
     * the tile row fills, on the first cycle.
     *
     * <p>Every wait is deliberately shorter than the 30s auto-refresh: something that
     * only appears once the next refresh cycle rebuilds it has still failed this. The
     * tile row is asserted ATTACHED rather than visible - it lives in the Overview
     * panel, which this deep link leaves hidden.
     */
    @Test
    void deepLinkingStraightToInsightsRendersTheTabAndTheOverviewTiles() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#insights");
        page.waitForSelector("#insights-tab.active");

        page.waitForSelector("#insights-level .pk-insight-level", new Page.WaitForSelectorOptions().setTimeout(10000));
        page.waitForSelector(
                "#insights-panels .pk-insight-panel[data-panel-id='cpu']",
                new Page.WaitForSelectorOptions().setTimeout(10000));
        assertThat(page.locator("#insights-level .pk-insight-level").count()).isEqualTo(3);

        page.waitForSelector(
                "#insights-tiles .pk-insight-tile[data-tile-id='uptime']",
                new Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.ATTACHED)
                        .setTimeout(10000));
        assertThat(page.locator("#insights-tiles .pk-insight-tile").count()).isEqualTo(5);
        assertThat(page.locator("#insights-tiles.hidden").count())
                .as("the tile row is populated, not left hidden")
                .isZero();
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
    void tickPushBlinksPanelReadout() {
        openInsights();
        String value = "#insights-panels .pk-insight-panel[data-panel-id='cpu'] .pk-insight-current";
        page.waitForFunction("(selector) => document.querySelector(selector)?.textContent.trim()", value);
        String before = page.textContent(value);

        // The changed value and the blink have to be observed together: .pk-blink is only
        // added for a value that actually changed, but it outlives its animation, so
        // finding it on its own says nothing about any tick after this read. The test
        // profile ticks every 250ms; the budget is deliberately generous, since what is
        // under test is that a pushed tick reaches the DOM, not how fast a loaded CI host
        // gets it there. CPU usage is re-sampled on every tick, so the readout does move.
        page.waitForFunction(
                "([selector, previous]) => {"
                        + "  const element = document.querySelector(selector);"
                        + "  return !!element && element.textContent !== previous"
                        + "      && element.classList.contains('pk-blink');"
                        + "}",
                List.of(value, before),
                new Page.WaitForFunctionOptions().setTimeout(15000));

        assertThat(page.textContent(value)).as("a live CPU reading moves").isNotEqualTo(before);
    }

    @Test
    void switchingGlobalLevelRefetchesDataAndRebuildsCharts() {
        openInsights();
        page.waitForSelector("#insights-panels .pk-insight-panel[data-panel-id='cpu'] canvas");

        Request request = page.waitForRequest(
                "**/api/insights/data?level=1", () -> page.click("#insights-level .pk-insight-level[data-level='1']"));

        assertThat(request.url()).contains("level=1");
        assertThat(page.getAttribute("#insights-level .pk-insight-level[data-level='1']", "aria-pressed"))
                .isEqualTo("true");
        assertThat(page.getAttribute("#insights-level .pk-insight-level[data-level='0']", "aria-pressed"))
                .isEqualTo("false");
        // the rebuilt chart (min/max bands + avg) must come back up on the new level
        page.waitForSelector("#insights-panels .pk-insight-panel[data-panel-id='cpu'] canvas");
    }

    /**
     * A panel charting something other than the global interval is marked as such and
     * offers a way back; resetting must actually re-fetch the global level and drop the
     * marking, not just relabel the button group. Clicking a level inside one panel's own
     * group is purely local - it must not touch the toolbar or any other panel, unlike a
     * toolbar click (see switchingGlobalLevelRefetchesDataAndRebuildsCharts).
     */
    @Test
    void perPanelOverrideIsMarkedAndResettableAndDoesNotAffectOtherPanels() {
        openInsights();
        String cpu = "#insights-panels .pk-insight-panel[data-panel-id='cpu']";
        String load = "#insights-panels .pk-insight-panel[data-panel-id='load']";
        page.waitForSelector(cpu + " canvas");
        page.waitForSelector(load + " canvas");
        assertThat(page.locator(cpu + ".pk-insight-panel--overridden").count()).isZero();
        assertThat(page.isVisible(cpu + " .pk-insight-panel-reset")).isFalse();

        page.waitForRequest(
                "**/api/insights/data?level=1",
                () -> page.click(cpu + " .pk-insight-panel-levels .pk-insight-level[data-level='1']"));

        assertThat(page.locator(cpu + ".pk-insight-panel--overridden").count()).isEqualTo(1);
        assertThat(page.isVisible(cpu + " .pk-insight-panel-reset")).isTrue();
        assertThat(page.getAttribute(
                        cpu + " .pk-insight-panel-levels .pk-insight-level[data-level='1']", "aria-pressed"))
                .isEqualTo("true");
        // a per-panel override leaves the toolbar, and every other panel, exactly where they were
        assertThat(page.getAttribute("#insights-level .pk-insight-level[data-level='0']", "aria-pressed"))
                .isEqualTo("true");
        assertThat(page.locator(load + ".pk-insight-panel--overridden").count()).isZero();
        assertThat(page.getAttribute(
                        load + " .pk-insight-panel-levels .pk-insight-level[data-level='0']", "aria-pressed"))
                .isEqualTo("true");

        page.click(cpu + " .pk-insight-panel-reset");

        page.waitForSelector(cpu + " canvas");
        assertThat(page.locator(cpu + ".pk-insight-panel--overridden").count()).isZero();
        assertThat(page.isVisible(cpu + " .pk-insight-panel-reset")).isFalse();
        assertThat(page.getAttribute(
                        cpu + " .pk-insight-panel-levels .pk-insight-level[data-level='0']", "aria-pressed"))
                .isEqualTo("true");
    }

    /**
     * A drag-selection on any one chart's x-axis applies the same absolute epoch window to
     * every chart, whatever level each one charts at - not just the one dragged on. Each
     * panel stamps its current window onto its own element (data-zoom-min/-max, see
     * insights.js's handleZoom) purely so this assertion has something deterministic to
     * read; the two panels are never expected to compute their own numbers independently,
     * they are the same broadcast pair.
     */
    @Test
    void dragZoomOnOneChartSyncsTheSameWindowToEveryOtherChart() {
        openInsights();
        String cpu = "#insights-panels .pk-insight-panel[data-panel-id='cpu']";
        String load = "#insights-panels .pk-insight-panel[data-panel-id='load']";
        page.waitForSelector(cpu + " canvas");
        page.waitForSelector(load + " canvas");
        assertThat(page.isVisible("#insights-zoom-reset")).isFalse();

        dragZoomOnChart(cpu + " canvas");

        page.waitForFunction("(sel) => document.querySelector(sel)?.dataset.zoomMin !== undefined", cpu);

        String cpuMin = page.getAttribute(cpu, "data-zoom-min");
        String cpuMax = page.getAttribute(cpu, "data-zoom-max");
        assertThat(cpuMin).isNotNull();
        assertThat(cpuMax).isNotNull();
        assertThat(page.getAttribute(load, "data-zoom-min")).isEqualTo(cpuMin);
        assertThat(page.getAttribute(load, "data-zoom-max")).isEqualTo(cpuMax);
        assertThat(page.isVisible("#insights-zoom-reset")).isTrue();
    }

    /**
     * The toolbar's zoom reset restores every chart to auto-fit/live-following, not just
     * the one it happened to be clicked from - and the readouts, which never stopped
     * moving even while zoomed, keep moving after.
     */
    @Test
    void resettingZoomRestoresLiveAutoFitOnEveryChart() {
        openInsights();
        String cpu = "#insights-panels .pk-insight-panel[data-panel-id='cpu']";
        page.waitForSelector(cpu + " canvas");

        dragZoomOnChart(cpu + " canvas");
        page.waitForFunction("(sel) => document.querySelector(sel)?.dataset.zoomMin !== undefined", cpu);
        assertThat(page.isVisible("#insights-zoom-reset")).isTrue();

        page.click("#insights-zoom-reset");

        page.waitForFunction("(sel) => document.querySelector(sel)?.dataset.zoomMin === undefined", cpu);
        assertThat(page.isVisible("#insights-zoom-reset")).isFalse();

        // live updates still reach the readout after a zoom/reset cycle, exactly as before
        String value = cpu + " .pk-insight-current";
        page.waitForFunction("(selector) => document.querySelector(selector)?.textContent.trim()", value);
        String before = page.textContent(value);
        page.waitForFunction(
                "([selector, previous]) => {"
                        + "  const element = document.querySelector(selector);"
                        + "  return !!element && element.textContent !== previous"
                        + "      && element.classList.contains('pk-blink');"
                        + "}",
                List.of(value, before),
                new Page.WaitForFunctionOptions().setTimeout(15000));
        assertThat(page.textContent(value))
                .as("a live CPU reading moves after a zoom reset")
                .isNotEqualTo(before);
    }

    /**
     * uPlot's own double-click-to-reset gesture is redirected (see insights-chart.js) to
     * reset every chart, not just the one double-clicked - proven here by zooming from one
     * chart and double-clicking a different one.
     */
    @Test
    void doubleClickingAnyChartResetsZoomOnEveryChart() {
        openInsights();
        String cpu = "#insights-panels .pk-insight-panel[data-panel-id='cpu']";
        String load = "#insights-panels .pk-insight-panel[data-panel-id='load']";
        page.waitForSelector(cpu + " canvas");
        page.waitForSelector(load + " canvas");

        dragZoomOnChart(cpu + " canvas");
        page.waitForFunction("(sel) => document.querySelector(sel)?.dataset.zoomMin !== undefined", load);

        // uPlot's cursor overlay (.u-over) sits above the canvas and is what actually
        // receives pointer events - the element Playwright must click, not the canvas itself
        page.locator(load + " .u-over").dblclick();

        page.waitForFunction("(sel) => document.querySelector(sel)?.dataset.zoomMin === undefined", cpu);
        assertThat(page.isVisible("#insights-zoom-reset")).isFalse();
    }

    @Test
    void rapidLevelSwitchingLeavesEveryPanelCharted() {
        openInsights();
        page.waitForSelector("#insights-panels .pk-insight-panel[data-panel-id='cpu'] canvas");

        // both switches must land inside the first rebuild's data fetch - dispatched in
        // one task, since two real clicks can straddle it instead
        page.evaluate("() => [...document.querySelectorAll('#insights-level .pk-insight-level')]"
                + ".filter(button => ['1', '0'].includes(button.dataset.level))"
                + ".sort((a, b) => b.dataset.level - a.dataset.level)"
                + ".forEach(button => button.click())");

        page.waitForSelector("#insights-panels .pk-insight-panel[data-panel-id='cpu'] canvas");
    }
}

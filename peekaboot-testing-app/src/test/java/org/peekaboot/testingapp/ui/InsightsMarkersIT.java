package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.util.List;
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

    /** The stub plot's overlay in {@link #openAStubPlotWithFourMarkers()}. */
    private static final String STUB_PLOT = "#pk-markers-plot";

    @BeforeEach
    void captureBrowserConsole() {
        captureBrowserSignals();
    }

    private void openInsights() {
        openDashboard();
        dashboard.openTab("insights");
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

    private Locator firstPlot() {
        return page.locator(PANEL + " .u-over").first();
    }

    private Locator firstTooltip() {
        return page.locator(PANEL + " .pk-insight-marker-tip").first();
    }

    /**
     * The marker layer on a plot that is not uPlot's: four markers at fixed positions, which
     * the one start of the running application cannot give. The plot is 400px wide over 100
     * seconds, so a marker sits at four times its second: a stop and a start 4px apart (40,
     * 44), within one tooltip's reach of each other, then two starts on their own (200, 360).
     * Each event's version is a letter, in x order.
     */
    private void openAStubPlotWithFourMarkers() {
        importModule("dashboard/tabs/insights-markers.js", """
            (() => {
                const panel = document.createElement('div');
                panel.className = 'pk-insight-panel';
                const root = panel.appendChild(document.createElement('div'));
                const over = root.appendChild(document.createElement('div'));
                over.id = 'pk-markers-plot';
                over.style.cssText = 'position: relative; width: 400px; height: 100px';
                document.body.appendChild(panel);
                const plot = {root, over, ctx: document.createElement('canvas').getContext('2d'),
                    bbox: {left: 0, top: 0, width: 400, height: 100}, scales: {x: {min: 0, max: 100}},
                    valToPos: value => value * 4, cursor: {left: -10}};
                const at = (second, type, version) =>
                    ({type, version, timestamp: new Date(second * 1000).toISOString()});
                const layer = m.createMarkerLayer({intervalMs: 1000, label: 'CPU'});
                layer.setEvents([at(10, 'stop', 'a'), at(11, 'start', 'b'), at(50, 'start', 'c'), at(90, 'start', 'd')]);
                layer.plugin.hooks.init(plot);
                layer.plugin.hooks.draw(plot);
            })()
            """);
    }

    /** Presses {@code key} and returns the versions the stub plot's tooltip then lists; none when it is hidden. */
    @SuppressWarnings("unchecked")
    private List<String> versionsListedAfter(String key) {
        page.keyboard().press(key);
        return (List<String>) page.evaluate("""
            plot => {
                const tooltip = document.querySelector(plot + ' .pk-insight-marker-tip');
                return tooltip.hidden ? [] : [...tooltip.querySelectorAll('dd')].map(version => version.textContent);
            }
            """, STUB_PLOT);
    }

    @Test
    void theRunningApplicationsOwnStartIsMarkedOnTheChart() {
        openInsights();
        useALevelWithHeadroomForTheStartMarker();

        // One run, one marker - and it has to sit inside the plot the reader is looking at,
        // which a marker layer that lost track of the x scale would not.
        assertThat(page.locator(PANEL).first().getAttribute("data-marker-count"))
                .isEqualTo("1");
        double markerX = Double.parseDouble(page.locator(PANEL).first().getAttribute("data-marker-x"));
        BoundingBox plot = page.locator(PANEL + " .u-over").first().boundingBox();
        assertThat(markerX).isBetween(0.0, plot.width);
    }

    @Test
    void hoveringAMarkerNamesTheBuildThatStarted() {
        openInsights();
        useALevelWithHeadroomForTheStartMarker();

        double markerX = Double.parseDouble(
                page.locator(PANEL).first().getAttribute("data-marker-x").split(",", -1)[0]);
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

    /**
     * The markers are canvas drawing, so nothing in them can take focus. The plot does, and
     * the arrow keys bring up the details a pointer gets by hovering.
     */
    @Test
    void arrowKeysStepThroughTheRestartMarkers() {
        openInsights();
        useALevelWithHeadroomForTheStartMarker();
        assertThat(firstTooltip().isHidden()).isTrue();

        firstPlot().focus();
        page.keyboard().press("ArrowRight");

        assertThat(firstTooltip().isVisible()).isTrue();
        assertThat(firstTooltip().textContent()).contains("Started");

        page.keyboard().press("Escape");

        assertThat(firstTooltip().isHidden()).isTrue();
    }

    @Test
    void thePlotIsAFocusableGroupThatNamesItsPanelAndItsKeys() {
        openInsights();
        Locator panel = page.locator(PANEL).first();
        Locator plot = panel.locator(".u-over");

        assertThat(plot.getAttribute("tabindex")).isEqualTo("0");
        assertThat(plot.getAttribute("role")).isEqualTo("group");
        assertThat(plot.getAttribute("aria-label"))
                .isEqualTo(panel.locator(".pk-insight-panel__title").textContent()
                        + ". Left and Right arrows step through the restart markers.");
        assertThat(panel.locator(".pk-insight-marker-tip").getAttribute("role"))
                .as("the details are announced when a key brings them up")
                .isEqualTo("status");
    }

    @Test
    void leavingThePlotHidesTheDetailsTheKeysBroughtUp() {
        openInsights();
        useALevelWithHeadroomForTheStartMarker();
        firstPlot().focus();
        page.keyboard().press("ArrowRight");
        assertThat(firstTooltip().isVisible()).isTrue();

        page.keyboard().press("Tab");

        assertThat(firstPlot().evaluate("plot => document.activeElement !== plot"))
                .as("focus left the plot")
                .isEqualTo(true);
        assertThat(firstTooltip().isHidden()).isTrue();
    }

    /**
     * uPlot runs the cursor hook again on every redraw while a pointer rests on the plot, and
     * a chart redraws with every tick. The details a key brought up stay through that: a
     * reader on the keyboard has not asked for what is under the pointer. A pointer that
     * moves has.
     */
    @Test
    void theKeysKeepTheDetailsUntilThePointerMoves() {
        openInsights();
        useALevelWithHeadroomForTheStartMarker();
        BoundingBox plot = firstPlot().boundingBox();
        double markerX = Double.parseDouble(
                page.locator(PANEL).first().getAttribute("data-marker-x").split(",", -1)[0]);
        // half a plot away from the one marker, so nothing is in reach of the pointer
        double restingX = plot.x + (markerX + plot.width / 2) % plot.width;
        double restingY = plot.y + plot.height / 2.0;
        page.mouse().move(restingX, restingY);
        assertThat(firstTooltip().isHidden()).isTrue();
        firstPlot().focus();
        page.keyboard().press("ArrowRight");
        assertThat(firstTooltip().isVisible()).isTrue();

        // every tick moves the charted window, and the panel publishes its right edge
        String rightEdge = page.locator(PANEL).first().getAttribute("data-zoom-max");
        page.waitForFunction(
                "([panel, before]) => document.querySelector(panel).dataset.zoomMax !== before",
                List.of(PANEL, rightEdge));

        assertThat(firstTooltip().isVisible())
                .as("the details survive a redraw under the resting pointer")
                .isTrue();
        assertThat(firstTooltip().textContent()).contains("Started");

        page.mouse().move(restingX + 3, restingY);

        // what is under the pointer now, which is nothing
        firstTooltip().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
    }

    /**
     * One press moves past every marker the details already list. A press per marker would
     * leave a reader pressing through a dense stretch of restarts with the same list in front
     * of them each time.
     */
    @Test
    void eachKeyPressBringsUpTheNextClusterOfMarkers() {
        openAStubPlotWithFourMarkers();
        page.focus(STUB_PLOT);

        assertThat(versionsListedAfter("ArrowRight")).containsExactly("a", "b");
        assertThat(versionsListedAfter("ArrowRight")).containsExactly("c");
        assertThat(versionsListedAfter("ArrowRight")).containsExactly("d");
        assertThat(versionsListedAfter("ArrowRight"))
                .as("nothing lies further right")
                .containsExactly("d");
        assertThat(versionsListedAfter("ArrowLeft")).containsExactly("c");
        assertThat(versionsListedAfter("ArrowLeft")).containsExactly("a", "b");
        assertThat(versionsListedAfter("ArrowLeft"))
                .as("nothing lies further left")
                .containsExactly("a", "b");
    }

    /** Markers that share one tooltip are told apart by a rule between them, and a lone marker has none. */
    @Test
    void markersListedTogetherAreSeparatedByARule() {
        openAStubPlotWithFourMarkers();
        page.focus(STUB_PLOT);
        String children = "plot => [...document.querySelector(plot + ' .pk-insight-marker-tip').children]"
                + ".map(child => child.tagName)";

        page.keyboard().press("ArrowRight");
        assertThat(page.evaluate(children, STUB_PLOT)).isEqualTo(List.of("P", "DL", "HR", "P", "DL"));

        page.keyboard().press("ArrowRight");
        assertThat(page.evaluate(children, STUB_PLOT)).isEqualTo(List.of("P", "DL"));
    }

    @Test
    void theFirstPressOfLeftStartsAtTheNewestMarker() {
        openAStubPlotWithFourMarkers();
        page.focus(STUB_PLOT);
        assertThat(versionsListedAfter("ArrowRight")).containsExactly("a", "b");
        assertThat(versionsListedAfter("Escape")).isEmpty();

        assertThat(versionsListedAfter("ArrowLeft")).containsExactly("d");
    }

    /** Alt+Left and Alt+Right move through the browser's history, and a focused chart must not take them. */
    @Test
    void anArrowPressedWithAModifierIsLeftToTheBrowser() {
        openAStubPlotWithFourMarkers();
        page.focus(STUB_PLOT);

        assertThat(versionsListedAfter("Alt+ArrowRight")).isEmpty();
        assertThat(versionsListedAfter("ArrowRight")).containsExactly("a", "b");
    }
}

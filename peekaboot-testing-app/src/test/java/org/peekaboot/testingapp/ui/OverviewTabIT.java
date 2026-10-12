package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.TimeoutError;
import java.util.List;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

class OverviewTabIT extends PlaywrightTestBase {

    /** Layout edges compare to within a pixel: grid tracks land on subpixel positions. */
    private static final Offset<Double> ONE_PIXEL = within(1.0);

    /** One Overview stat tile, by the id its insights config gives it. */
    private static String tile(String tileId) {
        return "#insights-tiles .pk-insight-tile[data-tile-id='" + tileId + "']";
    }

    /** An element's border box in viewport pixels, as getBoundingClientRect reports it. */
    private record Box(double left, double right, double top, double bottom) {
        double width() {
            return right - left;
        }
    }

    private Box box(String selector) {
        @SuppressWarnings("unchecked")
        Map<String, Number> rect = (Map<String, Number>) page.evalOnSelector(
                selector,
                "el => { const r = el.getBoundingClientRect();"
                        + " return {left: r.left, right: r.right, top: r.top, bottom: r.bottom}; }");
        return new Box(
                rect.get("left").doubleValue(),
                rect.get("right").doubleValue(),
                rect.get("top").doubleValue(),
                rect.get("bottom").doubleValue());
    }

    /** The card grid's own row gap at two columns, the spacing every stacked Overview section keeps. */
    private double cardRowGap() {
        return box("#spring-card").top() - box("#build-card").bottom();
    }

    /** Two tiles side by side on one row, together spanning {@code column} edge to edge. */
    private static void assertPairSpans(Box leftTile, Box rightTile, Box column) {
        assertThat(rightTile.top()).as("the pair shares a row").isCloseTo(leftTile.top(), ONE_PIXEL);
        assertThat(leftTile.left()).as("the pair's left edge").isCloseTo(column.left(), ONE_PIXEL);
        assertThat(rightTile.right()).as("the pair's right edge").isCloseTo(column.right(), ONE_PIXEL);
    }

    /**
     * Both values travel the actuator endpoints and the mappers before they reach a row, so
     * pinning them to the running JVM's own is what tells a real render from a card of
     * labels with nothing behind them.
     */
    @Test
    void overviewShowsJavaAndSystemCards() {
        openDashboard();
        page.waitForSelector("#java-info .pk-kv");

        assertThat(dashboard.kvValue("#java-info", "Version")).isEqualTo(System.getProperty("java.version"));
        assertThat(dashboard.kvValue("#os-info", "Architecture")).isEqualTo(System.getProperty("os.arch"));
    }

    @Test
    void healthBannerReflectsApplicationHealth() {
        openDashboard();
        page.waitForFunction("() => document.querySelector('#health-status-text').textContent.trim() !== ''");

        assertThat(page.textContent("#health-status-text")).isEqualTo("UP");
    }

    /**
     * A live JVM always holds some heap and never all of it, so a fill outside (0, 100] means
     * the real percentage never reached the primitive - a bare meter() with no argument
     * clamps to 0 and would still render.
     */
    @Test
    void memoryMeterIsRenderedWithTheSharedPrimitive() {
        openDashboard();
        page.waitForSelector("#memory-info .pk-meter__fill");

        String width = (String) page.evalOnSelector("#memory-info .pk-meter__fill", "el => el.style.width");
        assertThat(Double.parseDouble(width.replace("%", "")))
                .as("heap fill, rendered as %s", width)
                .isGreaterThan(0)
                .isLessThanOrEqualTo(100);
    }

    /**
     * A stored locale from a stale build ('en_US', underscore-joined) is not a valid
     * BCP-47 tag: toLocaleString throws RangeError on it, and renderData()'s tab loop
     * (main.js) stops rendering at the first tab that throws - surfacing as the
     * dashboard's own #error banner instead of a rendered Overview. The bootstrap
     * validates the stored value once, up front, and falls back as if nothing were
     * stored.
     */
    @Test
    void aStoredLocaleThatIsNotAValidTagFallsBackInsteadOfBreakingTheDashboard() {
        page.addInitScript("localStorage.setItem('peekaboot-locale', 'en_US')");

        openDashboard();

        assertThat(page.isVisible("#error")).isFalse();
        assertThat(page.inputValue("#locale-select")).isEqualTo("en-US");
    }

    /**
     * The insights stat tiles sit on the Overview tab, filled from /api/insights/config
     * on the dashboard's own 30s cycle - no SSE and no visit to the Insights tab. Only the
     * four time tiles: heap/disk/pool already appear in the Memory & Storage meters and
     * the DataSources grid on this page.
     */
    @Test
    void overviewShowsTheInsightStatTiles() {
        openDashboard();
        page.waitForSelector("#insights-tiles .pk-insight-tile[data-tile-id='uptime']");

        Object rendered = page.evaluate("() => [...document.querySelectorAll('#insights-tiles .pk-insight-tile')]"
                + ".map(el => el.dataset.tileId)");
        @SuppressWarnings("unchecked")
        List<String> tileIds = (List<String>) rendered;

        assertThat(tileIds).containsExactly("started-at", "startup-time", "ready-time", "uptime");
        assertThat(tileIds).doesNotContain("heap-max", "disk-total", "pool-min", "pool-max");
        assertThat(page.locator("#insights-tiles .pk-insight-tile__icon").count())
                .isEqualTo(tileIds.size());
        assertThat(page.textContent("#insights-tiles [data-tile-id='uptime'] .pk-insight-tile__value"))
                .as("a live tile resolves in a real app")
                .isNotEqualTo("-");
    }

    /**
     * Beside two card columns the four stat tiles share one row, each pair spanning one card
     * column edge to edge.
     */
    @Test
    void theInsightTilesLineUpWithTheTwoCardColumns() {
        openDashboard();
        page.waitForSelector(tile("uptime"));

        Box startedAt = box(tile("started-at"));
        Box readyTime = box(tile("ready-time"));
        assertPairSpans(startedAt, box(tile("startup-time")), box("#build-card"));
        assertPairSpans(readyTime, box(tile("uptime")), box("#git-card"));
        assertThat(readyTime.top()).as("both pairs share one row").isCloseTo(startedAt.top(), ONE_PIXEL);
    }

    /**
     * Over a single card column the stat tiles form a two-by-two, both rows spanning the cards
     * edge to edge.
     */
    @Test
    void theInsightTilesFormATwoByTwoOverASingleCardColumn() {
        page.setViewportSize(600, 900);
        openDashboard();
        page.waitForSelector(tile("uptime"));

        Box cards = box("#build-card");
        Box startedAt = box(tile("started-at"));
        Box startupTime = box(tile("startup-time"));
        Box readyTime = box(tile("ready-time"));
        assertPairSpans(startedAt, startupTime, cards);
        assertPairSpans(readyTime, box(tile("uptime")), cards);
        assertThat(readyTime.top()).as("the second pair wraps below the first").isGreaterThan(startedAt.bottom());
        assertThat(readyTime.right()).as("the columns line up row to row").isCloseTo(startedAt.right(), ONE_PIXEL);
        assertThat(startupTime.width()).as("two equal columns").isCloseTo(startedAt.width(), ONE_PIXEL);
    }

    /**
     * The health banner, and its details once opened, stack between the stat tiles and the cards,
     * one card row gap apart.
     */
    @Test
    void theHealthBannerAndItsDetailsSitBetweenTheTilesAndTheCards() {
        openDashboard();
        page.waitForSelector(tile("uptime"));
        double gap = cardRowGap();

        Box banner = box("#health-banner");
        assertThat(banner.top() - box("#insights-tiles").bottom())
                .as("tiles to banner")
                .isCloseTo(gap, ONE_PIXEL);
        assertThat(box("#build-card").top() - banner.bottom())
                .as("banner to cards")
                .isCloseTo(gap, ONE_PIXEL);

        page.click("#health-banner");
        page.waitForSelector("#health-components");

        Box details = box("#health-components");
        assertThat(details.top() - box("#health-banner").bottom())
                .as("banner to details")
                .isCloseTo(gap, ONE_PIXEL);
        assertThat(box("#build-card").top() - details.bottom())
                .as("details to cards")
                .isCloseTo(gap, ONE_PIXEL);
    }

    /** With insights off the health banner leads the Overview from where the tile row would have started. */
    @Test
    void withInsightsOffTheHealthBannerTakesTheTileRowsPlace() {
        openDashboard();
        page.waitForSelector(tile("uptime"));
        double tileRowOffset =
                box("#insights-tiles").top() - box("#overview-tab").top();

        page.route("**/peekaboot/api/features", route -> {
            APIResponse features = route.fetch();
            route.fulfill(new Route.FulfillOptions()
                    .setResponse(features)
                    .setBody(features.text().replace("\"insights\":true", "\"insights\":false")));
        });
        openDashboard();
        assertThat(page.isVisible(Dashboard.tabButton("insights")))
                .as("the insights flag was flipped")
                .isFalse();

        Box banner = box("#health-banner");
        assertThat(banner.top() - box("#overview-tab").top())
                .as("the banner starts where the tile row did")
                .isCloseTo(tileRowOffset, ONE_PIXEL);
        assertThat(box("#build-card").top() - banner.bottom())
                .as("banner to cards")
                .isCloseTo(cardRowGap(), ONE_PIXEL);
    }

    /**
     * The health banner is a real <button> with aria-expanded/aria-controls, not a
     * click-handled <div> that no keyboard reaches; this proves Space
     * actually expands it, not just that a mouse click does (which every other test here
     * would still pass even if the element were a div with an onclick handler).
     */
    @Test
    void healthBannerExpandsWithTheKeyboard() {
        openDashboard();
        page.waitForFunction("() => document.querySelector('#health-status-text').textContent.trim() !== ''");

        assertThat(page.getAttribute("#health-banner", "aria-expanded")).isEqualTo("false");
        assertThat(page.isHidden("#health-components")).isTrue();

        page.focus("#health-banner");
        page.keyboard().press("Space");

        assertThat(page.getAttribute("#health-banner", "aria-expanded")).isEqualTo("true");
        assertThat(page.isVisible("#health-components")).isTrue();
    }

    /**
     * The Overview tile row reads /api/insights/config on the dashboard's own refresh
     * cycle, but only while it is the tab on screen: a refresh with another tab showing
     * must not spend a request on tiles nobody is looking at. Switching back renders the
     * tab again, which is when the row catches up.
     */
    @Test
    void overviewSkipsTheTileFetchWhileAnotherTabIsShowing() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#environment");
        page.waitForSelector("#property-sources .pk-group__header");

        assertThatThrownBy(() -> page.waitForRequest(
                        "**/api/insights/config**",
                        new Page.WaitForRequestOptions().setTimeout(1000),
                        () -> page.click("#refresh-btn")))
                .as("no tile fetch while the Overview tab is hidden")
                .isInstanceOf(TimeoutError.class);

        page.waitForRequest("**/api/insights/config**", () -> page.click(Dashboard.tabButton("overview")));
        page.waitForSelector("#insights-tiles .pk-insight-tile");
    }
}

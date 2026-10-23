package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MetersTabIT extends PlaywrightTestBase {

    /** The meters tab's readout while a filter is active (meters.js's updateCount): each count is grouped. */
    private static final Pattern METERS_COUNT_READOUT = Pattern.compile("([\\d,.]+) / ([\\d,.]+) metrics");

    @Test
    void metersTabFiltersAndCounts() {
        openDashboard();
        dashboard.openTab("meters");

        page.fill("#meters-filter", "jvm.memory");
        page.waitForFunction("() => document.querySelector('#meters-count').textContent.includes('/')");

        String readout = page.textContent("#meters-count");
        Matcher counts = METERS_COUNT_READOUT.matcher(readout);
        assertThat(counts.matches())
                .as("the count readout reads '<matched> / <all> metrics': %s", readout)
                .isTrue();
        assertThat(parseGroupedInt(counts.group(1)))
                .as("jvm.memory matches some meters but not all of them: %s", readout)
                .isPositive()
                .isLessThan(parseGroupedInt(counts.group(2)));
    }

    /** Every meter's type badge fits its column whole, clipped nowhere and clear of the unit beside it. */
    @Test
    void everyMeterTypeBadgeFitsItsColumn() {
        openDashboard();
        dashboard.openTab("meters");

        assertThat(page.locator("#meters-list .pk-group__meta .pk-badge").allTextContents())
                .as("the app registers a long task timer, the longest type it has")
                .contains("LONG_TASK_TIMER");
        assertThat(meterTypeBadgesOutsideTheirColumn("#meters-list")).isEmpty();
    }

    /**
     * The meta columns are wide enough to push a phone-width header off screen while they share
     * the name's row, squeezing the name itself to nothing. Below the stylesheet's breakpoint
     * they take a row of their own instead.
     */
    @Test
    void meterGroupHeadersFitAPhoneViewport() {
        page.setViewportSize(375, 800);
        openDashboard();
        dashboard.openTab("meters");

        double sidewaysScroll = ((Number) page.evaluate(
                        "() => document.documentElement.scrollWidth - document.documentElement.clientWidth"))
                .doubleValue();
        double narrowestName = ((Number) page.evaluate("""
                        () => Math.min(...[...document.querySelectorAll('#meters-list .pk-group__name')]
                            .map(name => name.getBoundingClientRect().width
                                / name.closest('.pk-group__header').getBoundingClientRect().width))
                        """)).doubleValue();

        assertThat(sidewaysScroll).as("pixels the page scrolls sideways").isNotPositive();
        assertThat(narrowestName)
                .as("the narrowest metric name's share of its header")
                .isGreaterThan(0.5);
    }

    /**
     * A deep link into the meters tab must restore the text filter from the URL, and
     * typing further into it must keep writing the URL back (via replaceState - see
     * url-state.js's push/replace rule) without growing browser history, so every
     * keystroke doesn't add its own Back stop.
     */
    @Test
    void metersFilterIsRestoredFromTheUrlAndWritesBackOnInput() {
        page.navigate(dashboardUrl("#meters?q=jvm"));
        page.waitForFunction("() => document.querySelector('#meters-filter')?.value === 'jvm'");

        assertThat(page.inputValue("#meters-filter")).isEqualTo("jvm");

        int historyLengthBefore = ((Number) page.evaluate("() => window.history.length")).intValue();

        Locator input = page.locator("#meters-filter");
        input.click();
        input.press("End");
        input.press("m");

        page.waitForFunction("() => window.location.hash.includes('q=jvmm')");
        int historyLengthAfter = ((Number) page.evaluate("() => window.history.length")).intValue();

        assertThat(page.url()).contains("q=jvmm");
        assertThat(historyLengthAfter).isEqualTo(historyLengthBefore);
    }

    /**
     * The tab strip's own onSelect handler (main.js) pushes a plain "#<tab>" hash with no
     * params on every tab switch, so switching away from meters and back hands this tab a
     * bare URL. The seed logic must not treat that bare URL as authoritative - that would
     * clear the filter the user just typed even though nothing about it was undone. The
     * URL is authoritative only when it carries this tab's own "q" param; when it is bare
     * but the tab still has non-default state, that state is written back to the URL
     * instead, so the filter survives the round trip and the URL stays truthful.
     */
    @Test
    void metersFilterSurvivesSwitchingTabsAwayAndBack() {
        openDashboard();
        dashboard.openTab("meters");

        page.fill("#meters-filter", "jvm.memory");
        page.waitForFunction("() => window.location.hash.includes('q=jvm.memory')");

        dashboard.openTab("environment");

        dashboard.openTab("meters");
        page.waitForFunction("() => window.location.hash.includes('q=jvm.memory')");

        assertThat(page.inputValue("#meters-filter")).isEqualTo("jvm.memory");
        assertThat(page.url()).contains("q=jvm.memory");
    }

    /**
     * The counterpart of metersFilterSurvivesSwitchingTabsAwayAndBack: "the tab strip
     * switched tabs" (a bare hash this tab's own filter should survive) and "the user
     * hand-edited the address bar to remove the q param" (a bare hash that should
     * actually clear the filter) look identical in the URL. main.js tells them apart by
     * flagging a render as URL-authoritative only when it is the direct result of a
     * genuine hashchange event (handleHashChange()'s urlChangeInProgress) - a
     * programmatic tab switch never sets it, so its own bare hash lets the filter
     * survive, while a real hash edit clears it.
     */
    @Test
    void handEditingTheHashToRemoveTheFilterClearsIt() {
        openDashboard();
        dashboard.openTab("meters");

        page.fill("#meters-filter", "jvm");
        page.waitForFunction("() => window.location.hash.includes('q=jvm')");

        // Direct hash assignment fires a real 'hashchange' event - what a user editing
        // the address bar (or following a bookmark without the param) would produce -
        // unlike main.js's own pushAppHash/replaceAppHash writes, which never do.
        page.evaluate("() => { window.location.hash = '#meters'; }");
        page.waitForFunction("() => document.querySelector('#meters-filter').value === ''");

        assertThat(page.inputValue("#meters-filter")).isEmpty();
        assertThat(page.url()).endsWith("#meters");
    }

    /**
     * The meters tab fetches its own endpoint, so it owns the failure too: a rejection renders
     * the tab's own message in place of the group list rather than leaving the loading block
     * up for good. The request is refused by Chromium's real network stack.
     */
    @Test
    void theMetersTabSaysSoWhenItsOwnFetchFails() {
        page.route("**/peekaboot/api/metrics", route -> route.abort());

        openDashboard();
        page.click(Dashboard.tabButton("meters"));

        page.waitForSelector("#meters-list .pk-empty");
        assertThat(page.textContent("#meters-list .pk-empty")).startsWith("Failed to load metrics");
    }
}

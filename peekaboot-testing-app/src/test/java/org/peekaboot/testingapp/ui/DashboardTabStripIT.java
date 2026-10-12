package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.Test;

class DashboardTabStripIT extends PlaywrightTestBase {

    @Test
    void tabStripUsesAriaSelection() {
        openDashboard();

        assertThat(dashboard.selectedTab()).isEqualTo("overview");
    }

    @Test
    void switchingTabsUpdatesTheHashAndSelection() {
        openDashboard();
        dashboard.openTab("environment");

        assertThat(page.url()).endsWith("#environment");
        assertThat(dashboard.selectedTab()).isEqualTo("environment");
    }

    /**
     * The ARIA tabs pattern: only the selected tab is reachable by Tab, every other
     * tab moves out of the tab order (tabIndex -1) - proves the shared tabStrip()
     * helper actually drives this, not just that the markup happens to carry
     * aria-selected.
     */
    @Test
    void arrowKeysMoveBetweenTabs() {
        openDashboard();
        page.focus(".pk-tab[data-tab='overview']");

        page.keyboard().press("ArrowRight");

        // Insights is the very next tab button after Overview (see index.html's tab
        // order), and is unhidden here since the test profile configures the insights
        // feature (see dashboardShowsTheInsightStatTiles's own comment for the pattern).
        assertThat(page.evaluate("() => document.activeElement.dataset.tab")).isEqualTo("insights");
        assertThat(dashboard.selectedTab()).isEqualTo("insights");
    }

    @Test
    void arrowKeysWrapAtTheEnds() {
        openDashboard();
        page.focus(".pk-tab[data-tab='overview']");

        page.keyboard().press("ArrowLeft");

        Object lastVisible = page.evaluate(
                "() => [...document.querySelectorAll('#main-tabs .pk-tab')].filter(t => t.offsetParent !== null).pop().dataset.tab");
        assertThat(page.evaluate("() => document.activeElement.dataset.tab")).isEqualTo(lastVisible);
    }

    @Test
    void homeAndEndJumpToTheFirstAndLastVisibleTab() {
        openDashboard();
        page.focus(".pk-tab[data-tab='overview']");

        page.keyboard().press("End");
        Object lastVisible = page.evaluate(
                "() => [...document.querySelectorAll('#main-tabs .pk-tab')].filter(t => t.offsetParent !== null).pop().dataset.tab");
        assertThat(page.evaluate("() => document.activeElement.dataset.tab")).isEqualTo(lastVisible);

        page.keyboard().press("Home");
        assertThat(page.evaluate("() => document.activeElement.dataset.tab")).isEqualTo("overview");
    }

    @Test
    void onlyTheSelectedTabIsInTheTabOrder() {
        openDashboard();

        Object selectedTabIndex =
                page.evaluate("() => document.querySelector('.pk-tab[aria-selected=\"true\"]').tabIndex");
        Object otherTabIndex =
                page.evaluate("() => document.querySelector('.pk-tab[aria-selected=\"false\"]').tabIndex");

        assertThat(selectedTabIndex).isEqualTo(0);
        assertThat(otherTabIndex).isEqualTo(-1);
    }

    /**
     * The dashboard hides the traces/meters tabs (and others) until /api/features
     * says they're available - arrow navigation must skip anything not currently
     * visible, not just walk DOM order. Hides Environment directly (rather than
     * depending on which tabs the test profile's real data happens to unhide) so
     * this discriminates the skip logic itself, not incidental feature flags.
     */
    @Test
    void hiddenTabsAreSkippedByArrowNavigation() {
        openDashboard();
        page.evaluate("() => document.querySelector('.pk-tab[data-tab=\"environment\"]').classList.add('hidden')");
        page.focus(".pk-tab[data-tab='overview']");

        page.keyboard().press("ArrowRight");

        String selected = dashboard.selectedTab();
        Object expectedNext = page.evaluate(
                "() => { const visible = [...document.querySelectorAll('#main-tabs .pk-tab')].filter(t => t.offsetParent !== null);"
                        + " const idx = visible.findIndex(t => t.dataset.tab === 'overview');"
                        + " return visible[(idx + 1) % visible.length].dataset.tab; }");

        assertThat(selected).isNotEqualTo("environment");
        assertThat(selected).isEqualTo(expectedNext);
    }

    /**
     * Inspects the real accessibility tree (not just the markup) to confirm the
     * strip exposes as an actual tablist with the right tabs and selected state -
     * markup alone has repeatedly looked right in this project without being right
     * (a role="button" wrapping a link is the classic case).
     */
    @Test
    void tabStripExposesAsARealTablistInTheAccessibilityTree() {
        openDashboard();

        Locator tablist = page.locator("#main-tabs");
        String snapshot = tablist.ariaSnapshot();

        assertThat(snapshot).contains("tablist");
        assertThat(snapshot).contains("\"Overview\" [selected]");
        assertThat(snapshot).contains("\"Environment\"");
    }

    /**
     * A bookmark or a shared link can name a tab this instance does not have - Flyway is
     * disabled under the test profile, so its tab is hidden. Landing on an empty panel with
     * no tab selected looks broken; the dashboard falls back to Overview and corrects the
     * hash, the way a bogus filter value is corrected to the state that restored.
     */
    @Test
    void deepLinkToAnUnavailableTabFallsBackToOverview() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#flyway");
        page.waitForSelector("#overview-tab.active");
        page.waitForSelector("#build-info > *");

        assertThat(page.url()).endsWith("#overview");
        assertThat(page.isVisible("#flyway-tab")).isFalse();
        assertThat(page.getAttribute(".pk-tab[data-tab='overview']", "aria-selected"))
                .isEqualTo("true");
        assertThat(page.isVisible(".pk-tab[data-tab='flyway']")).isFalse();
    }

    /**
     * The other half of that fallback: an id no tab has at all, from a typo or a link built
     * against an older version. Overview renders and the URL is corrected to say so, rather
     * than keeping a hash that names a view the reader is not looking at.
     */
    @Test
    void deepLinkToAnUnknownTabCorrectsTheHashToOverview() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#nosuchtab");
        page.waitForSelector("#overview-tab.active");
        page.waitForSelector("#build-info > *");

        assertThat(page.url()).endsWith("#overview");
        assertThat(page.getAttribute(".pk-tab[data-tab='overview']", "aria-selected"))
                .isEqualTo("true");
    }
}

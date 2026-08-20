package org.peekaboot.testingapp.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardTabsTest extends PlaywrightTestBase {

    @Test
    void overviewShowsJavaAndSystemCards() {
        openDashboard();
        page.waitForSelector("#java-info .pk-kv");

        assertThat(page.textContent("#java-info")).contains("Version");
        assertThat(page.textContent("#os-info")).contains("Architecture");
    }

    @Test
    void healthBannerReflectsApplicationHealth() {
        openDashboard();
        page.waitForFunction("() => document.querySelector('#health-status-text').textContent.trim() !== ''");

        assertThat(page.textContent("#health-status-text")).isEqualTo("UP");
    }

    @Test
    void memoryMeterIsRenderedWithTheSharedPrimitive() {
        openDashboard();
        page.waitForSelector("#memory-info .pk-meter__fill");

        assertThat(page.isVisible("#memory-info .pk-meter")).isTrue();
    }

    @Test
    void tabStripUsesAriaSelection() {
        openDashboard();

        String selected = (String) page.evaluate(
                "() => document.querySelector('.pk-tab[aria-selected=\"true\"]').dataset.tab");
        assertThat(selected).isEqualTo("dashboard");
    }

    @Test
    void switchingTabsUpdatesTheHashAndSelection() {
        openDashboard();
        page.click(".pk-tab[data-tab='environment']");
        page.waitForSelector("#environment-tab.active");

        assertThat(page.url()).endsWith("#environment");
        assertThat(page.evaluate(
                "() => document.querySelector('.pk-tab[aria-selected=\"true\"]').dataset.tab"))
                .isEqualTo("environment");
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
        page.focus(".pk-tab[data-tab='dashboard']");

        page.keyboard().press("ArrowRight");

        assertThat(page.evaluate("() => document.activeElement.dataset.tab")).isEqualTo("environment");
        assertThat(page.evaluate(
                "() => document.querySelector('.pk-tab[aria-selected=\"true\"]').dataset.tab"))
                .isEqualTo("environment");
    }

    @Test
    void arrowKeysWrapAtTheEnds() {
        openDashboard();
        page.focus(".pk-tab[data-tab='dashboard']");

        page.keyboard().press("ArrowLeft");

        Object lastVisible = page.evaluate(
                "() => [...document.querySelectorAll('.pk-tab')].filter(t => t.offsetParent !== null).pop().dataset.tab");
        assertThat(page.evaluate("() => document.activeElement.dataset.tab")).isEqualTo(lastVisible);
    }

    @Test
    void homeAndEndJumpToTheFirstAndLastVisibleTab() {
        openDashboard();
        page.focus(".pk-tab[data-tab='dashboard']");

        page.keyboard().press("End");
        Object lastVisible = page.evaluate(
                "() => [...document.querySelectorAll('.pk-tab')].filter(t => t.offsetParent !== null).pop().dataset.tab");
        assertThat(page.evaluate("() => document.activeElement.dataset.tab")).isEqualTo(lastVisible);

        page.keyboard().press("Home");
        assertThat(page.evaluate("() => document.activeElement.dataset.tab")).isEqualTo("dashboard");
    }

    @Test
    void onlyTheSelectedTabIsInTheTabOrder() {
        openDashboard();

        Object selectedTabIndex = page.evaluate(
                "() => document.querySelector('.pk-tab[aria-selected=\"true\"]').tabIndex");
        Object otherTabIndex = page.evaluate(
                "() => document.querySelector('.pk-tab[aria-selected=\"false\"]').tabIndex");

        assertThat(selectedTabIndex).isEqualTo(0);
        assertThat(otherTabIndex).isEqualTo(-1);
    }

    /**
     * The dashboard hides the traces/metrics tabs (and others) until /api/features
     * says they're available - arrow navigation must skip anything not currently
     * visible, not just walk DOM order. Hides Environment directly (rather than
     * depending on which tabs the test profile's real data happens to unhide) so
     * this discriminates the skip logic itself, not incidental feature flags.
     */
    @Test
    void hiddenTabsAreSkippedByArrowNavigation() {
        openDashboard();
        page.evaluate("() => document.querySelector('.pk-tab[data-tab=\"environment\"]').classList.add('hidden')");
        page.focus(".pk-tab[data-tab='dashboard']");

        page.keyboard().press("ArrowRight");

        String selected = (String) page.evaluate(
                "() => document.querySelector('.pk-tab[aria-selected=\"true\"]').dataset.tab");
        Object expectedNext = page.evaluate(
                "() => { const visible = [...document.querySelectorAll('.pk-tab')].filter(t => t.offsetParent !== null);"
              + " const idx = visible.findIndex(t => t.dataset.tab === 'dashboard');"
              + " return visible[(idx + 1) % visible.length].dataset.tab; }");

        assertThat(selected).isNotEqualTo("environment");
        assertThat(selected).isEqualTo(expectedNext);
    }

    /**
     * Inspects the real accessibility tree (not just the markup) to confirm the
     * strip exposes as an actual tablist with the right tabs and selected state -
     * markup alone has repeatedly looked right in this project without being right
     * (see the role="button"-wrapping-a-link defect from Tasks 10/15).
     */
    @Test
    void tabStripExposesAsARealTablistInTheAccessibilityTree() {
        openDashboard();

        Locator tablist = page.locator("#main-tabs");
        String snapshot = tablist.ariaSnapshot();

        assertThat(snapshot).contains("tablist");
        assertThat(snapshot).contains("\"Dashboard\" [selected]");
        assertThat(snapshot).contains("\"Environment\"");
    }

    @Test
    void deepLinkOpensTheRequestedTab() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#loggers");
        page.waitForSelector("#loggers-tab.active");

        assertThat(page.isVisible("#loggers-tab")).isTrue();
    }

    /**
     * The health banner used to be a click-handled <div> - not reachable by keyboard at
     * all. It is now a real <button> with aria-expanded/aria-controls; this proves Space
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
     * main.js fetches /api/features once at boot and unhides the traces/metrics tab
     * buttons directly from the result - the only place those two buttons are ever
     * unhidden, since neither has a tab module registered yet (Task 15). Tracing is
     * enabled in the test profile (see TraceOverlayTest/ToolbarTest, which depend on
     * real trace data), so this is a real assertion on a real feature flag, not a stub.
     */
    @Test
    void tracesTabIsUnhiddenWhenTracingIsAvailable() {
        openDashboard();
        page.waitForFunction(
                "() => !document.querySelector('.pk-tab[data-tab=\"traces\"]').classList.contains('hidden')");

        assertThat(page.isVisible(".pk-tab[data-tab='traces']")).isTrue();
    }

    @Test
    void environmentGroupsCollapseAndExpand() {
        openDashboard();
        page.click(".pk-tab[data-tab='environment']");
        page.waitForSelector("#property-sources .pk-group__header");

        assertThat(page.isVisible("#property-sources .pk-group__list")).isFalse();

        page.click("#property-sources .pk-group__header");

        assertThat(page.isVisible("#property-sources .pk-group__list")).isTrue();
    }

    /**
     * Filtering never auto-expands a group - a matching group renders collapsed just
     * like an unfiltered one, so the header must be clicked open before a <mark>
     * inside its list becomes visible. Same pattern as configTabMasksSensitiveValues
     * below.
     */
    @Test
    void environmentFilterHighlightsMatches() {
        openDashboard();
        page.click(".pk-tab[data-tab='environment']");
        page.waitForSelector("#property-sources .pk-group");

        page.fill("#env-filter", "server.port");
        page.waitForSelector("#property-sources mark", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.ATTACHED));
        page.click("#property-sources .pk-group__header");

        page.waitForSelector("#property-sources mark");
        assertThat(page.textContent("#property-sources mark")).contains("server.port");
    }

    @Test
    void expandedGroupSurvivesARefresh() {
        openDashboard();
        page.click(".pk-tab[data-tab='config']");
        page.waitForSelector("#config-groups .pk-group__header");
        page.click("#config-groups .pk-group__header");
        assertThat(page.isVisible("#config-groups .pk-group__list")).isTrue();

        page.click("#refresh-btn");
        page.waitForFunction("() => !document.getElementById('refresh-icon').classList.contains('pk-spinning')");

        assertThat(page.isVisible("#config-groups .pk-group__list")).isTrue();
    }

    @Test
    void loggersTabShowsLevelsAndRespectsConfiguredOnly() {
        openDashboard();
        page.click(".pk-tab[data-tab='loggers']");
        page.waitForSelector("#loggers-list .pk-group");

        int all = page.querySelectorAll("#loggers-list .pk-group").size();
        page.check("#loggers-configured-only");
        page.waitForFunction("(prev) => document.querySelectorAll('#loggers-list .pk-group').length !== prev", all);
        int configured = page.querySelectorAll("#loggers-list .pk-group").size();

        assertThat(configured).isLessThan(all);
    }

    /**
     * Filters on "key", not "password": the test profile's H2 datasource has no bound
     * username/password (Spring's /configprops report omits unset properties entirely,
     * rather than masking them), so no property in the real payload ever contains
     * "password" to filter on. "key" does occur for real - confirmed via a temporary
     * debug dump of the live /api/actuator/all/insights response against this exact
     * test profile: the "management.observations" group's "keyValues" property (and
     * "springdoc"'s "writerWithOrderByKeys") both survive the filter, and both are
     * masked by the same sensitive-key pattern - so this exercises the real masking
     * path against actual data instead of an unreachable filter term.
     */
    @Test
    void configTabMasksSensitiveValues() {
        openDashboard();
        page.click(".pk-tab[data-tab='config']");
        page.waitForSelector("#config-groups .pk-group__header");

        page.fill("#config-filter", "key");
        page.waitForSelector("#config-groups .pk-kv__value--sensitive", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.ATTACHED));
        page.click("#config-groups .pk-group__header");

        assertThat(page.querySelectorAll("#config-groups .pk-kv__value--sensitive")).isNotEmpty();
    }

    @Test
    void scheduledTasksTabGroupsByScheduleType() {
        openDashboard();
        page.click(".pk-tab[data-tab='scheduled-tasks']");
        page.waitForSelector("#scheduled-tasks-groups .pk-group");

        assertThat(page.querySelectorAll("#scheduled-tasks-groups .pk-group")).isNotEmpty();
        assertThat(page.textContent("#scheduled-tasks-summary")).contains("Total:");
    }

    @Test
    void metricsTabFiltersAndCounts() {
        openDashboard();
        page.click(".pk-tab[data-tab='metrics']");
        page.waitForSelector("#metrics-list .pk-group");

        page.fill("#metrics-filter", "jvm.memory");
        page.waitForFunction("() => document.querySelector('#metrics-count').textContent.includes('/')");

        assertThat(page.textContent("#metrics-count")).contains("/");
    }

    /**
     * Two things that look like they'd discriminate real bucket filtering, don't:
     * TraceInsightsService computes bucketCounts unconditionally (independent of the
     * requested bucket), so every bucket button's own count text is already correct
     * before any click - waiting on it (the first attempt at this test) resolves
     * immediately and proves nothing. And "every visible trace has .pk-badge--error"
     * (the second attempt) is also not a valid predicate against real data: the
     * backend's error-bucket membership is driven by any ERROR-level *log* during the
     * trace, while the frontend's HAS_ERRORS badge is driven only by an actual span
     * exception - confirmed empirically, the scheduler's fixedRate() logs an error
     * without throwing and lands in the errors bucket with no badge, alongside
     * fixedDelay()'s real exception, which does get one; a strict "every item has the
     * badge" wait against this app's real data never resolves.
     * <p>
     * What genuinely differs between bucket responses is the item count. The errors
     * bucket's total is already known from its button's text (see above - available
     * before any click), so read it once, then wait for the rendered list to actually
     * match it once real filtering has taken effect. The sanity assertion that it's
     * smaller than the unfiltered count is what keeps this non-vacuous: confirmed by
     * inspection that the unfiltered list contains a mix of error and non-error traces
     * (the scheduler's deliberate failures alongside ordinary HTTP request traces for
     * the dashboard's own page loads), so the two counts are never equal in practice.
     */
    @Test
    void tracesTabListsTracesAndBucketsThem() {
        openDashboard();
        page.click(".pk-tab[data-tab='traces']");
        page.waitForSelector("#traces-list .pk-trace-item");

        assertThat(page.textContent("#traces-bucket .pk-btn[data-bucket='all']")).contains("All (");
        int allCount = page.querySelectorAll("#traces-list .pk-trace-item").size();

        String errorsButtonText = page.textContent("#traces-bucket .pk-btn[data-bucket='errors']");
        // Stripping non-digits only yields the right number because no type filter is
        // active here: updateBucketCounts renders the plain "Errors (N)" form when
        // filteredCounts is null, and switches to "Errors (M / N)" once a filter is
        // applied - which this replaceAll would silently mangle into "MN".
        int expectedErrorsCount = Integer.parseInt(errorsButtonText.replaceAll("\\D+", ""));
        assertThat(expectedErrorsCount).isLessThan(allCount);

        page.click("#traces-bucket .pk-btn[data-bucket='errors']");
        page.waitForFunction(
                "(expected) => document.querySelectorAll('#traces-list .pk-trace-item').length === expected",
                expectedErrorsCount);

        assertThat(page.querySelectorAll("#traces-list .pk-trace-item")).hasSize(expectedErrorsCount);
    }

    @Test
    void clickingATraceOpensTheOverlayAndDeepLinks() {
        openDashboard();
        page.click(".pk-tab[data-tab='traces']");
        page.waitForSelector("#traces-list .pk-trace-item");

        page.click("#traces-list .pk-trace-item__open");
        page.waitForSelector("#peekaboot-trace-overlay");

        assertThat(page.url()).contains("#traces/");
    }

    @Test
    void closingTheOverlayCleansTheHash() {
        openDashboard();
        page.click(".pk-tab[data-tab='traces']");
        page.waitForSelector("#traces-list .pk-trace-item");
        page.click("#traces-list .pk-trace-item__open");
        page.waitForSelector("#peekaboot-trace-overlay");

        page.keyboard().press("Escape");
        page.waitForCondition(() -> page.querySelector("#peekaboot-trace-overlay") == null);

        assertThat(page.url()).endsWith("#traces");
    }

    /**
     * The scheduled-tasks "view traces" link pre-filters the Traces tab to that
     * scheduler's own SCHEDULED_JOB traces (rootActionType + rootOperation), via
     * context.navigate's payload argument routed to traces.js's applyFilter(). Proves
     * the link actually arrives filtered, not just that it switches tabs.
     */
    @Test
    void schedulerTracesLinkArrivesFiltered() {
        openDashboard();
        page.click(".pk-tab[data-tab='scheduled-tasks']");
        page.waitForSelector("#scheduled-tasks-groups .pk-group__header");
        page.click("#scheduled-tasks-groups .pk-group__header");
        page.waitForSelector(".pk-task__traces-link");

        page.click(".pk-task__traces-link");
        page.waitForSelector("#traces-tab.active");
        page.waitForFunction("() => !document.getElementById('traces-active-filter').classList.contains('hidden')");

        assertThat(page.textContent("#traces-active-filter")).contains("Type:").contains("Target:");
        assertThat(page.isChecked("#traces-filter input[value='SCHEDULED_JOB']")).isTrue();
    }
}

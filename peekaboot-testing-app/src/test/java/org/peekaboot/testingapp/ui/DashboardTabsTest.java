package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.util.List;
import org.junit.jupiter.api.Test;

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

    /**
     * The insights stat tiles sit on the Overview tab, filled from /api/insights/config
     * on the dashboard's own 30s cycle - no SSE and no visit to the Insights tab. The
     * heap/disk/pool tiles were dropped rather than moved: the Memory & Storage meters
     * and the DataSources grid on this very page already carry those numbers.
     */
    @Test
    void overviewShowsTheInsightStatTiles() {
        openDashboard();
        page.waitForSelector("#insights-tiles .pk-insight-tile[data-tile-id='uptime']");

        Object rendered = page.evaluate("() => [...document.querySelectorAll('#insights-tiles .pk-insight-tile')]"
                + ".map(el => el.dataset.tileId)");
        @SuppressWarnings("unchecked")
        List<String> tileIds = (List<String>) rendered;

        assertThat(tileIds).containsExactly("started-at", "startup-time", "ready-time", "uptime", "cpu-cores");
        assertThat(tileIds).doesNotContain("heap-max", "disk-total", "pool-min", "pool-max");
        assertThat(page.locator("#insights-tiles .pk-insight-tile__icon").count())
                .isEqualTo(tileIds.size());
        assertThat(page.textContent("#insights-tiles [data-tile-id='uptime'] .pk-insight-tile-value"))
                .as("a live tile resolves in a real app")
                .isNotEqualTo("-");
    }

    @Test
    void tabStripUsesAriaSelection() {
        openDashboard();

        String selected =
                (String) page.evaluate("() => document.querySelector('.pk-tab[aria-selected=\"true\"]').dataset.tab");
        assertThat(selected).isEqualTo("overview");
    }

    @Test
    void switchingTabsUpdatesTheHashAndSelection() {
        openDashboard();
        page.click(".pk-tab[data-tab='environment']");
        page.waitForSelector("#environment-tab.active");

        assertThat(page.url()).endsWith("#environment");
        assertThat(page.evaluate("() => document.querySelector('.pk-tab[aria-selected=\"true\"]').dataset.tab"))
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
        page.focus(".pk-tab[data-tab='overview']");

        page.keyboard().press("ArrowRight");

        // Insights is the very next tab button after Overview (see index.html's tab
        // order), and is unhidden here since the test profile configures the insights
        // feature (see dashboardShowsTheInsightStatTiles's own comment for the pattern).
        assertThat(page.evaluate("() => document.activeElement.dataset.tab")).isEqualTo("insights");
        assertThat(page.evaluate("() => document.querySelector('.pk-tab[aria-selected=\"true\"]').dataset.tab"))
                .isEqualTo("insights");
    }

    @Test
    void arrowKeysWrapAtTheEnds() {
        openDashboard();
        page.focus(".pk-tab[data-tab='overview']");

        page.keyboard().press("ArrowLeft");

        Object lastVisible = page.evaluate(
                "() => [...document.querySelectorAll('.pk-tab')].filter(t => t.offsetParent !== null).pop().dataset.tab");
        assertThat(page.evaluate("() => document.activeElement.dataset.tab")).isEqualTo(lastVisible);
    }

    @Test
    void homeAndEndJumpToTheFirstAndLastVisibleTab() {
        openDashboard();
        page.focus(".pk-tab[data-tab='overview']");

        page.keyboard().press("End");
        Object lastVisible = page.evaluate(
                "() => [...document.querySelectorAll('.pk-tab')].filter(t => t.offsetParent !== null).pop().dataset.tab");
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

        String selected =
                (String) page.evaluate("() => document.querySelector('.pk-tab[aria-selected=\"true\"]').dataset.tab");
        Object expectedNext = page.evaluate(
                "() => { const visible = [...document.querySelectorAll('.pk-tab')].filter(t => t.offsetParent !== null);"
                        + " const idx = visible.findIndex(t => t.dataset.tab === 'overview');"
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
        assertThat(snapshot).contains("\"Overview\" [selected]");
        assertThat(snapshot).contains("\"Environment\"");
    }

    @Test
    void deepLinkOpensTheRequestedTab() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#loggers");
        page.waitForSelector("#loggers-tab.active");

        assertThat(page.isVisible("#loggers-tab")).isTrue();
    }

    /**
     * Regression guard for a real bug tabStrip()'s {silent: true} option exists to
     * fix: handleHashChange() calls mainTabs.select(tabId, {silent: true}) precisely
     * so that syncing the strip's visual selection on a hash-driven boot doesn't also
     * re-trigger onSelect() - which calls setHash(tabId) with no detail argument, and
     * would silently strip the "/deadbeef" segment off a URL like "#traces/deadbeef"
     * before expandTraceById() even runs. Deep-linking straight to a trace detail URL
     * (not clicking into it - clickingATraceOpensTheOverlayAndDeepLinks below goes
     * through navigate(), whose own setHash(resolvedId, detail) call passes the real
     * detail through and would self-correct the hash even without silent) must land
     * with the URL intact once routing settles.
     */
    @Test
    void deepLinkingDirectlyToATraceDetailPreservesTheDetailSegment() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/deadbeef");
        page.waitForFunction("() => !!document.getElementById('peekaboot-trace-overlay')"
                + "?.shadowRoot?.querySelector('.pk-overlay__error')");

        assertThat(page.url()).endsWith("#traces/deadbeef");
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
     * main.js fetches /api/features once at boot and unhides the traces/meters tab
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
        page.waitForSelector(
                "#property-sources mark", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.ATTACHED));
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
     * application-test.yml binds spring.datasource.password as a fixture value purely so
     * this test has a real, secret-looking property to filter on and check against the
     * masking engine's actual output - the test profile's H2 datasource doesn't otherwise
     * need it. Filtering on "password" would previously have found nothing: Spring's
     * /configprops report omits unset properties entirely rather than masking them, and
     * before that fixture existed no property in the real payload contained "password".
     */
    @Test
    void configTabMasksSensitiveValues() {
        openDashboard();
        page.click(".pk-tab[data-tab='config']");
        page.waitForSelector("#config-groups .pk-group__header");

        page.fill("#config-filter", "password");
        // Rows render into the DOM regardless of the group's expand/collapse state -
        // only the group's [hidden] wrapper controls visibility - so this waits for
        // attachment, not visibility.
        page.waitForSelector(
                "#config-groups .pk-kv__key",
                new Page.WaitForSelectorOptions().setState(WaitForSelectorState.ATTACHED));

        String maskedValue = (String) page.evaluate("""
            () => {
                const row = Array.from(document.querySelectorAll('#config-groups .pk-kv'))
                    .find(r => r.querySelector('.pk-kv__key').textContent === 'password');
                return row ? row.querySelector('.pk-kv__value').textContent : null;
            }
            """);

        assertThat(maskedValue).isEqualTo("******");
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
    void metersTabFiltersAndCounts() {
        openDashboard();
        page.click(".pk-tab[data-tab='meters']");
        page.waitForSelector("#meters-list .pk-group");

        page.fill("#meters-filter", "jvm.memory");
        page.waitForFunction("() => document.querySelector('#meters-count').textContent.includes('/')");

        assertThat(page.textContent("#meters-count")).contains("/");
    }

    /**
     * A deep link into the meters tab must restore the text filter from the URL, and
     * typing further into it must keep writing the URL back (via replaceState - see
     * url-state.js's push/replace rule) without growing browser history, so every
     * keystroke doesn't add its own Back stop.
     */
    @Test
    void metersFilterIsRestoredFromTheUrlAndWritesBackOnInput() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#meters?q=jvm");
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
     * Regression test for a review finding: the tab strip's own onSelect handler
     * (main.js) pushes a plain "#<tab>" hash with no params on every tab switch, so
     * switching away from meters and back used to hand this tab a bare URL - and the
     * seed logic treated that bare URL as authoritative, actively clearing the filter
     * the user had just typed even though nothing about it had ever been undone. Fixed
     * by only treating the URL as authoritative when it actually carries this tab's own
     * "q" param; when it's bare but the tab still has non-default state, that state is
     * written back to the URL instead, so the filter survives the round trip and the URL
     * becomes truthful again.
     */
    @Test
    void metersFilterSurvivesSwitchingTabsAwayAndBack() {
        openDashboard();
        page.click(".pk-tab[data-tab='meters']");
        page.waitForSelector("#meters-list .pk-group");

        page.fill("#meters-filter", "jvm.memory");
        page.waitForFunction("() => window.location.hash.includes('q=jvm.memory')");

        page.click(".pk-tab[data-tab='environment']");
        page.waitForSelector("#environment-tab.active");

        page.click(".pk-tab[data-tab='meters']");
        page.waitForFunction("() => window.location.hash.includes('q=jvm.memory')");

        assertThat(page.inputValue("#meters-filter")).isEqualTo("jvm.memory");
        assertThat(page.url()).contains("q=jvm.memory");
    }

    /**
     * Regression test for a review finding on the fix above: the reconcile logic
     * couldn't tell "the tab strip switched tabs" (a bare hash this tab's own filter
     * should survive - see metersFilterSurvivesSwitchingTabsAwayAndBack) apart from "the
     * user hand-edited the address bar to remove the q param" (a bare hash that should
     * actually clear the filter) - both looked identical, so it always favored the
     * surviving-filter behavior, silently reverting a real edit. Fixed by having
     * main.js flag a render as URL-authoritative only when it's the direct result of a
     * genuine hashchange event (handleHashChange()'s urlChangeInProgress) - a
     * programmatic tab switch never sets it, so its own bare hash still lets the filter
     * survive, while a real hash edit now actually clears it.
     */
    @Test
    void handEditingTheHashToRemoveTheFilterClearsIt() {
        openDashboard();
        page.click(".pk-tab[data-tab='meters']");
        page.waitForSelector("#meters-list .pk-group");

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

        assertThat(page.textContent("#traces-bucket .pk-btn[data-bucket='all']"))
                .contains("All (");
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

    /**
     * A deep link into the traces tab must restore the bucket and type filter controls
     * from the URL, not just land on the traces tab - the whole point of Task 5 is that
     * a filtered traces URL is shareable/bookmarkable.
     */
    @Test
    void deepLinkRestoresTheTracesBucketAndTypeFilter() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces?bucket=errors&type=SCHEDULED_JOB");
        page.waitForSelector("#traces-bucket .pk-btn[data-bucket='errors'][aria-pressed='true']");

        assertThat(page.getAttribute("#traces-bucket .pk-btn[data-bucket='all']", "aria-pressed"))
                .isEqualTo("false");
        assertThat(page.getAttribute("#traces-bucket .pk-btn[data-bucket='errors']", "aria-pressed"))
                .isEqualTo("true");

        Object checkedTypesRaw = page.evaluate(
                "() => [...document.querySelectorAll('#traces-filter input:checked')].map(cb => cb.value)");
        @SuppressWarnings("unchecked")
        List<String> checkedTypes = (List<String>) checkedTypesRaw;
        assertThat(checkedTypes).containsExactly("SCHEDULED_JOB");
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
     * Regression test for a review finding: context.setUrlParams's closure (main.js's
     * currentContext()) used to capture detail/subview once, at the last render() of a
     * tab - but opening a trace (traces.js's click-to-open path) and closing it (its
     * onClose callback, both via context.navigate()) each skip a fresh render whenever
     * the traces tab was already active (navigate()'s wasAlreadyActive guard), so the
     * closure only ever picked up "detail = the open trace's id" if some *other* render
     * happened while the overlay was open - in real use, the 30s auto-refresh cycle;
     * here, a manual refresh click makes it deterministic. Once that was baked in,
     * closing the overlay cleared the real hash back to plain "#traces" but left the
     * closure stale - so the very next filter change replaced the hash with the
     * just-closed trace's id still attached, silently reopening it on reload/share.
     * Fixed by having setUrlParams re-parse the hash at call time instead of closing
     * over a snapshot.
     */
    @Test
    void closingAnOverlayThenFilteringDoesNotResurrectTheClosedTrace() {
        openDashboard();
        page.click(".pk-tab[data-tab='traces']");
        page.waitForSelector("#traces-list .pk-trace-item");

        page.click("#traces-list .pk-trace-item__open");
        page.waitForSelector("#peekaboot-trace-overlay");

        // Forces a full renderData() cycle while the overlay is open, so traces.js's
        // setUrlParams closure (pre-fix) would pick up the open trace's id as "detail" -
        // the same thing a real 30s auto-refresh cycle would eventually do on its own.
        // A real pointer click on the button is unusable here: the full-screen overlay
        // intercepts it, so this invokes the button's own click handler directly instead.
        page.evaluate("() => document.getElementById('refresh-btn').click()");
        page.waitForFunction("() => !document.getElementById('refresh-icon').classList.contains('pk-spinning')");

        page.keyboard().press("Escape");
        page.waitForCondition(() -> page.querySelector("#peekaboot-trace-overlay") == null);
        assertThat(page.url()).endsWith("#traces");

        page.click("#traces-bucket .pk-btn[data-bucket='errors']");
        page.waitForFunction("() => document.querySelector(\"#traces-bucket .pk-btn[data-bucket='errors']\")"
                + ".getAttribute('aria-pressed') === 'true'");

        assertThat(page.url()).endsWith("#traces?bucket=errors");
    }

    /**
     * Regression test for a review finding on the trace-detail re-open guard: two
     * hash-driven opens in a row - e.g. deep-linking from one trace straight into another,
     * or a Back/Forward step that lands on a different trace - used to desync main.js's
     * bookkeeping of "which trace is open" (a private module variable at the time).
     * openTraceDetail's synchronous closeTraceDetail() call fires the *first* trace's
     * still-registered onClose callback - which unconditionally cleared that bookkeeping -
     * before the *second* traceId was even recorded, clobbering it back to unset. The next
     * hashchange landing back on the (already open) second trace then failed the
     * "already open" check and tore the overlay down to rebuild it for no reason - the
     * exact flicker the guard exists to prevent. Fixed by deriving "is trace X open" from
     * the overlay host's own data-trace-id (stamped by trace-detail.js) instead of a
     * private flag, so it can't desync regardless of which of the app's two entry points
     * (main.js's hash routing, or traces.js's own click-to-open, which bypasses main.js's
     * bookkeeping entirely) opened the overlay.
     * <p>
     * Marks the host with a throwaway attribute right after switching traces, then
     * re-fires the very hashchange event Back/Forward (or a redundant navigation) would
     * produce for the trace already showing: a rebuilt overlay is a fresh DOM node and
     * loses the marker, while a guard that correctly recognizes the trace is already open
     * leaves the marked node untouched.
     */
    @Test
    void revisitingAnAlreadyOpenTraceAfterSwitchingDoesNotRebuildTheOverlay() {
        openDashboard();
        page.click(".pk-tab[data-tab='traces']");
        page.waitForSelector("#traces-list .pk-trace-item");

        Object idsRaw = page.evaluate(
                "() => [...document.querySelectorAll('#traces-list .pk-trace-item')].map(el => el.dataset.traceId)");
        @SuppressWarnings("unchecked")
        List<String> traceIds = (List<String>) idsRaw;
        assertThat(traceIds.size())
                .as("need at least two distinct traces for this test")
                .isGreaterThanOrEqualTo(2);
        String firstTraceId = traceIds.get(0);
        String secondTraceId = traceIds.get(1);

        // Deep-link straight to the first trace - main.js's own hash-driven
        // expandTraceById path, which is what registers the onClose callback that the
        // switch below fires early.
        page.evaluate("id => { window.location.hash = '#traces/' + id; }", firstTraceId);
        page.waitForFunction(
                "id => document.getElementById('peekaboot-trace-overlay')?.dataset.traceId === id", firstTraceId);

        // Straight to a *different* trace by hash, without closing the first - the exact
        // sequence that used to clobber the guard's bookkeeping (see the javadoc above).
        page.evaluate("id => { window.location.hash = '#traces/' + id; }", secondTraceId);
        page.waitForFunction(
                "id => document.getElementById('peekaboot-trace-overlay')?.dataset.traceId === id", secondTraceId);

        page.evaluate("() => { document.getElementById('peekaboot-trace-overlay').dataset.testMarker = 'stable'; }");

        // Re-fire the hashchange for the trace that's already open, without changing the
        // hash itself - what Back/Forward landing back on it produces.
        page.evaluate("() => window.dispatchEvent(new Event('hashchange'))");

        assertThat(page.getAttribute("#peekaboot-trace-overlay", "data-test-marker"))
                .isEqualTo("stable");
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
        assertThat(page.isChecked("#traces-filter input[value='SCHEDULED_JOB']"))
                .isTrue();
    }
}

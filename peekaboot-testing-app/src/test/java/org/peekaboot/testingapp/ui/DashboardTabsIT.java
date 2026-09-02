package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DashboardTabsIT extends PlaywrightTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The observed datasource - a connection acquired on it outside any traced work starts a pool trace. */
    @Autowired
    private DataSource dataSource;

    /** The page size traces.js asks for, read off its own request rather than mirrored. */
    private static final Pattern TRACES_PAGE_SIZE_PARAM = Pattern.compile("[?&]limit=(\\d+)");

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

        String selected =
                (String) page.evaluate("() => document.querySelector('.pk-tab[aria-selected=\"true\"]').dataset.tab");
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

    @Test
    void deepLinkOpensTheRequestedTab() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#loggers");
        page.waitForSelector("#loggers-tab.active");

        assertThat(page.isVisible("#loggers-tab")).isTrue();
    }

    /**
     * tabStrip()'s {silent: true} option exists for this:
     * handleHashChange() calls mainTabs.select(tabId, {silent: true}) precisely
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
     * main.js fetches /api/features once at boot and unhides the traces/meters tab
     * buttons directly from the result - the only place those two buttons are ever
     * unhidden. Tracing is
     * enabled in the test profile (see TraceOverlayIT/ToolbarIT, which depend on
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
     * need it. Without the fixture, filtering on "password" finds nothing: Spring's
     * /configprops report omits unset properties entirely rather than masking them, and
     * no other property in the real payload contains "password".
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
     * before any click - waiting on it resolves immediately and proves nothing. And
     * "every visible trace has .pk-badge--error" is not a valid predicate against real
     * data either: the backend's error-bucket membership is driven by any ERROR-level
     * *log* during the trace, while the frontend's HAS_ERRORS badge is driven only by an
     * actual span exception - the scheduler's fixedRate() logs an error without throwing
     * and lands in the errors bucket with an error-log count but no status badge, alongside fixedDelay()'s real
     * exception, which does get one.
     * <p>
     * What genuinely differs between bucket responses is the item count, so the list is
     * checked against the count carried by the very response that rendered it: the
     * bucket count is app-global and uncapped, the list is capped at the page size, and
     * any class running alongside this one can log an ERROR between two requests. It has
     * to be the <em>filtered</em> count: traces.js always sends a type include-list
     * (hiding CONNECTION_POOL), and a hidden trace that logs an ERROR is counted in
     * {@code bucketCounts} but never listed. The sanity assertion that the errors count is
     * smaller than the all count is what keeps this non-vacuous: the store always holds a
     * mix of error and non-error traces (the scheduler's deliberate failures alongside
     * ordinary HTTP request traces for the dashboard's own page loads).
     */
    @Test
    void tracesTabListsTracesAndBucketsThem() {
        openDashboard();
        page.click(".pk-tab[data-tab='traces']");
        page.waitForSelector("#traces-list .pk-trace-item");
        assertThat(page.textContent("#traces-bucket .pk-btn[data-bucket='all']"))
                .contains("All (");

        Response errorsResponse = page.waitForResponse(
                response -> response.url().contains("/api/traces/insights")
                        && response.url().contains("bucket=errors"),
                () -> page.click("#traces-bucket .pk-btn[data-bucket='errors']"));
        JsonNode errorsBucket = JSON.readTree(errorsResponse.text());
        JsonNode counts = errorsBucket.path("filteredBucketCounts");
        assertThat(counts.isObject())
                .as("a type-filtered listing carries the counts that match it")
                .isTrue();
        int errorsCount = counts.path("errors").asInt();
        int listedCount = errorsBucket.path("traces").size();

        assertThat(errorsCount).isPositive().isLessThan(counts.path("all").asInt());
        assertThat(listedCount).isEqualTo(Math.min(errorsCount, pageSizeOf(errorsResponse)));
        page.waitForFunction(
                "(expected) => document.querySelectorAll('#traces-list .pk-trace-item').length === expected",
                listedCount);
    }

    /**
     * A deep link into the traces tab must restore the bucket and type filter controls
     * from the URL, not just land on the traces tab - a filtered traces URL is meant to
     * be shareable/bookmarkable.
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

    /**
     * Every URL-sourced filter value traces.js reads is validated, "bucket" included: seeding
     * currentBucket='bogus' verbatim from "#traces?bucket=bogus" would hit the backend with it
     * and (on an empty result) render BUCKET_EMPTY_MESSAGES's literal "undefined" as the
     * empty-state text, since no such key exists. seedFromUrl falls back to 'all' for
     * anything not in BUCKET_EMPTY_MESSAGES's own key set - proven here
     * by the bucket strip itself: an unvalidated 'bogus' would match none of the three real
     * bucket buttons, leaving all three unpressed, where the fallback leaves "All" pressed.
     */
    @Test
    void bogusBucketInTheUrlFallsBackToAllInsteadOfHittingTheBackendWithIt() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces?bucket=bogus");
        page.waitForSelector("#traces-list .pk-trace-item");

        assertThat(page.getAttribute("#traces-bucket .pk-btn[data-bucket='all']", "aria-pressed"))
                .isEqualTo("true");
        assertThat(page.getAttribute("#traces-bucket .pk-btn[data-bucket='errors']", "aria-pressed"))
                .isEqualTo("false");
        assertThat(page.getAttribute("#traces-bucket .pk-btn[data-bucket='slow']", "aria-pressed"))
                .isEqualTo("false");
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
     * context.setUrlParams (main.js's currentContext()) re-parses the hash at call time
     * rather than closing over a detail/subview snapshot taken at the tab's last render().
     * A snapshot goes stale: opening a trace (traces.js's click-to-open path) and closing
     * it (its onClose callback, both via context.navigate()) each skip a fresh render
     * whenever the traces tab was already active (navigate()'s wasAlreadyActive guard), so
     * it would only pick up "detail = the open trace's id" through some *other* render
     * while the overlay is open - in real use, the 30s auto-refresh cycle; here, a manual
     * refresh click makes it deterministic. Closing the overlay then clears the real hash
     * back to plain "#traces" but leaves the snapshot behind, and the very next filter
     * change would replace the hash with the just-closed trace's id still attached,
     * silently reopening it on reload/share.
     */
    @Test
    void closingAnOverlayThenFilteringDoesNotResurrectTheClosedTrace() {
        openDashboard();
        page.click(".pk-tab[data-tab='traces']");
        page.waitForSelector("#traces-list .pk-trace-item");

        page.click("#traces-list .pk-trace-item__open");
        page.waitForSelector("#peekaboot-trace-overlay");

        // Forces a full renderData() cycle while the overlay is open, so a setUrlParams
        // closure taken at render time would pick up the open trace's id as "detail" -
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
     * The traces tab's own filter write-back has to respect an open overlay. With
     * "#traces?bucket=errors" active and a trace open on top of it, switching the overlay to
     * Logs and setting a level filter puts "#traces/<id>/logs?level=ERROR" in the address
     * bar - but the traces tab's panel is still ".active" underneath, so the 30s
     * auto-refresh (forced here via the refresh button, same pattern as
     * closingAnOverlayThenFilteringDoesNotResurrectTheClosedTrace above) re-renders it.
     * Seeing no bucket/type/op keys in the URL (level/q are the overlay's own), its
     * reconcileWithUrl would take the URL for stale and write its own {bucket: 'errors'}
     * back over the whole params slot, silently discarding the overlay's level filter from
     * the shareable URL. So both the seed direction (traces.js's reconcileWithUrl) and the
     * write direction (main.js's setUrlParams) treat a detail segment in the hash as "the
     * params slot belongs to the overlay - no-op".
     */
    @Test
    void autoRefreshOfTheTracesTabDoesNotClobberTheOpenOverlaysFilterParams() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces?bucket=errors");
        page.waitForSelector("#traces-bucket .pk-btn[data-bucket='errors'][aria-pressed='true']");
        page.waitForSelector("#traces-list .pk-trace-item");

        page.click("#traces-list .pk-trace-item__open");
        page.waitForSelector("#peekaboot-trace-overlay");
        page.waitForFunction(
                "() => !!document.getElementById('peekaboot-trace-overlay').shadowRoot"
                        + ".querySelector('.pk-tab[data-tab=\"logs\"]')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[data-tab=\"logs\"]').click()");
        // The tab switch renders synchronously, but wait for the element anyway - the
        // click evaluate() resolving does not mean the shadow DOM has been re-queried.
        page.waitForFunction(
                "() => !!document.getElementById('peekaboot-trace-overlay').shadowRoot"
                        + ".querySelector('#pk-log-level')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));
        page.waitForFunction("() => window.location.hash.includes('/logs')");

        page.evaluate("() => { const sel = document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('#pk-log-level'); sel.value = 'ERROR';"
                + " sel.dispatchEvent(new Event('change')); }");
        page.waitForFunction("() => window.location.hash.includes('level=ERROR')");

        // Forces a full renderData() cycle - the traces tab panel is still .active behind
        // the overlay, so this re-runs its reconcileWithUrl exactly as a real 30s
        // auto-refresh tick would while the overlay sits open on top of it.
        page.evaluate("() => document.getElementById('refresh-btn').click()");
        page.waitForFunction("() => !document.getElementById('refresh-icon').classList.contains('pk-spinning')");

        assertThat(page.url()).contains("level=ERROR");
        assertThat(page.url()).doesNotContain("bucket=errors");
    }

    /**
     * The trace-detail re-open guard derives "is trace X open" from the overlay host's own
     * data-trace-id (stamped by trace-detail.js) rather than from a private flag in
     * main.js. A flag desyncs on two hash-driven opens in a row - e.g. deep-linking from
     * one trace straight into another, or a Back/Forward step that lands on a different
     * trace: openTraceDetail's synchronous closeTraceDetail() call fires the *first*
     * trace's still-registered onClose callback, which would clear the flag before the
     * *second* traceId was even recorded, and the next hashchange landing back on the
     * (already open) second trace would then fail the "already open" check and tear the
     * overlay down to rebuild it for no reason - the exact flicker the guard exists to
     * prevent. The host attribute cannot desync regardless of which of the app's two
     * entry points (main.js's hash routing, or traces.js's own click-to-open, which
     * bypasses main.js's bookkeeping entirely) opened the overlay.
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

        // Straight to a *different* trace by hash, without closing the first - the
        // sequence that desyncs a flag-based guard (see the javadoc above).
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

    /**
     * Connection-pool traces sit in the store but not in the default view: with no type
     * in the URL, traces.js requests every type except CONNECTION_POOL. Selecting the
     * type's own chip reveals them and lands in the URL (#traces?type=CONNECTION_POOL),
     * so the revealed view stays shareable while old typed links keep their meaning.
     */
    private static int pageSizeOf(Response listing) {
        Matcher matcher = TRACES_PAGE_SIZE_PARAM.matcher(listing.url());
        assertThat(matcher.find())
                .as("traces.js names its page size: %s", listing.url())
                .isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    @Test
    void connectionPoolTracesAreHiddenByDefaultAndRevealedByTheirChip() throws SQLException {
        // What an external health probe or HikariCP maintenance does: acquire a pooled
        // connection outside any traced work, yielding a standalone CONNECTION_POOL trace.
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(1)).isTrue();
        }

        openDashboard();
        Response defaultResponse = page.waitForResponse(
                response -> response.url().contains("/api/traces/insights"),
                () -> page.click(".pk-tab[data-tab='traces']"));
        assertThat(defaultResponse.url()).contains("rootActionType=").doesNotContain("CONNECTION_POOL");
        page.waitForSelector("#traces-list .pk-trace-item");
        assertThat(page.locator("#traces-list .pk-trace-item__icon[aria-label='Connection Pool']")
                        .count())
                .isZero();

        page.waitForResponse(
                response -> response.url().contains("rootActionType=CONNECTION_POOL"),
                () -> page.check("#traces-filter input[value='CONNECTION_POOL']"));

        assertThat(page.url()).endsWith("#traces?type=CONNECTION_POOL");
        page.waitForSelector("#traces-list .pk-trace-item__icon[aria-label='Connection Pool']");
    }
}

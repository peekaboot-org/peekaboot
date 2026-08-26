package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

/**
 * Exercises the trace-detail overlay's URL-driven tab + filter state: deep-linking straight
 * into a subview with query params, and every subsequent tab switch / filter edit rewriting
 * the hash via replaceState (never adding a Back stop of its own). Split out from
 * TraceOverlayTest because these go through the full dashboard hash-routing integration
 * (main.js's handleHashChange -> expandTraceById) rather than the toolbar-only open path
 * that file's helpers are built around.
 */
class TraceDeepLinkTest extends PlaywrightTestBase {

    /**
     * Scheduler#fixedRate (peekaboot-testing-app) is a purpose-built fixture already relied
     * on elsewhere (see DashboardTabsTest.tracesTabListsTracesAndBucketsThem's javadoc): a
     * {@code fixedRate} job with no initial delay, so it runs once right at app boot, and
     * logs an INFO ("fixedRate start") followed by an ERROR ("fixedRate failed") in the same
     * trace - real data with both a row that matches a level=ERROR&q=failed filter and one
     * that doesn't, so this proves both the restore AND the actual hide/show, not just one
     * of the two. (OrderReconciler's WARN-per-stale-order logs would have made a more
     * on-the-nose fixture for this test, but the test profile runs with flyway.enabled=false
     * - see application-test.yml - so V4__order-data.sql never seeds the orders it needs and
     * it logs "reconciling 0 orders"; confirmed empirically before choosing this fixture
     * instead.) Searching the real traces-list API for it (rather than depending on test
     * execution order) is what makes this robust regardless of which test class runs first.
     */
    private String findFixedRateSchedulerTraceId() {
        openDashboard();
        String traceId = (String) page.evaluate("""
                async () => {
                    const response = await fetch('/peekaboot/api/traces/insights?bucket=errors&limit=50');
                    const result = await response.json();
                    const match = (result.traces || []).find(t => (t.rootSpan?.name || '').includes('fixedRate'));
                    return match ? match.traceId : null;
                }
                """);
        assertThat(traceId)
                .as("expected a real scheduler.fixedRate trace (INFO 'fixedRate start' + ERROR 'fixedRate "
                        + "failed') - Scheduler#fixedRate runs once at boot with no initial delay")
                .isNotNull();
        return traceId;
    }

    @Test
    void deepLinkToATraceLogsTabRestoresTabAndFilters() {
        String traceId = findFixedRateSchedulerTraceId();

        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/" + traceId + "/logs?level=ERROR&q=failed");
        page.waitForFunction(
                "() => !!document.getElementById('peekaboot-trace-overlay')?.shadowRoot"
                        + "?.querySelector('#pk-log-level')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));

        String selectedTab = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay')"
                + ".shadowRoot.querySelector('.pk-tab[aria-selected=\"true\"]').dataset.tab");
        String levelValue = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay')"
                + ".shadowRoot.querySelector('#pk-log-level').value");
        String textValue = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay')"
                + ".shadowRoot.querySelector('#pk-log-filter').value");

        assertThat(selectedTab).isEqualTo("logs");
        assertThat(levelValue).isEqualTo("ERROR");
        assertThat(textValue).isEqualTo("failed");

        // Every row visible in the DOM must actually match both filters, and every row
        // that matches must be visible - the same check applyFilters() itself enforces,
        // proven here via the real rendered pk-log--hidden classes on real fixture data
        // (one matching ERROR row, one non-matching INFO row - see the fixture's javadoc).
        Boolean everyRowConsistentWithTheFilters = (Boolean) page.evaluate("""
                () => [...document.getElementById('peekaboot-trace-overlay').shadowRoot.querySelectorAll('.pk-log')]
                    .every(row => {
                        const matches = row.dataset.level === 'ERROR'
                            && row.querySelector('.pk-log__message').textContent.toLowerCase().includes('failed');
                        return matches !== row.classList.contains('pk-log--hidden');
                    })
                """);
        assertThat(everyRowConsistentWithTheFilters).isTrue();

        Boolean atLeastOneRowVisible = (Boolean) page.evaluate("""
                () => [...document.getElementById('peekaboot-trace-overlay').shadowRoot.querySelectorAll('.pk-log')]
                    .some(row => !row.classList.contains('pk-log--hidden'))
                """);
        Boolean atLeastOneRowHidden = (Boolean) page.evaluate("""
                () => [...document.getElementById('peekaboot-trace-overlay').shadowRoot.querySelectorAll('.pk-log')]
                    .some(row => row.classList.contains('pk-log--hidden'))
                """);
        assertThat(atLeastOneRowVisible)
                .as("the ERROR 'fixedRate failed' row should be visible")
                .isTrue();
        assertThat(atLeastOneRowHidden)
                .as("the INFO 'fixedRate start' row should be hidden")
                .isTrue();
    }

    /**
     * Regression guard for the push/replace rule (url-state.js): the overlay's tab strip
     * and the Logs tab's own filters must both write via replaceState, so a single Back
     * step from a filtered subview closes the overlay outright rather than unwinding the
     * tab switch and filter edit as separate Back stops.
     */
    @Test
    void changingOverlayFiltersRewritesTheUrlWithoutAddingHistoryEntries() {
        // /?error=true (PersonController) logs a single real ERROR line - just needs a
        // trace with at least one log row so the Logs tab actually renders #pk-log-level;
        // unlike deepLinkToATraceLogsTabRestoresTabAndFilters this test isn't checking
        // filter correctness, only that changing a filter rewrites the URL without pushing
        // a new history entry.
        page.navigate(baseUrl + "/?error=true");
        page.waitForSelector("#peekaboot-toolbar-host");
        page.waitForFunction("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-trace').textContent.trim() !== '-'");
        // #pk-trace's own textContent is "traceId<hex><copy-icon>" (a labelled copy-button,
        // see shared/copyable.js) - the bare id lives in its .pk-copy__value child.
        String traceId = (String) page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-trace .pk-copy__value').textContent.trim()");

        openDashboard();
        // Establishes a real pushed '#traces' history entry - the state Back below must
        // land on once it unwinds the trace-open, tab-switch and filter-change entry that
        // were all written via replaceState.
        page.click(".pk-tab[data-tab='traces']");
        page.waitForSelector("#traces-list .pk-trace-item");

        // Deep-link (rather than clicking the trace's own open button): a real navigation,
        // like Back/Forward, so this test can drive main.js's hash-routing path - the one
        // that threads urlState into the overlay - the same way deepLinkOpensTheRequestedTab
        // and revisitingAnAlreadyOpenTraceAfterSwitchingDoesNotRebuildTheOverlay do.
        page.evaluate("id => { window.location.hash = '#traces/' + id; }", traceId);
        page.waitForFunction(
                "id => document.getElementById('peekaboot-trace-overlay')?.dataset.traceId === id", traceId);
        page.waitForFunction(
                "() => !!document.getElementById('peekaboot-trace-overlay').shadowRoot"
                        + ".querySelector('.pk-tab[data-tab=\"logs\"]')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));

        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[data-tab=\"logs\"]').click()");
        page.waitForFunction("() => !!document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('#pk-log-level')");

        page.evaluate("() => { const select = document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('#pk-log-level'); select.value = 'ERROR';"
                + " select.dispatchEvent(new Event('change', {bubbles: true})); }");

        assertThat(page.url()).contains("level=");

        page.goBack();

        page.waitForCondition(() -> page.querySelector("#peekaboot-trace-overlay") == null);
        assertThat(page.url()).endsWith("#traces");
    }

    /**
     * Regression guard for the DOM-only-state bug this task's logs.js refactor fixes: the
     * span filter chip used to force a full re-render off container.innerHTML, and the text/
     * level inputs' values lived only in the DOM - so that re-render silently wiped whatever
     * the user had typed. logs.js now renders its controls from a `state` object instead.
     */
    @Test
    void logsTextAndLevelFiltersSurviveChangingTheSpanFilter() {
        setStoredTheme("light");
        page.navigate(baseUrl + "/?error=true");
        page.waitForSelector("#peekaboot-toolbar-host");
        page.waitForFunction("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-trace').textContent.trim() !== '-'");
        page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('.pk-toolbar').click()");
        page.waitForSelector("#peekaboot-trace-overlay");
        page.waitForFunction(
                "() => !!document.getElementById('peekaboot-trace-overlay').shadowRoot"
                        + ".querySelector('.pk-tab[data-tab=\"logs\"]')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[data-tab=\"logs\"]').click()");
        page.waitForFunction(
                "() => !!document.getElementById('peekaboot-trace-overlay').shadowRoot"
                        + ".querySelector('.pk-log__span')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));

        page.evaluate("() => { const input = document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('#pk-log-filter'); input.value = 'persons';"
                + " input.dispatchEvent(new Event('input', {bubbles: true})); }");

        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-log__span').click()");
        page.waitForFunction("() => !!document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-logs-filter-span')");

        String textValue = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay')"
                + ".shadowRoot.querySelector('#pk-log-filter').value");
        assertThat(textValue).isEqualTo("persons");
    }
}

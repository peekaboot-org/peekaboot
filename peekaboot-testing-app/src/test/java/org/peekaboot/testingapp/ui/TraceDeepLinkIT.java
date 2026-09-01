package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.Scheduler;
import org.peekaboot.testingapp.integration.ScheduledJobs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * Exercises the trace-detail overlay's URL-driven tab + filter state: deep-linking straight
 * into a subview with query params, and every subsequent tab switch / filter edit rewriting
 * the hash via replaceState (never adding a Back stop of its own). Split out from
 * TraceOverlayIT because these go through the full dashboard hash-routing integration
 * (main.js's handleHashChange -> expandTraceById) rather than the toolbar-only open path
 * that file's helpers are built around.
 */
class TraceDeepLinkIT extends PlaywrightTestBase {

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    /**
     * Scheduler#fixedRate (peekaboot-testing-app) is a purpose-built fixture: it logs an
     * INFO ("fixedRate start") followed by an ERROR ("fixedRate failed") in the same trace -
     * real data with both a row that matches a level=ERROR&q=failed filter and one that
     * doesn't, so this proves both the restore AND the actual hide/show, not just one of
     * the two. (OrderReconciler's WARN-per-stale-order logs would be a more on-the-nose
     * fixture, but the test profile runs with flyway.enabled=false - see
     * application-test.yml - so V4__order-data.sql never seeds the orders it needs and it
     * logs "reconciling 0 orders".)
     *
     * <p>Fired per test through the scheduler's own runnable rather than relying on the
     * boot-time run: every /?error=true and /boom the concurrent classes issue pushes an
     * older error trace further down the list, so only a freshly minted one is guaranteed
     * to be found. The poll matches on the root span's name, which is what proves the
     * trace's spans - exported asynchronously, unlike its logs - have landed.
     */
    private String freshFixedRateSchedulerTraceId() {
        ScheduledJobs.run(scheduledTaskHolder, Scheduler.class, "fixedRate");
        openDashboard();
        return (String) page.evaluate("""
                async () => {
                    for (let attempt = 0; attempt < 150; attempt++) {
                        const response = await fetch('/peekaboot/api/traces/insights?bucket=errors&limit=50');
                        const result = await response.json();
                        const match = (result.traces || []).find(t => (t.rootSpan?.name || '').includes('fixedRate'));
                        if (match) return match.traceId;
                        await new Promise(resolve => setTimeout(resolve, 100));
                    }
                    throw new Error('no scheduler.fixedRate trace reached the errors bucket within 15s');
                }
                """);
    }

    @Test
    void deepLinkToATraceLogsTabRestoresTabAndFilters() {
        String traceId = freshFixedRateSchedulerTraceId();

        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/" + traceId + "/logs?level=ERROR&q=failed");
        overlay.waitFor("#pk-log-level");

        String selectedTab = overlay.selectedTab();
        String levelValue = (String) overlay.evaluate("root => root.querySelector('#pk-log-level').value");
        String textValue = (String) overlay.evaluate("root => root.querySelector('#pk-log-filter').value");

        assertThat(selectedTab).isEqualTo("logs");
        assertThat(levelValue).isEqualTo("ERROR");
        assertThat(textValue).isEqualTo("failed");

        // Every row visible in the DOM must actually match both filters, and every row
        // that matches must be visible - the same check applyFilters() itself enforces,
        // proven here via the real rendered pk-log--hidden classes on real fixture data
        // (one matching ERROR row, one non-matching INFO row - see the fixture's javadoc).
        Boolean everyRowConsistentWithTheFilters = (Boolean) overlay.evaluate("""
                root => [...root.querySelectorAll('.pk-log')]
                    .every(row => {
                        const matches = row.dataset.level === 'ERROR'
                            && row.querySelector('.pk-log__message').textContent.toLowerCase().includes('failed');
                        return matches !== row.classList.contains('pk-log--hidden');
                    })
                """);
        assertThat(everyRowConsistentWithTheFilters).isTrue();

        Boolean atLeastOneRowVisible = (Boolean) overlay.evaluate("""
                root => [...root.querySelectorAll('.pk-log')]
                    .some(row => !row.classList.contains('pk-log--hidden'))
                """);
        Boolean atLeastOneRowHidden = (Boolean) overlay.evaluate("""
                root => [...root.querySelectorAll('.pk-log')]
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
     * The push/replace rule (url-state.js): the overlay's tab strip
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
        String traceId = toolbar.traceId();

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
        overlay.openTab("logs");
        overlay.waitFor("#pk-log-level");

        overlay.evaluate(
                "root => { const select = root.querySelector('#pk-log-level'); select.value = 'ERROR'; select.dispatchEvent(new Event('change', {bubbles: true})); }");

        assertThat(page.url()).contains("level=");

        page.goBack();

        overlay.awaitClosed();
        assertThat(page.url()).endsWith("#traces");
    }

    /**
     * logs.js validates the URL's {@code level} param against the real {@code LEVELS} list
     * and falls back to '' for anything else (matching how an unrecognized subview falls
     * back to 'spans' in trace-detail.js), so the dropdown and the actual filtering agree.
     * Seeded verbatim into {@code state.level}, a typo'd, wrong-case, or stale value (a
     * malformed hand-edited link, or a level value from an older build) matches no {@code
     * <option>}: the browser shows "All Levels" selected while applyFilters() keeps
     * filtering by the bogus value underneath - a dropdown that visually claims "no
     * filter" while silently hiding every row.
     */
    @Test
    void deepLinkWithAnUnrecognizedLevelFallsBackToAllLevelsAndShowsEveryRow() {
        String traceId = freshFixedRateSchedulerTraceId();

        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/" + traceId + "/logs?level=BOGUS");
        overlay.waitFor("#pk-log-level");

        String levelValue = (String) overlay.evaluate("root => root.querySelector('#pk-log-level').value");
        assertThat(levelValue).isEqualTo("");

        Boolean anyRowHidden = (Boolean) overlay.evaluate("""
                root => [...root.querySelectorAll('.pk-log')]
                    .some(row => row.classList.contains('pk-log--hidden'))
                """);
        assertThat(anyRowHidden)
                .as("an unrecognized level should fall back to no filter at all, not hide every row")
                .isFalse();
    }

    /**
     * traces.js's own click-to-open path (openTrace(), the trace list's "open" button)
     * passes the same urlState factory main.js's expandTraceById uses
     * (context.traceUrlState) into overlay.open(), so a trace opened by clicking it - the
     * primary way anyone opens a trace - gets its tab switches and filter changes synced to
     * the URL just like the hash-driven paths (deep link, Back/Forward) do.
     * <p>
     * Also covers openTrace's onClose, which parses the hash and compares tab/detail only,
     * mirroring expandTraceById's onClose: an exact-string comparison against
     * "#traces/{traceId}" holds only while the overlay is still on its opening subview -
     * once a tab switch rewrites the hash to "#traces/{traceId}/{subview}" (replaceAppHash,
     * same as any hash-driven open) it stops matching, onClose's cleanup never fires, and
     * closing the overlay leaves the detail segment in the URL, so a reload would silently
     * reopen the trace on that subview.
     */
    @Test
    void clickingATraceRowThenSwitchingTabsUpdatesTheUrl() {
        openDashboard();
        page.click(".pk-tab[data-tab='traces']");
        page.waitForSelector("#traces-list .pk-trace-item");
        String traceId =
                (String) page.evaluate("() => document.querySelector('#traces-list .pk-trace-item').dataset.traceId");

        page.click("#traces-list .pk-trace-item__open");
        page.waitForFunction(
                "id => document.getElementById('peekaboot-trace-overlay')?.dataset.traceId === id", traceId);
        overlay.openTab("request");

        assertThat(page.url()).endsWith("#traces/" + traceId + "/request");

        page.keyboard().press("Escape");
        overlay.awaitClosed();

        assertThat(page.url()).endsWith("#traces");
    }

    /**
     * logs.js renders its controls from a `state` object: the span filter chip forces a
     * full re-render off container.innerHTML, and were the text/level inputs' values kept
     * only in the DOM, that re-render would silently wipe whatever the user had typed.
     */
    @Test
    void logsTextAndLevelFiltersSurviveChangingTheSpanFilter() {
        setStoredTheme("light");
        page.navigate(baseUrl + "/?error=true");
        toolbar.openOverlay();
        overlay.openLogsTab();

        overlay.evaluate(
                "root => { const input = root.querySelector('#pk-log-filter'); input.value = 'persons'; input.dispatchEvent(new Event('input', {bubbles: true})); }");

        overlay.click(".pk-log__span");
        overlay.waitFor(".pk-logs-filter-span");

        String textValue = (String) overlay.evaluate("root => root.querySelector('#pk-log-filter').value");
        assertThat(textValue).isEqualTo("persons");
    }
}

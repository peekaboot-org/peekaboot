package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.ColorScheme;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.integration.ScheduledJobs;
import org.peekaboot.testingapp.order.OrderReconciler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * Exercises the real trace-detail overlay served by the running app in a real browser.
 * The overlay shares the design system with the toolbar (see ToolbarIT) and follows the
 * same theme; a dark-only palette hardcoded on :host would open a hard-dark fullscreen
 * overlay over a light dashboard, which is what overlayIsLightWhenTheStoredPreferenceIsLight
 * guards.
 */
class TraceOverlayIT extends PlaywrightTestBase {

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    private void openOverlayFromToolbar() {
        openPersonsPage();
        toolbar.openOverlay();
    }

    /**
     * Polls the insights endpoint for the trace the toolbar currently tracks until its
     * spans carry logs on more than one span - shared by both the hash-route and
     * toolbar-path variants of the span-logs filter test below, since both need the
     * same real, non-vacuous precondition. It counts spans carrying logs the way spans.js
     * itself derives them (span.logs, walked down span.children), so the precondition is
     * measured against the very shape the Spans tab renders its "N logs" toggles from. The
     * poll lives in a single evaluate() rather than separate Java-side calls: a second,
     * separate fetch could race the trace's eviction from a bounded store.
     */
    private String waitForMultiSpanLogTraceId() {
        return (String) toolbar.evaluate("""
                async root => {
                    const spansWithLogs = span => !span ? 0
                        : ((span.logs || []).length > 0 ? 1 : 0)
                          + (span.children || []).reduce((n, child) => n + spansWithLogs(child), 0);
                    for (let attempt = 0; attempt < 150; attempt++) {
                        const copyEl = root.querySelector('#pk-trace .pk-copy');
                        const id = copyEl ? copyEl.dataset.pkCopy : null;
                        if (id) {
                            const response = await fetch('/peekaboot/api/traces/' + id + '/insights');
                            if (response.ok) {
                                const trace = await response.json();
                                if (spansWithLogs(trace.rootSpan) > 1) return id;
                            }
                        }
                        await new Promise(resolve => setTimeout(resolve, 100));
                    }
                    throw new Error('no trace with logs on more than one span arrived within 15s');
                }
                """);
    }

    /**
     * Opens the overlay for the index page's error-path trace - the one trace this app
     * produces whose logs are spread over more than one span: the request handler's own
     * ERROR log, and the INFO line PersonQueryService.findAll() writes inside its own
     * observed span. Returns that trace's id.
     *
     * <p>It opens through the dashboard's hash route rather than the toolbar, because
     * only that path supplies an urlState (main.js's expandTraceById -> buildTraceUrlState).
     * The toolbar calls openTraceDetail with none, so there every urlState write is a silent
     * no-op and the URL assertions below could not fail even if the wiring were deleted -
     * see spanLogsToggleOpensTheLogsTabFilteredToThatSpanFromTheToolbar below for the
     * toolbar-path counterpart, which asserts only the DOM hand-off for that reason.
     */
    private String openOverlayForTheMultiSpanLogTrace() {
        page.navigate(baseUrl + "/?error=true");
        toolbar.traceId();

        String traceId = waitForMultiSpanLogTraceId();

        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/" + traceId);
        overlay.waitFor("#pk-gantt-rows");
        return traceId;
    }

    /**
     * Headless Chromium's own default is prefers-color-scheme: light, so a naive
     * "storage wins" test in the light direction would pass even with resolveTheme()/
     * applyTheme() deleted entirely - light is also tokens.css's bare :root,:host default.
     * Forcing the OS preference to the opposite of what's stored (mirroring ToolbarIT)
     * makes each test fail if the stored preference ever stops taking priority.
     */
    private void emulateOppositeOsPreference(ColorScheme osPreference) {
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(osPreference));
    }

    /** A light dashboard must not open a dark overlay. */
    @Test
    void overlayIsLightWhenTheStoredPreferenceIsLight() {
        setStoredTheme("light");
        emulateOppositeOsPreference(ColorScheme.DARK);
        openOverlayFromToolbar();

        assertThat(overlay.cssVar("--pk-bg")).isEqualTo("#ffffff");
    }

    @Test
    void overlayIsDarkWhenTheStoredPreferenceIsDark() {
        setStoredTheme("dark");
        emulateOppositeOsPreference(ColorScheme.LIGHT);
        openOverlayFromToolbar();

        assertThat(overlay.cssVar("--pk-bg")).isEqualTo("#0d1117");
    }

    /**
     * Fills that reuse --pk-primary (this chip, the gantt "server" kind badge) or
     * --pk-success (the result-set row-count badge) for their background take the
     * contrast-tuned --pk-on-primary/--pk-on-success foreground that components.css's
     * .pk-badge uses for the same fills - --pk-text-strong there would be near-white text
     * on light-blue/light-green at ~2.3:1 in dark mode, against 8.2-8.3:1 for dark ink.
     * Pins the literal resolved colour rather than comparing against the --pk-on-primary
     * token itself, which would pass even if both sides regressed to the same wrong token.
     *
     * The pinned value is dark ink in both themes because --pk-primary is the brand green:
     * white on it measures 2.61:1, so not even the light theme can use plain white.
     *
     * Drives a real ERROR log entry (matching ToolbarIT's
     * toolbarShowsErrorLogCountWhenRequestLogsAnError) rather than the gantt "server" kind
     * badge: this test app's real request-capture path (RequestCaptureFilter /
     * TracingHandlerInterceptor) never tags the root span with an OpenTelemetry SERVER
     * kind - only OtelSpanExporter does that - so .pk-gantt-kind.server never actually
     * renders here, and a result-set row-count badge needs a JDBC instrumentation detail
     * this test has no reason to depend on. The logs-tab span-filter chip needs only one
     * real log entry attached to the trace, which /?error=true reliably provides.
     */
    @Test
    void logsFilterChipUsesTheContrastTunedForeground() {
        setStoredTheme("light");
        page.navigate(baseUrl + "/?error=true");
        toolbar.openOverlay();
        overlay.openLogsTab();
        overlay.click(".pk-log__span");
        overlay.waitFor(".pk-logs-filter-span");

        String color =
                (String) overlay.evaluate("root => getComputedStyle(root.querySelector('.pk-logs-filter-span')).color");

        assertThat(color).isEqualTo("rgb(13, 17, 23)");
    }

    /**
     * The testing app's spans are always named, so clicking any .pk-log__span chip
     * deterministically produces the "name (shortId)" form - see logs.js's task brief for
     * the unnamed/unresolvable fallback ("shortId" alone, full id in the title attribute),
     * which isn't reachable through this app's real trace data.
     */
    @Test
    void logsFilterChipShowsTheSpanNameWithItsShortenedId() {
        setStoredTheme("light");
        page.navigate(baseUrl + "/?error=true");
        toolbar.openOverlay();
        overlay.openLogsTab();
        overlay.click(".pk-log__span");
        overlay.waitFor(".pk-logs-filter-span");

        String chipText = overlay.text(".pk-logs-filter-span");

        assertThat(chipText.trim()).matches("^Span: .+\\([0-9a-f]{8}\\)\\s*×?$");
    }

    @Test
    void overlayShowsSpansTabByDefault() {
        openOverlayFromToolbar();

        String selected = overlay.selectedTab();
        assertThat(selected).isEqualTo("spans");
    }

    @Test
    void escapeClosesTheOverlay() {
        openOverlayFromToolbar();

        page.keyboard().press("Escape");

        overlay.awaitClosed();
        assertThat(page.querySelector("#peekaboot-trace-overlay")).isNull();
    }

    /** role=dialog + aria-modal, and a real accessible name, not just visual chrome. */
    @Test
    void overlayExposesDialogSemantics() {
        openOverlayFromToolbar();

        String role = (String) overlay.evaluate("root => root.querySelector('.pk-overlay').getAttribute('role')");
        String ariaModal =
                (String) overlay.evaluate("root => root.querySelector('.pk-overlay').getAttribute('aria-modal')");
        String accessibleName = (String)
                overlay.evaluate(
                        "root => { const el = root.querySelector('.pk-overlay'); const labelledBy = el.getAttribute('aria-labelledby'); return labelledBy ? el.getRootNode().getElementById(labelledBy).textContent.trim() : el.getAttribute('aria-label'); }");

        assertThat(role).isEqualTo("dialog");
        assertThat(ariaModal).isEqualTo("true");
        assertThat(accessibleName).isNotBlank();
    }

    /**
     * Opening the overlay from a keyboard-focused toolbar button must move focus into the
     * overlay, and closing it (ESC) must return focus to that same button - otherwise a
     * keyboard user opens a fullscreen overlay while focus silently stays behind it.
     */
    @Test
    void focusMovesIntoTheOverlayOnOpenAndReturnsToTheInvokerOnClose() {
        openPersonsPage();
        toolbar.traceId();
        toolbar.evaluate("root => root.querySelector('.pk-toolbar__open').focus()");
        page.keyboard().press("Enter");
        page.waitForSelector("#peekaboot-trace-overlay");
        // container.focus() only happens once render() actually runs (after the trace
        // fetch and shared stylesheets both resolve) - wait for real content so the
        // assertion below cannot race a still-loading overlay.
        overlay.waitFor(".pk-tab");

        boolean focusIsInsideOverlay = (Boolean) overlay.evaluate("root => root.activeElement !== null");
        assertThat(focusIsInsideOverlay).isTrue();

        page.keyboard().press("Escape");
        overlay.awaitClosed();

        boolean focusIsBackOnTheInvoker = (Boolean)
                toolbar.evaluate("root => root.activeElement?.classList.contains('pk-toolbar__open') ?? false");
        assertThat(focusIsBackOnTheInvoker).isTrue();
    }

    /**
     * Forces the overlay's error path (a real network failure, not a mocked response) and
     * proves its Close button actually works. The button must not rely on
     * this.closest('#peekaboot-trace-overlay') from inside the shadow root: closest() cannot
     * cross the shadow boundary, so such a button throws and does nothing.
     */
    @Test
    void closeButtonDismissesTheOverlayOnTheErrorPath() {
        page.route("**/api/traces/*/insights", route -> route.abort());
        openOverlayFromToolbar();

        overlay.waitFor(".pk-overlay__error");
        overlay.click(".pk-overlay__error button");

        overlay.awaitClosed();
        assertThat(page.querySelector("#peekaboot-trace-overlay")).isNull();
    }

    /**
     * The overlay's own four-tab strip is built by the same shared tabStrip() helper
     * as the dashboard's - a real ArrowRight keypress (not a direct handler call) must
     * move both the DOM focus and the aria-selected tab from Spans to Queries.
     */
    @Test
    void overlayTabStripIsKeyboardNavigable() {
        openOverlayFromToolbar();
        overlay.evaluate("root => root.querySelector('.pk-tab[data-tab=\"spans\"]').focus()");

        page.keyboard().press("ArrowRight");

        String focused = (String) overlay.evaluate("root => root.activeElement?.dataset.tab");
        String selected = overlay.selectedTab();

        assertThat(focused).isEqualTo("queries");
        assertThat(selected).isEqualTo("queries");

        String content = (String) overlay.evaluate("root => root.querySelector('#pk-tab-content').innerHTML");
        assertThat(content).isNotEmpty();
    }

    /**
     * The ARIA tabs pattern needs both halves: a tab that says which panel it controls,
     * and a panel that says which tab labels it - otherwise a screen reader announces
     * "tab 2 of 4" with no relationship to what changes. The strip is built at runtime by
     * tabStrip(), so it has to wire this itself; the dashboard's static strip carries the
     * same attributes in its markup.
     */
    @Test
    void overlayTabsAndTheirContentPanelPointAtEachOther() {
        openOverlayFromToolbar();

        assertThat((String) overlay.evaluate("root => root.querySelector('#pk-tab-content').getAttribute('role')"))
                .isEqualTo("tabpanel");
        assertThat((Boolean) overlay.evaluate("root => [...root.querySelectorAll('.pk-tab')]"
                        + ".every(tab => tab.id && tab.getAttribute('aria-controls') === 'pk-tab-content')"))
                .as("every tab controls the one content panel")
                .isTrue();
        assertThat(labelledBy()).isEqualTo(selectedTabId());

        overlay.openTab("queries");

        assertThat(selectedTabId()).endsWith("queries");
        assertThat(labelledBy()).as("the panel's label follows the selection").isEqualTo(selectedTabId());
    }

    private String labelledBy() {
        return (String)
                overlay.evaluate("root => root.querySelector('#pk-tab-content').getAttribute('aria-labelledby')");
    }

    private String selectedTabId() {
        return (String) overlay.evaluate("root => root.querySelector('.pk-tab[aria-selected=\"true\"]').id");
    }

    /** Only the selected main tab stays in the tab order - roving tabindex. */
    @Test
    void onlyTheSelectedOverlayTabIsInTheTabOrder() {
        openOverlayFromToolbar();

        Object selectedTabIndex =
                overlay.evaluate("root => root.querySelector('.pk-tab[aria-selected=\"true\"]').tabIndex");
        Object otherTabIndex =
                overlay.evaluate("root => root.querySelector('.pk-tab[aria-selected=\"false\"]').tabIndex");

        assertThat(selectedTabIndex).isEqualTo(0);
        assertThat(otherTabIndex).isEqualTo(-1);
    }

    /**
     * Inspects the real accessibility tree for the overlay's strip too - confirms it
     * exposes as an actual tablist with the right tabs and selected state, same as
     * the dashboard's equivalent check. The tab counts come from TABS.count(trace)
     * through the shared tabStrip(); hand-rolled markup computing its own queryCount
     * would render an identical "Queries 1", so this snapshot alone does not prove the
     * shared path is load-bearing - only render() carrying no duplicate template text
     * does. Also pins the spans tab's own count badge, computed from the same
     * endpoint TABS.count(trace) reads (trace.summary.spans.count) rather than a
     * hardcoded literal, so a real change to the trace's span count still passes.
     */
    @Test
    void overlayTabStripExposesAsARealTablistInTheAccessibilityTree() {
        // This is the one test that asserts the QUERIES COUNT rendered into the tab
        // strip, and that count races span ingestion: the toolbar shows the trace id
        // as soon as the response arrives, but the SQL query span reaches the trace
        // store asynchronously after the response is written. On a fast machine the
        // store wins; on slower ones (observed on macOS) the overlay fetch can read
        // the trace before its query landed and render "Queries 0". Wait for the
        // backend to actually serve the query before opening the overlay - the same
        // endpoint and field the overlay's TABS.count reads (trace.queries).
        openPersonsPage();
        toolbar.traceId();
        // Polls (inside one evaluate(), not a separate waitForFunction + a later re-fetch)
        // until the query span lands, then returns the span count from that very same
        // response - both to dodge the ingestion race documented above, and to read the
        // count from the exact same JSON payload the "queries present" check just parsed,
        // rather than a second independent fetch that could race the trace being evicted
        // from the store (a bounded ring buffer under constant pressure from this app's
        // own background scheduler). Reads the id from the copy button's data-pk-copy
        // attribute - #pk-trace's own textContent is "traceId<hex>⧉" (label + icon baked
        // in by copyableIdHtml), not the bare id a URL path segment needs.
        int spanCount = ((Number) toolbar.evaluate("async root => {"
                        + "for (let i = 0; i < 150; i++) {"
                        + "  const copyEl = root.querySelector('#pk-trace .pk-copy');"
                        + "  const id = copyEl ? copyEl.dataset.pkCopy : null;"
                        + "  if (id) {"
                        + "    const response = await fetch('/peekaboot/api/traces/' + id + '/insights');"
                        + "    if (response.ok) {"
                        + "      const trace = await response.json();"
                        + "      if ((trace.queries || []).length > 0) {"
                        + "        return trace.summary?.spans?.count ?? 0;"
                        + "      }"
                        + "    }"
                        + "  }"
                        + "  await new Promise(r => setTimeout(r, 100));"
                        + "}"
                        + "throw new Error('query span never arrived within 15s');"
                        + "}"))
                .intValue();
        // Not openOverlayFromToolbar(): that helper re-navigates, which would mint a
        // fresh trace and reopen the very race waited out above. Open the overlay for
        // the already-verified trace directly.
        toolbar.openOverlay();
        overlay.openTab("queries");

        Locator tablist = page.locator("#peekaboot-trace-overlay .pk-overlay__container > .pk-tabs");
        String snapshot = tablist.ariaSnapshot();

        assertThat(snapshot).contains("tablist");
        assertThat(snapshot).contains("\"Spans " + spanCount + "\"");
        // The " 1" is the queries count TABS.count(trace) computes for this real trace -
        // pins that count is actually rendered into the tab, not just present in TABS.
        assertThat(snapshot).contains("\"Queries 1\" [selected]");
    }

    /**
     * The header pill is the one place a status code is read at a glance, so it carries
     * the reason phrase rather than a bare number. The testing app's /persons page
     * answers 200, so that is the phrase asserted here.
     */
    @Test
    void theHeaderStatusPillSpellsOutTheReasonPhrase() {
        openOverlayFromToolbar();

        String status = (String)
                overlay.evaluate("root => root.querySelector('.pk-overlay__meta .pk-badge').textContent.trim()");

        assertThat(status).isEqualTo("200 OK");
    }

    @Test
    void everyOverlayTabRendersContent() {
        openOverlayFromToolbar();

        for (String tab : List.of("request", "spans", "queries", "logs")) {
            overlay.evaluate("(root, id) => root.querySelector(`.pk-tab[data-tab=\"${id}\"]`).click()", tab);

            String content = (String) overlay.evaluate("root => root.querySelector('#pk-tab-content').innerHTML");
            assertThat(content).as("tab %s renders something", tab).isNotEmpty();
        }
    }

    /**
     * A copyable full-length span id on every span-tree row would crowd the tree, so the
     * id lives on the Logs tab's rows instead (see logsTableRendersCopyableSpanIds and
     * clickingTheLogSpanIdCopiesItWithoutFiltering in CopyableIdIT). A row keeps its span
     * name, duration, badges and the logs/SQL toggles - just not a copy control.
     */
    @Test
    void spanTreeRowsDoNotRenderACopyableSpanId() {
        openOverlayFromToolbar();

        boolean anyRowHasACopyControl =
                (boolean) overlay.evaluate("root => !!root.querySelector('#pk-gantt-rows .pk-copy')");

        assertThat(anyRowHasACopyControl).isFalse();
    }

    /**
     * The Spans tab's per-span "N logs" toggle hands off to the Logs tab's own span filter
     * - switch the overlay to the Logs tab, seed its span filter, and rely on the filter
     * chip's own clear button for a reversible "back to all logs" - rather than opening a
     * popup of its own, which would only duplicate the Logs tab's row renderer.
     *
     * <p>Runs against a real captured trace with nothing stubbed. The index page's error
     * path writes its ERROR log inside the request handler's span while
     * PersonQueryService.findAll() writes an INFO line inside its own observed span, so
     * the trace's logs genuinely sit on two different spans. That spread is what keeps
     * both halves of this test from holding vacuously - filtering to one span has to
     * actually hide something, and clearing has to actually bring something back - so the
     * premise is asserted before it is relied on rather than assumed.
     *
     * <p>Also pins that the hand-off is a real, shareable location and not just a DOM
     * mutation: goToSpanLogs writes the span into the hash through the very same urlState
     * seam a "?span=..." deep link is restored from, so the filtered view can be linked to
     * and Back-navigated like any other, and clearing the filter takes the param back out.
     */
    @Test
    void spanLogsToggleOpensTheLogsTabFilteredToThatSpanAndTheFilterIsClearable() {
        String traceId = openOverlayForTheMultiSpanLogTrace();

        @SuppressWarnings("unchecked")
        List<String> spansOfferingLogs = (List<String>)
                page.evalOnSelectorAll(".pk-span-logs-toggle", "els => els.map(el => el.dataset.spanId)");
        assertThat(spansOfferingLogs)
                .as("the Spans tab must offer a logs toggle per logging span - the helper already "
                        + "waited for the backend to serve more than one, so a shortfall here is the "
                        + "tree failing to render them, not ingestion still catching up")
                .hasSizeGreaterThan(1);
        String spanId = spansOfferingLogs.getFirst();

        page.click(".pk-span-logs-toggle[data-span-id='" + spanId + "']");

        overlay.waitUntil("root => root.querySelector('.pk-tab[aria-selected=\"true\"]')?.dataset.tab === 'logs'");
        assertThat(page.url())
                .as("the hand-off is a real location, not just a DOM change - the same hash shape a "
                        + "deep link into this filtered view would use")
                .contains("#traces/" + traceId + "/logs?span=" + spanId);
        String focusedTab = (String) overlay.evaluate("root => root.activeElement?.dataset.tab");
        assertThat(focusedTab)
                .as("the clicked toggle belonged to the Spans tab's markup, which the tab switch just "
                        + "replaced, destroying it - focus must move deliberately to the Logs tab's own "
                        + "button rather than falling back to the shadow host")
                .isEqualTo("logs");
        String content = (String) overlay.evaluate("root => root.querySelector('#pk-tab-content').innerHTML");
        assertThat(content).as("no popup - the Logs tab itself rendered").contains("pk-logs-list");

        page.waitForSelector(".pk-log:not(.pk-log--hidden)");
        @SuppressWarnings("unchecked")
        List<String> visibleSpanIds = (List<String>)
                page.evalOnSelectorAll(".pk-log:not(.pk-log--hidden)", "els => els.map(el => el.dataset.spanId)");
        assertThat(visibleSpanIds)
                .as("only the span the toggle was clicked for stays visible")
                .containsOnly(spanId);
        assertThat(page.isVisible(".pk-logs-filter-span"))
                .as("the filtered state is obvious, not just an invisible internal flag")
                .isTrue();

        page.click("#pk-clear-span-filter");

        overlay.waitForGone(".pk-logs-filter-span");
        @SuppressWarnings("unchecked")
        List<String> visibleAfterClear = (List<String>)
                page.evalOnSelectorAll(".pk-log:not(.pk-log--hidden)", "els => els.map(el => el.dataset.spanId)");
        assertThat(visibleAfterClear).contains(spanId);
        assertThat(Set.copyOf(visibleAfterClear))
                .as("clearing the filter is reversible - the other spans' logs are back too")
                .hasSizeGreaterThan(1);
        assertThat(page.url())
                .as("clearing takes the param back out, so the URL never claims a filter that is "
                        + "no longer applied")
                .doesNotContain("span=");
    }

    /**
     * The cheap DOM-only counterpart to spanLogsToggleOpensTheLogsTabFilteredToThatSpanAndTheFilterIsClearable
     * above, covering the toolbar-open path rather than the hash route. The toolbar calls
     * openTraceDetail with no urlState at all (see openOverlayForTheMultiSpanLogTrace's own
     * javadoc), so goToSpanLogs's urlState?.update is a silent no-op there and the URL never
     * changes by design - there is nothing to assert about it on this path, only the DOM
     * hand-off itself.
     */
    @Test
    void spanLogsToggleOpensTheLogsTabFilteredToThatSpanFromTheToolbar() {
        page.navigate(baseUrl + "/?error=true");
        toolbar.traceId();
        waitForMultiSpanLogTraceId();

        toolbar.openOverlay();

        @SuppressWarnings("unchecked")
        List<String> spansOfferingLogs = (List<String>)
                page.evalOnSelectorAll(".pk-span-logs-toggle", "els => els.map(el => el.dataset.spanId)");
        assertThat(spansOfferingLogs).hasSizeGreaterThan(1);
        String spanId = spansOfferingLogs.getFirst();

        page.click(".pk-span-logs-toggle[data-span-id='" + spanId + "']");

        overlay.waitUntil("root => root.querySelector('.pk-tab[aria-selected=\"true\"]')?.dataset.tab === 'logs'");

        page.waitForSelector(".pk-log:not(.pk-log--hidden)");
        @SuppressWarnings("unchecked")
        List<String> visibleSpanIds = (List<String>)
                page.evalOnSelectorAll(".pk-log:not(.pk-log--hidden)", "els => els.map(el => el.dataset.spanId)");
        assertThat(visibleSpanIds)
                .as("only the span the toggle was clicked for stays visible")
                .containsOnly(spanId);
    }

    /**
     * Cross-link: a span the backend classified as a query (span.query present) carries a
     * link to its entry in the Queries tab. The jump switches the overlay tab, moves
     * keyboard focus onto the target entry and marks it with a temporary highlight class,
     * so the eye lands where focus just went. Runs on the toolbar-open path - the jump is
     * pure DOM state and identical on every open path.
     */
    @Test
    void spanQueryLinkJumpsToTheQueriesTabEntry() {
        openOverlayFromToolbar();
        overlay.waitFor(".pk-span-query-link");
        String spanId = (String) overlay.evaluate("root => root.querySelector('.pk-span-query-link').dataset.spanId");

        overlay.click(".pk-span-query-link");

        overlay.waitUntil("root => root.querySelector('.pk-tab[aria-selected=\"true\"]')?.dataset.tab === 'queries'");
        overlay.waitFor(".pk-query-item.pk-jump-flash");
        String highlighted =
                (String) overlay.evaluate("root => root.querySelector('.pk-query-item.pk-jump-flash')?.dataset.spanId");
        assertThat(highlighted).isEqualTo(spanId);
        Boolean focusOnTarget =
                (Boolean) overlay.evaluate("root => root.activeElement?.classList.contains('pk-query-item') ?? false");
        assertThat(focusOnTarget)
                .as("focus moves with the jump - the clicked link's markup was just replaced")
                .isTrue();

        // temporary by design: the highlight clears on its own, the focus stays
        overlay.waitForGone(".pk-jump-flash");
    }

    /**
     * Cross-link in the other direction: each Queries-tab entry links back to its span in
     * the Spans tab's tree - the row is scrolled to, focused and temporarily highlighted,
     * mirroring spanQueryLinkJumpsToTheQueriesTabEntry above.
     */
    @Test
    void queryEntrySpanLinkJumpsBackToItsSpanRow() {
        openOverlayFromToolbar();
        overlay.openTab("queries");
        overlay.waitFor(".pk-query-span-link");
        String spanId = (String) overlay.evaluate("root => root.querySelector('.pk-query-span-link').dataset.spanId");

        overlay.click(".pk-query-span-link");

        overlay.waitUntil("root => root.querySelector('.pk-tab[aria-selected=\"true\"]')?.dataset.tab === 'spans'");
        overlay.waitFor(".pk-gantt-row.pk-jump-flash");
        String highlighted =
                (String) overlay.evaluate("root => root.querySelector('.pk-gantt-row.pk-jump-flash')?.dataset.spanId");
        assertThat(highlighted).isEqualTo(spanId);
        String focusedRowSpanId =
                (String) overlay.evaluate("root => root.activeElement?.closest('.pk-gantt-row')?.dataset.spanId");
        assertThat(focusedRowSpanId).isEqualTo(spanId);
    }

    /**
     * Cross-link from the Logs tab: beside the existing filter-to-span button, each log
     * row links to its span in the Spans tab's tree the same way the Queries tab does.
     */
    @Test
    void logRowSpanLinkJumpsToTheSpanTree() {
        page.navigate(baseUrl + "/?error=true");
        toolbar.traceId();
        toolbar.openOverlay();
        overlay.openLogsTab();
        overlay.waitFor(".pk-log__goto-span");
        String spanId = (String) overlay.evaluate("root => root.querySelector('.pk-log__goto-span').dataset.spanId");

        overlay.click(".pk-log__goto-span");

        overlay.waitUntil("root => root.querySelector('.pk-tab[aria-selected=\"true\"]')?.dataset.tab === 'spans'");
        overlay.waitFor(".pk-gantt-row.pk-jump-flash");
        String highlighted =
                (String) overlay.evaluate("root => root.querySelector('.pk-gantt-row.pk-jump-flash')?.dataset.spanId");
        assertThat(highlighted).isEqualTo(spanId);
        String focusedRowSpanId =
                (String) overlay.evaluate("root => root.activeElement?.closest('.pk-gantt-row')?.dataset.spanId");
        assertThat(focusedRowSpanId).isEqualTo(spanId);
    }

    @Test
    void queriesTabListsTheJdbcQueryFromThePersonsPage() {
        openOverlayFromToolbar();
        overlay.click(".pk-tab[data-tab=\"queries\"]");

        String sql = (String) overlay.evaluate("root => root.querySelector('.pk-query__sql')?.textContent ?? ''");
        assertThat(sql.toLowerCase(Locale.ROOT)).contains("select");
    }

    /**
     * Each span's duration cell shows the duration the way every other surface formats
     * one (formatDurationMs - "250ms", "1.50s") plus its share of the whole trace's
     * duration, and the gantt header's tick marks line up with the row tracks below
     * them - both track and header timeline carry the same side margin, so the 0%/100%
     * ticks sit right above the start/end of the bars they describe rather than further out.
     */
    @Test
    void spansTabShowsPercentOfTotalTraceTimeNextToEachDuration() {
        openOverlayFromToolbar();

        Object allDurationsMatchPattern = overlay.evaluate(
                "root => Array.from(root.querySelectorAll('.pk-gantt-duration'))"
                        + ".every(el => /^(<1ms|\\d+ms|\\d+\\.\\d{2}[sm]) \\u00B7 \\d{1,3}%$/.test(el.textContent.trim()))");
        assertThat((Boolean) allDurationsMatchPattern)
                .as("every duration cell reads '<duration> \u00B7 <pct>%'")
                .isTrue();

        BoundingBox headerBox = page.locator("#peekaboot-trace-overlay .pk-gantt-header__timeline")
                .boundingBox();
        BoundingBox trackBox = page.locator("#peekaboot-trace-overlay .pk-gantt-row")
                .first()
                .locator(".pk-gantt-track")
                .boundingBox();

        assertThat(headerBox.x)
                .as("header timeline's left edge lines up with the first row's track")
                .isCloseTo(trackBox.x, Offset.offset(1.0));
        assertThat(headerBox.x + headerBox.width)
                .as("header timeline's right edge lines up with the first row's track")
                .isCloseTo(trackBox.x + trackBox.width, Offset.offset(1.0));
    }

    /**
     * The SLOW label reads severity.js's querySeverity() - the query threshold behind the
     * backend's SLOW_QUERY issue (slowQueryThresholdMs, default 50) - not the span
     * thresholds, and not a bare literal re-derived on the same line. Imports queries.js
     * directly (SharedModuleIT's pk-blank.html pattern) rather than driving a real slow
     * query through the app, and pins the exact boundary (49ms must NOT get the label;
     * 50ms - the threshold itself, IssueDetector compares with >= - must), for the
     * fallback and for a published threshold alike (see severity.js's own boundary tests
     * in SharedModuleIT).
     */
    @Test
    void queriesTabSlowLabelFollowsTheQueryThresholdAtTheBoundary() {
        page.navigate(baseUrl + "/peekaboot/ui/pk-blank.html");

        Object labels = page.evaluate("""
            async () => {
                const m = await import('/peekaboot/ui/trace-detail/tabs/queries.js');
                const queries = [
                    {sql: 'SELECT 1', durationMs: 49, dbSystem: 'h2', rowCount: 1},
                    {sql: 'SELECT 2', durationMs: 50, dbSystem: 'h2', rowCount: 1}
                ];
                const fallback = document.createElement('div');
                m.render(fallback, {queries});
                const published = document.createElement('div');
                m.render(published, {queries}, {features: {slowQueryThresholdMs: 51}});
                const labelsIn = el =>
                    Array.from(el.querySelectorAll('.pk-query__duration')).map(cell => cell.textContent);
                return [...labelsIn(fallback), ...labelsIn(published)];
            }
            """);

        @SuppressWarnings("unchecked")
        List<String> durationLabels = (List<String>) labels;
        assertThat(durationLabels).containsExactly("49ms", "50ms SLOW", "49ms", "50ms");
    }

    /**
     * .pk-overlay__close sits in the header's own flex flow next to a
     * .pk-overlay__header-main wrapper, so it cannot drift from the title's first line.
     * Positioned absolutely against .pk-overlay__container, with the title carrying a
     * margin-right hack to fake reserving space for the button, they would be two
     * independent layouts that only look aligned by coincidence - and drift the moment the
     * title's UA margin-top pushes it down without moving the absolutely-positioned button.
     *
     * <p>Close is the header's one dismiss control: the overlay is a dialog, not a page,
     * so a "Back" beside it would only collide with the browser's own Back, which the
     * dashboard's hash routing handles separately.
     */
    @Test
    void overlayHeaderKeepsCloseInTheFlowAlignedWithTheTitle() {
        openOverlayFromToolbar();

        assertThat((String)
                        overlay.evaluate("root => getComputedStyle(root.querySelector('.pk-overlay__close')).position"))
                .isEqualTo("static");
        assertThat((Boolean) overlay.evaluate("root => !!root.querySelector('.pk-overlay__back')"))
                .as("no second dismiss control")
                .isFalse();

        BoundingBox closeBox =
                page.locator("#peekaboot-trace-overlay .pk-overlay__close").boundingBox();
        BoundingBox titleBox =
                page.locator("#peekaboot-trace-overlay .pk-overlay__title").boundingBox();

        assertThat(closeBox.y)
                .as("close button top should be within the title's vertical span")
                .isLessThan(titleBox.y + titleBox.height);
        assertThat(closeBox.y + closeBox.height)
                .as("close button bottom should overlap the title's vertical span")
                .isGreaterThan(titleBox.y);
    }

    /**
     * Polls the traces list API - the same one the dashboard's Traces tab reads - for a
     * trace Peekaboot itself classified SCHEDULED_JOB, rather than asserting against
     * anything this test constructed. Whichever trace turns up first is fair game: the
     * assertions below hold for any correctly-captured scheduled-job trace, not
     * specifically the one the test just fired, so a trace left behind
     * by an earlier test in this JVM's shared Spring context is just as valid a fixture.
     */
    private String waitForScheduledJobTraceId() {
        page.navigate(baseUrl + "/persons");
        return (String) page.evaluate("""
                async () => {
                    for (let attempt = 0; attempt < 150; attempt++) {
                        const response = await fetch(
                            '/peekaboot/api/traces/insights?bucket=all&rootActionType=SCHEDULED_JOB');
                        if (response.ok) {
                            const body = await response.json();
                            const trace = (body.traces || [])[0];
                            if (trace) return trace.traceId;
                        }
                        await new Promise(resolve => setTimeout(resolve, 100));
                    }
                    throw new Error('no SCHEDULED_JOB trace arrived within 15s');
                }
                """);
    }

    /**
     * On a non-HTTP trace (a scheduled job here) trace-detail.js's method falls back to
     * null, which the header renders as the trace's root-action label (root-actions.js):
     * httpExchange/http.* tags are only ever populated for real HTTP requests, so a
     * hardcoded 'UNKNOWN' fallback would be a fake method.
     *
     * <p>Drives a real {@link OrderReconciler#reconcileOrders()} run through Spring's own
     * scheduled-task observation (see {@link ScheduledJobs}) rather than stubbing
     * the insights endpoint with a canned response - the fix is about what real
     * classification data the header renders, so a hand-built trace object would only
     * prove the header can read JSON, not that the classification it depends on ever
     * happens. Also covers the "1 query" pluralisation on the same header
     * (formatCount() in format.js): reconcileOrders() calls orderRepository.findAll()
     * exactly once, and CustomerOrder is a flat entity with no lazy associations to
     * trigger further queries, so the trace's query count is deterministically 1
     * regardless of how many orders exist when the test runs.
     */
    @Test
    void overlayHeaderShowsTheRootActionLabelForNonHttpTraces() {
        ScheduledJobs.run(scheduledTaskHolder, OrderReconciler.class, "reconcileOrders");
        String traceId = waitForScheduledJobTraceId();

        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/" + traceId);
        overlay.waitFor(".pk-overlay__title-method");

        String methodText = overlay.text(".pk-overlay__title-method");
        assertThat(methodText)
                .as("no HTTP method exists for a scheduled job, so the header must fall back to "
                        + "the root-action label rather than a fake method")
                .isEqualTo("Scheduled Job");

        String metaText = overlay.text(".pk-overlay__meta");
        assertThat(metaText)
                .as("reconcileOrders() issues exactly one query (CustomerOrder is a flat entity, so "
                        + "findAll() is a single SELECT regardless of row count) - the count is "
                        + "deterministic, not just usually 1")
                .contains("1 query")
                .doesNotContain("1 queries");
    }
}

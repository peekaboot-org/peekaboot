package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.WaitForSelectorState;
import io.micrometer.tracing.Span;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.peekaboot.testingapp.integration.ScheduledJobs;
import org.peekaboot.testingapp.integration.TestSpans;
import org.peekaboot.testingapp.order.OrderReconciler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exercises the real trace-detail overlay served by the running app in a real browser.
 * The overlay shares the design system with the toolbar (see ToolbarIT) and follows the
 * same theme; a dark-only palette hardcoded on :host would open a hard-dark fullscreen
 * overlay over a light dashboard, which is what overlayIsLightWhenTheStoredPreferenceIsLight
 * guards.
 */
class TraceOverlayIT extends PlaywrightTestBase {

    /** The colour of the stand-in page header {@link #overlayStacksAboveHostPageChrome} puts on the page. */
    private static final int HOST_HEADER_RGB = 0x123456;

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    @Autowired
    private TraceStore traceStore;

    private void openOverlayFromToolbar() {
        openPersonsPage();
        toolbar.openOverlay();
    }

    /**
     * A host page's own chrome routinely stacks above the page flow - Bulma's {@code .navbar}
     * is {@code z-index: 30}, Bootstrap's {@code .fixed-top} is {@code 1030}. The overlay host
     * is appended to {@code document.body}, so it has to outrank that on its own or the page's
     * header paints across the open overlay and hides the overlay's own header row.
     *
     * <p>Asserted on the painted pixel rather than on hit testing: the overlay makes its
     * siblings {@code inert}, which already stops them taking clicks, so
     * {@code elementFromPoint} names the overlay either way and only paint order tells the two
     * states apart.
     */
    @Test
    void overlayStacksAboveHostPageChrome() throws IOException {
        openPersonsPage();
        page.evaluate("""
                () => {
                    const header = document.createElement('div');
                    header.id = 'host-page-header';
                    header.style.cssText =
                        'position:fixed;top:0;left:0;right:0;height:60px;z-index:30;background:#123456';
                    document.body.prepend(header);
                }""");

        toolbar.openOverlay();

        BufferedImage screen = ImageIO.read(new ByteArrayInputStream(page.screenshot()));
        int painted = screen.getRGB(screen.getWidth() / 2, screen.getHeight() / 20) & 0xFFFFFF;
        assertThat(painted)
                .as("the host page's header is painting over the open overlay")
                .isNotEqualTo(HOST_HEADER_RGB);
    }

    /**
     * Waits until the trace the toolbar currently tracks carries logs on more than one span,
     * and returns its id - shared by both the hash-route and toolbar-path variants of the
     * span-logs filter test below, since both need the same real, non-vacuous precondition.
     * It counts spans carrying logs the way spans.js itself derives them (span.logs, walked
     * down span.children), so the precondition is measured against the very shape the Spans
     * tab renders its "N logs" toggles from.
     */
    private String waitForMultiSpanLogTraceId() {
        String traceId = toolbar.traceId();
        awaitTrace(traceId, """
                trace => {
                    const spansWithLogs = span => !span ? 0
                        : ((span.logs || []).length > 0 ? 1 : 0)
                          + (span.children || []).reduce((n, child) => n + spansWithLogs(child), 0);
                    return spansWithLogs(trace.rootSpan) > 1;
                }
                """);
        return traceId;
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
        openPageThatLogsAnError();

        String traceId = waitForMultiSpanLogTraceId();

        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/" + traceId);
        overlay.waitFor("#pk-gantt-rows");
        return traceId;
    }

    /**
     * A light dashboard must not open a dark overlay. The OS preference is set to the
     * opposite of what is stored (see emulateOsColorScheme), or the light case would pass
     * with resolveTheme()/applyTheme() deleted: light is also tokens.css's bare default.
     */
    @Test
    void overlayIsLightWhenTheStoredPreferenceIsLight() {
        setStoredTheme("light");
        emulateOsColorScheme(ColorScheme.DARK);
        openOverlayFromToolbar();

        assertThat(overlay.cssVar("--pk-bg")).isEqualTo("#ffffff");
    }

    @Test
    void overlayIsDarkWhenTheStoredPreferenceIsDark() {
        setStoredTheme("dark");
        emulateOsColorScheme(ColorScheme.LIGHT);
        openOverlayFromToolbar();

        assertThat(overlay.cssVar("--pk-bg")).isEqualTo("#0d1117");
    }

    /**
     * The Logs tab's span-filter chip reuses --pk-primary for its background, so it takes
     * the contrast-tuned --pk-on-primary foreground that components.css's .pk-badge uses
     * for the same fill - --pk-text-strong there would be near-white text on light green at
     * ~2.3:1 in dark mode, against 8.2-8.3:1 for dark ink.
     * Pins the literal resolved colour rather than comparing against the --pk-on-primary
     * token itself, which would pass even if both sides regressed to the same wrong token.
     *
     * The pinned value is dark ink in both themes because --pk-primary is the brand green:
     * white on it measures 2.61:1, so not even the light theme can use plain white.
     *
     * Drives a real ERROR log entry (matching ToolbarIT's
     * toolbarShowsErrorLogCountWhenRequestLogsAnError): the chip needs only one real log
     * entry attached to the trace, which openPageThatLogsAnError() guarantees.
     */
    @Test
    void logsFilterChipUsesTheContrastTunedForeground() {
        setStoredTheme("light");
        openPageThatLogsAnError();
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
        openPageThatLogsAnError();
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

    /**
     * Escape has to reach the overlay from the moment it opens, not from its first render:
     * between the two the reader is looking at a loading dialog with the whole page behind
     * it inert, which is exactly when they reach for Escape.
     *
     * <p>Every insights request is parked - held, not stubbed - rather than only the
     * overlay's: the toolbar's own fetch ladder reads the same URL, and holding them all is
     * what keeps the overlay on its loading placeholder for the length of the press.
     */
    @Test
    void escapeClosesAnOverlayWhoseTraceHasNotArrivedYet() {
        openPersonsPage();
        toolbar.traceId();
        toolbar.evaluate("root => root.querySelector('.pk-toolbar__open').focus()");
        List<Route> parkedTraceRequests = new CopyOnWriteArrayList<>();
        page.route("**/api/traces/*/insights", parkedTraceRequests::add);

        page.keyboard().press("Enter");
        overlay.awaitOpened();
        overlay.waitFor(".pk-overlay__loading");
        page.keyboard().press("Escape");

        overlay.awaitClosed();
        boolean focusIsBackOnTheInvoker = (Boolean)
                toolbar.evaluate("root => root.activeElement?.classList.contains('pk-toolbar__open') ?? false");
        assertThat(focusIsBackOnTheInvoker).isTrue();
        parkedTraceRequests.forEach(Route::resume);
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
        overlay.awaitOpened();
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
        assertThat(page.querySelector(TraceOverlay.HOST)).isNull();
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

        // The panel really swapped: the Spans tab's gantt is what was showing a moment ago,
        // and an innerHTML that merely stayed non-empty would keep it.
        assertThat((Boolean) overlay.evaluate("root => !!root.querySelector('#pk-tab-content .pk-gantt')"))
                .as("the arrow key swapped the panel, it did not merely restyle the strip")
                .isFalse();
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
        // The count is read from the very response that proved the query span landed, so a
        // second fetch cannot race the trace's eviction from the bounded store.
        JsonNode trace = awaitTrace(toolbar.traceId(), "trace => (trace.queries || []).length > 0");
        int spanCount = trace.path("summary").path("spans").path("count").asInt();
        // Not openOverlayFromToolbar(): that helper re-navigates, which would mint a
        // fresh trace and reopen the very race waited out above. Open the overlay for
        // the already-verified trace directly.
        toolbar.openOverlay();
        overlay.openTab("queries");

        Locator tablist = page.locator(TraceOverlay.HOST + " .pk-overlay__container > .pk-tabs");
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

    /**
     * Each tab against the element only its own renderer builds. The error page's trace is
     * the one that has all four: an HTTP exchange, a span tree, a JDBC query and a captured
     * log, so no tab can pass on its empty state.
     */
    @Test
    void everyOverlayTabRendersContent() {
        openOverlayForTheMultiSpanLogTrace();

        Map<String, String> tabRoot = new LinkedHashMap<>();
        tabRoot.put("request", ".pk-table--kv");
        tabRoot.put("spans", "#pk-gantt-rows");
        tabRoot.put("queries", ".pk-code-block");
        tabRoot.put("logs", ".pk-log");
        tabRoot.forEach((tab, root) -> {
            overlay.openTab(tab);
            overlay.waitFor(root);
            assertThat((Boolean) overlay.evaluate("root => !!root.querySelector('#pk-tab-content .pk-empty')"))
                    .as("tab %s fell back to its empty state", tab)
                    .isFalse();
        });
    }

    /**
     * A full-length span id on every row would crowd the tree, so a row carries none. The id
     * sits in the span's details panel instead, for the reader who opened the panel for this
     * span's particulars; CopyableIdIT covers the copy itself.
     */
    @Test
    void aSpanIdIsCopyableFromItsDetailsPanelNotItsRow() {
        openOverlayFromToolbar();

        assertThat((Boolean) overlay.evaluate("root => !!root.querySelector('#pk-gantt-rows .pk-gantt-row .pk-copy')"))
                .as("no row carries a copy control")
                .isFalse();
        assertThat(overlay.evaluate("root => [...root.querySelectorAll('#pk-gantt-rows .pk-gantt-span')]"
                        + ".every(entry => entry.querySelector('.pk-span-details .pk-copy')?.dataset.pkCopy"
                        + " === entry.querySelector('.pk-gantt-row').dataset.spanId)"))
                .as("every details panel offers its own span's id")
                .isEqualTo(true);
    }

    /**
     * One switch opens every span's details panel, and closes them all again. Its label names
     * what the next click does, worked out from the panels themselves, so it stays true after
     * the reader opens or closes panels by hand.
     */
    @Test
    void theAllDetailsSwitchOpensAndClosesEveryPanel() {
        Object states = importModule("trace-detail/tabs/spans.js", """
            (() => {
                const container = document.createElement('div');
                m.render(container, {durationMs: 10, startTimeMs: 0, rootSpan: {spanId: 'a', name: 'a', children: [{spanId: 'b', name: 'b'}]}});
                const all = container.querySelector('.pk-gantt-all-details');
                const names = container.querySelectorAll('.pk-gantt-name__toggle');
                const snapshot = () => [all.textContent,
                    container.querySelectorAll('.pk-gantt-span--open').length,
                    container.querySelectorAll('.pk-gantt-name__toggle[aria-expanded="true"]').length].join(':');
                const states = [snapshot()];
                all.click();
                states.push(snapshot());
                names[0].click();
                states.push(snapshot());
                all.click();
                states.push(snapshot());
                all.click();
                states.push(snapshot());
                names[0].click();
                names[1].click();
                states.push(snapshot());
                return states;
            })()
            """);

        @SuppressWarnings("unchecked")
        List<String> allDetailsStates = (List<String>) states;
        assertThat(allDetailsStates)
                .containsExactly(
                        "Show all details:0:0",
                        "Hide all details:2:2",
                        "Show all details:1:1",
                        "Hide all details:2:2",
                        "Show all details:0:0",
                        "Hide all details:2:2");
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
        openPageThatLogsAnError();
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
     * link to its entry in the Queries tab, in its details panel. The jump switches the
     * overlay tab, moves keyboard focus onto the target entry and marks it with a temporary
     * highlight class, so the eye lands where focus just went. Runs on the toolbar-open
     * path - the jump is pure DOM state and identical on every open path.
     */
    @Test
    void spanQueryLinkJumpsToTheQueriesTabEntry() {
        openOverlayFromToolbar();
        overlay.waitFor(".pk-span-query-link");
        String spanId = (String) overlay.evaluate("root => root.querySelector('.pk-span-query-link').dataset.spanId");
        overlay.click(".pk-gantt-row[data-span-id='" + spanId + "'] .pk-gantt-name__toggle");

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
     * mirroring spanQueryLinkJumpsToTheQueriesTabEntry above. Also the one test that proves
     * a real jump applies {@code pk-jump-flash} at all - jumpFlashOutranksAHoveredRow below
     * adds the class by hand, so it never exercises jumpToElement itself.
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
     * A jump target a reader's pointer happens to rest on must still show the flash tint:
     * .pk-jump-flash alone (0,1,0) loses to .pk-gantt-row:hover (0,2,0) by specificity, so an
     * unguarded flash would show the hover tint instead of the highlight the jump exists to
     * draw the eye to. Before trace-detail.css qualified the flash rule to match the hover
     * rule's specificity, this would have read the plain hover background instead of
     * --pk-primary-light, since the hover rule declared later would have won the tie.
     *
     * <p>The class is added by hand rather than through a real jump, so nothing here races
     * JUMP_FLASH_MS's 2s removal timer - queryEntrySpanLinkJumpsBackToItsSpanRow above
     * already covers that a real jump applies the class. Hovering and reading both the
     * hover state and the resolved background happen in one evaluate call, so there is no
     * round trip in which anything could change the row between the two reads.
     */
    @Test
    void jumpFlashOutranksAHoveredRow() {
        openOverlayFromToolbar();
        overlay.waitFor(".pk-gantt-row");
        // A specific row, by its own span id, so the row hover lands on and the row the
        // class is added to are provably the same element rather than each independently
        // resolving ".pk-gantt-row" to whichever row happens to be first.
        String spanId = (String) overlay.evaluate("root => root.querySelector('.pk-gantt-row').dataset.spanId");
        String rowSelector = ".pk-gantt-row[data-span-id='" + spanId + "']";

        page.hover(rowSelector);
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) overlay.evaluate("""
                (root, sel) => {
                    const row = root.querySelector(sel);
                    row.classList.add('pk-jump-flash');
                    return {hovered: row.matches(':hover'), background: getComputedStyle(row).backgroundColor};
                }
                """, rowSelector);

        assertThat((Boolean) state.get("hovered"))
                .as("hover must have landed on the row")
                .isTrue();
        assertThat(state.get("background")).isEqualTo(resolvedVar(TraceOverlay.HOST, "--pk-primary-light"));
    }

    /**
     * Cross-link from the Logs tab: beside the existing filter-to-span button, each log
     * row links to its span in the Spans tab's tree the same way the Queries tab does.
     */
    @Test
    void logRowSpanLinkJumpsToTheSpanTree() {
        openPageThatLogsAnError();
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

    /**
     * The ERROR log fixture's throwable carries framework frames the exclusion list folds
     * away, so the trace renders with at least one hidden run collapsed behind a
     * {@code <details>} and at least one application frame highlighted - proof the browser
     * is rendering the ranges Task 6 put on the wire, not inventing its own classification.
     * Playwright locators pierce the overlay's shadow root, so {@code page.locator} reaches
     * straight in the way {@code AccessibilityIT} already does for other log-row elements.
     *
     * <p>The counts above are a coarse smoke test; the real pin is the last assertion -
     * every {@code .pk-log__frame} rendered inside {@code .pk-log__trace}, read off in
     * document order and compared line for line against the trace's own {@code stackTrace}
     * field. A walk that loses track of where a hidden run ends - rendering its frames once
     * inside the {@code <details>} and again as loose frames below it - or one that drops a
     * line still satisfies the counts above, but not an exact, in-order, no-duplicates
     * comparison against every line the server actually sent. There are no JS unit tests in
     * this project, so this is the only thing standing between a broken walk and a shipped
     * regression.
     *
     * <p>What this cannot pin: whether a frame's {@code applicationFrame} flag was read off
     * the right index inside a hidden run (e.g. {@code app.has(j)} mistyped as a captured
     * {@code app.has(i)}). {@link org.peekaboot.backend.stacktrace.StackTraceFolding#fold}
     * guarantees the two can never disagree - an application frame always closes the hidden
     * run it would otherwise extend, so no index inside {@code [run.start, run.endExclusive)}
     * is ever also an application-frame index, on any real trace. Verified empirically: with
     * that one substitution made by hand, this test - text content, order, and the counts
     * above - stayed green, because the substitution reads as always-false either way. Text
     * content alone cannot distinguish the two expressions; only a fixture that violated the
     * server's own invariant could, and the server cannot produce one.
     */
    @Test
    void aLoggedErrorShowsItsTraceWithFrameworkFramesFolded() {
        String traceId = openPageThatLogsAnError();
        toolbar.openOverlay();
        overlay.openLogsTab();

        assertThat(page.locator(".pk-log__trace details.pk-log__hidden").count())
                .isGreaterThan(0);
        assertThat(page.locator(".pk-log__trace .pk-log__frame--app").count()).isGreaterThan(0);

        String stackTrace = capturedStackTrace(traceId);
        @SuppressWarnings("unchecked")
        List<String> renderedFrames = (List<String>) overlay.evaluate(
                "root => [...root.querySelectorAll('.pk-log__trace .pk-log__frame')].map(el => el.textContent)");
        assertThat(renderedFrames).containsExactlyElementsOf(List.of(stackTrace.split("\n", -1)));
    }

    /** The trace's own {@code stackTrace} field, joined back the way the server sent it. */
    private String capturedStackTrace(String traceId) {
        JsonNode trace = awaitTrace(traceId, ROOT_SPAN_EXPORTED);
        return trace.path("logs")
                .valueStream()
                .filter(log -> !log.path("stackTrace").isNull())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no captured log carried a stack trace"))
                .path("stackTrace")
                .asString();
    }

    /**
     * Each row whose trace actually hid something gets exactly one reveal control - the
     * scoping rule {@code revealControl(pre)} implements by closing over that row's own
     * {@code <pre>} rather than querying the document. Clicking the control then opens
     * every hidden run inside that trace, and - since {@code open} is recomputed from
     * {@code aria-pressed} on every click - a second click closes every run again. The
     * fixture here only ever produces one row with a throwable, so this cannot show a click
     * leaving a DIFFERENT row's trace alone; what it does pin is the invariant the
     * implementation actually promises - exactly one control per qualifying row - which
     * holds however many such rows the fixture grows to.
     */
    @Test
    void theLogTraceRevealControlOpensEveryHiddenRun() {
        openPageThatLogsAnError();
        toolbar.openOverlay();
        overlay.openLogsTab();
        overlay.waitFor(".pk-log__reveal");

        int rowsWithHiddenRuns = (int) (Integer) overlay.evaluate("root => [...root.querySelectorAll('.pk-log')]"
                + ".filter(row => row.querySelector('details.pk-log__hidden')).length");
        assertThat(page.locator(".pk-log__reveal").count()).isEqualTo(rowsWithHiddenRuns);

        page.click(".pk-log__reveal");

        assertThat(page.locator(".pk-log__trace details.pk-log__hidden[open]").count())
                .isEqualTo(page.locator(".pk-log__trace details.pk-log__hidden").count());
        assertThat(page.getAttribute(".pk-log__reveal", "aria-pressed")).isEqualTo("true");
        assertThat(page.textContent(".pk-log__reveal")).isEqualTo("Hide framework frames");

        page.click(".pk-log__reveal");

        assertThat(page.locator(".pk-log__trace details.pk-log__hidden[open]").count())
                .isEqualTo(0);
        assertThat(page.getAttribute(".pk-log__reveal", "aria-pressed")).isEqualTo("false");
        assertThat(page.textContent(".pk-log__reveal")).isEqualTo("Show full stack trace");
    }

    /** The query span lands after the response, so the overlay is opened once the store serves it. */
    @Test
    void queriesTabListsTheJdbcQueryFromThePersonsPage() {
        openPersonsPage();
        awaitTrace(toolbar.traceId(), "trace => (trace.queries || []).length > 0");
        toolbar.openOverlay();
        overlay.openTab("queries");
        overlay.waitFor(".pk-code-block");

        String sql = overlay.text(".pk-code-block");
        assertThat(sql.toLowerCase(Locale.ROOT)).contains("select");
    }

    /**
     * Each span's duration cell shows the duration the way every other surface formats
     * one (formatDurationMs - "250ms", "1.50s") plus its share of the whole trace's
     * duration, and the gantt header's tick marks line up with the row tracks below
     * them - both track and header timeline carry the same side margin, so the 0%/100%
     * ticks sit right above the start/end of the bars they describe rather than further out.
     *
     * <p>The leftmost tick is the axis origin, the trace's own start: it reads "0ms"
     * rather than the "&lt;1ms" formatDurationMs() gives any sub-millisecond duration,
     * because no duration is being measured there at all.
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

        String originTick =
                (String) overlay.evaluate("root => root.querySelector('.pk-gantt-header__timeline span').textContent");
        assertThat(originTick)
                .as("the axis origin is the trace's start, not a sub-millisecond measurement")
                .isEqualTo("0ms");

        BoundingBox headerBox =
                page.locator(TraceOverlay.HOST + " .pk-gantt-header__timeline").boundingBox();
        BoundingBox trackBox = page.locator(TraceOverlay.HOST + " .pk-gantt-row")
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
        Object labels = importModule("trace-detail/tabs/queries.js", """
            (() => {
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
            })()
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
                page.locator(TraceOverlay.HOST + " .pk-overlay__close").boundingBox();
        BoundingBox titleBox =
                page.locator(TraceOverlay.HOST + " .pk-overlay__title").boundingBox();

        assertThat(closeBox.y)
                .as("close button top should be within the title's vertical span")
                .isLessThan(titleBox.y + titleBox.height);
        assertThat(closeBox.y + closeBox.height)
                .as("close button bottom should overlap the title's vertical span")
                .isGreaterThan(titleBox.y);
    }

    /**
     * .pk-btn sets every other text property but not font-family, so without one the
     * "Show all details" switch renders in the UA's button font instead of the UI's -
     * mismatched against its own toolbar's legend right beside it.
     */
    @Test
    void ganttToolbarButtonsShareTheLegendsFont() {
        openOverlayFromToolbar();
        overlay.waitFor(".pk-gantt-all-details");

        String buttonFont = (String)
                overlay.evaluate("root => getComputedStyle(root.querySelector('.pk-gantt-all-details')).fontFamily");
        String legendFont = (String)
                overlay.evaluate("root => getComputedStyle(root.querySelector('.pk-gantt-legend')).fontFamily");

        assertThat(buttonFont).isEqualTo(legendFont);
    }

    /**
     * The overlay keeps the features it is handed for its whole lifetime - every SLOW
     * colour in its header, Spans and Queries tabs comes from those thresholds - and
     * nothing re-opens it once /api/features answers. So a deep link straight to a trace
     * must not open the overlay until the features are known, or a reader who configured
     * their own thresholds sees the shared link coloured by the defaults instead.
     *
     * <p>The features request is parked (a real request whose real response is merely
     * held back, the pattern ComponentBuilderIT uses for the api client's race) rather
     * than stubbed: the assertion is purely about ordering. A bounded wait for the
     * overlay's absence would pass vacuously if the deep link were broken outright, so
     * the same page then proves the overlay does open once the features are released.
     */
    @Test
    void aDeepLinkedOverlayOpensOnlyOnceTheFeaturesAreKnown() {
        openPersonsPage();
        String traceId = toolbar.traceId();

        AtomicReference<Route> parkedFeatures = new AtomicReference<>();
        page.route("**/peekaboot/api/features", route -> {
            if (!parkedFeatures.compareAndSet(null, route)) {
                route.resume();
            }
        });
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/" + traceId);
        page.waitForSelector("#traces-tab.active");
        page.waitForCondition(() -> parkedFeatures.get() != null);

        assertThatThrownBy(() -> page.waitForSelector(
                        TraceOverlay.HOST,
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.ATTACHED)
                                .setTimeout(1000)))
                .as("no overlay while /api/features is still outstanding")
                .isInstanceOf(TimeoutError.class);

        parkedFeatures.get().resume();

        overlay.waitFor("#pk-gantt-rows");
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
     * happens. Also covers that the header leaves the span/query/log counts to the tabs:
     * reconcileOrders() calls orderRepository.findAll() exactly once, and CustomerOrder is
     * a flat entity with no lazy associations to trigger further queries, so the trace's
     * query count is deterministically 1 regardless of how many orders exist when the test
     * runs - read from the Queries tab, whose "1 query" pluralisation is pinned by
     * {@code SharedModuleIT.formatCountPluralisesIrregularNouns}.
     */
    @Test
    void overlayHeaderShowsTheRootActionLabelForNonHttpTraces() {
        String traceId = ScheduledJobs.run(scheduledTaskHolder, OrderReconciler.class, "reconcileOrders");
        // The run's own trace, and the wait is on its root span: the exporter hands spans over
        // in the order they ended, so the query span the header counts is there with it.
        awaitTrace(traceId, "trace => trace.rootActionType === 'SCHEDULED_JOB'");

        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/" + traceId);
        overlay.waitFor(".pk-overlay__title-method");

        String methodText = overlay.text(".pk-overlay__title-method");
        assertThat(methodText)
                .as("no HTTP method exists for a scheduled job, so the header must fall back to "
                        + "the root-action label rather than a fake method")
                .isEqualTo("Scheduled Job");

        String metaText = overlay.text(".pk-overlay__meta").toLowerCase(Locale.ROOT);
        assertThat(metaText)
                .as("the tabs carry the span, query and log counts, so the header does not repeat them")
                .doesNotContain("span")
                .doesNotContain("quer")
                .doesNotContain("log");
        assertThat(overlay.text(".pk-tab[data-tab=\"queries\"] .pk-tab__count"))
                .as("reconcileOrders() issues exactly one query (CustomerOrder is a flat entity, so "
                        + "findAll() is a single SELECT regardless of row count)")
                .isEqualTo("1");
    }

    /**
     * The tab strip's counts are counts like any other: grouped, and in the locale the dashboard
     * is set to when the dashboard opened the overlay. Proven on a real trace's real counts,
     * grouped with 'ar-EG-u-nu-arab' - its Arabic-Indic digits differ from the browser's en-US
     * ones however small a count is - and checked live with Intl in the page rather than
     * hard-coded, so this holds regardless of how many spans/queries/logs the request produces.
     */
    @Test
    void overlayTabCountsAreGroupedInTheDashboardsLocale() {
        openPersonsPage();
        String traceId = toolbar.traceId();
        JsonNode trace = awaitTrace(traceId, "trace => (trace.queries || []).length > 0");
        page.addInitScript("localStorage.setItem('peekaboot-locale', 'ar-EG-u-nu-arab')");

        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/" + traceId);
        overlay.waitFor(".pk-tab[data-tab=\"logs\"] .pk-tab__count");

        assertThat(overlay.text(".pk-tab[data-tab=\"spans\"] .pk-tab__count"))
                .isEqualTo(localeFormatted(
                        trace.path("summary").path("spans").path("count").asInt(), "ar-EG-u-nu-arab"));
        assertThat(overlay.text(".pk-tab[data-tab=\"queries\"] .pk-tab__count"))
                .isEqualTo(localeFormatted(
                        trace.path("summary").path("queries").path("count").asInt(), "ar-EG-u-nu-arab"));
        assertThat(overlay.text(".pk-tab[data-tab=\"logs\"] .pk-tab__count"))
                .isEqualTo(localeFormatted(
                        trace.path("summary").path("logs").path("count").asInt(), "ar-EG-u-nu-arab"));
    }

    /**
     * A CSP that omits style-src 'unsafe-inline' drops every style attribute written
     * through innerHTML, but not CSSOM writes (element.style). The gantt has to survive
     * that policy: its bar positions, row indents and indent guides are the whole chart,
     * and it sets them through the CSSOM for exactly this reason. The policy is applied
     * by adding the header to the real dashboard response (its body is untouched), so the dashboard
     * document and the overlay it opens are what runs under it - the toolbar's own page
     * is served before the route is installed and is not covered here.
     *
     * <p>Two positive controls keep the test from passing with no policy in force at all:
     * the header is asserted on the navigation response, and a probe element whose style
     * arrives as a parsed attribute is asserted not to take the width it asks for. The
     * probe runs last, since the violation it provokes is the one console message this
     * test does expect.
     */
    @Test
    void ganttSurvivesAHostPageWhoseCspForbidsInlineStyles() {
        List<String> cspViolations = new ArrayList<>();
        page.onConsoleMessage(message -> {
            if (message.text().contains("Content Security Policy")) cspViolations.add(message.text());
        });
        openPersonsPage();
        String traceId = toolbar.traceId();
        serveWithCsp("**/peekaboot/ui/dashboard/index.html", "style-src 'self'");

        Response navigation = page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/" + traceId);
        overlay.awaitMeasurable(".pk-gantt-span[data-depth='2']");

        assertThat(navigation.headers())
                .as("the policy reached the document under test")
                .containsEntry("content-security-policy", "style-src 'self'");
        assertThat(
                        overlay.evaluate(
                                "root => getComputedStyle(root.querySelector('.pk-gantt-span[data-depth=\"1\"] .pk-gantt-name')).paddingLeft"))
                .as("a nested row keeps its indent")
                .isEqualTo("16px");
        assertThat((Boolean) overlay.evaluate("root => {"
                        + "const track = root.querySelector('.pk-gantt-span[data-depth=\"0\"] .pk-gantt-track');"
                        + "return track.querySelector('.pk-gantt-bar').getBoundingClientRect().width"
                        + "  > track.getBoundingClientRect().width * 0.9; }"))
                .as("the root span's bar spans its track")
                .isTrue();
        assertThat(
                        overlay.evaluate(
                                "root => getComputedStyle(root.querySelector('.pk-gantt-span[data-depth=\"1\"] .pk-span-details')).marginLeft"))
                .as("its details panel sits under its name")
                .isEqualTo("40px");
        assertThat(
                        overlay.evaluate(
                                "root => getComputedStyle(root.querySelector('.pk-gantt-span[data-depth=\"2\"]')).backgroundSize"))
                .as("a span two levels down draws two indent guides")
                .isEqualTo("32px 100%");
        assertThat(cspViolations).isEmpty();

        // style-src-attr falls back to style-src, so a parsed style attribute is refused
        // while the element.style writes the gantt uses go through.
        assertThat(page.evaluate("""
                        () => {
                            const probe = document.createElement('div');
                            probe.setAttribute('style', 'width: 99px');
                            document.body.appendChild(probe);
                            const width = getComputedStyle(probe).width;
                            probe.remove();
                            return width;
                        }
                        """))
                .as("a style attribute is dropped, so the policy is in force")
                .isNotEqualTo("99px");
    }

    /**
     * On a phone-sized viewport a row wraps rather than splitting one line three ways, so it
     * stays inside the viewport and the track keeps a usable width.
     */
    @Test
    void ganttRowsFitANarrowViewport() {
        page.setViewportSize(375, 667);
        openOverlayFromToolbar();
        overlay.waitFor(".pk-gantt-row");

        Boolean rowFits = (Boolean) overlay.evaluate("root => {"
                + "const row = root.querySelector('.pk-gantt-row');"
                + "return row.scrollWidth <= row.clientWidth && row.getBoundingClientRect().right <= 375.5; }");
        assertThat(rowFits).as("the row stays inside the viewport").isTrue();
        assertThat(((Number)
                                overlay.evaluate(
                                        "root => root.querySelector('.pk-gantt-row .pk-gantt-track').getBoundingClientRect().width"))
                        .doubleValue())
                .as("the track keeps a usable width")
                .isGreaterThan(40.0);
    }

    /**
     * A query span deep in the tree keeps its whole name beside its chips. The name column
     * sizes to the widest row's own indent, name and chips, capped at 40% of the tab, so a
     * name several levels down is not squeezed by the indent and chips it carries there.
     */
    @Test
    void aDeepQuerySpanKeepsItsWholeName() {
        page.setViewportSize(1280, 800);
        String traceId = storeDeepQueryTrace();

        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/" + traceId);
        overlay.awaitMeasurable(".pk-span-logs-toggle");

        assertThat(overlay.evaluate("root => [...root.querySelectorAll('.pk-gantt-name__text')]"
                        + ".filter(name => name.scrollWidth > name.clientWidth).map(name => name.textContent)"))
                .as("span names cut short by an ellipsis")
                .isEqualTo(List.of());
    }

    /**
     * The 40% cap holds even against a single span whose name alone dwarfs it: the name
     * column's {@code fit-content(40%)} still gives up no more than that share of the
     * gantt's own width, leaving the rest of the name to the ellipsis - a column sized to
     * {@code max-content} instead would grow past the cap to fit the whole name.
     */
    @Test
    void theNameColumnNeverExceeds40PercentOfTheGanttsWidth() {
        page.setViewportSize(1280, 800);
        String traceId = "overlay-long-name-" + System.nanoTime();
        traceStore.addSpan(TestSpans.span(traceId, "root")
                .named("x".repeat(200))
                .kind(Span.Kind.SERVER)
                .at(0, 10)
                .build());

        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/" + traceId);
        overlay.awaitMeasurable(".pk-gantt-name__text");

        BoundingBox nameCellBox =
                page.locator(TraceOverlay.HOST + " .pk-gantt-name").first().boundingBox();
        BoundingBox ganttBox = page.locator(TraceOverlay.HOST + " .pk-gantt").boundingBox();

        assertThat(nameCellBox.width)
                .as("name column width stays within 40% of the gantt's own content width")
                .isLessThanOrEqualTo(ganttBox.width * 0.4 + 1);
        assertThat((Boolean) overlay.evaluate("root => {"
                        + "const name = root.querySelector('.pk-gantt-name__text');"
                        + "return name.scrollWidth > name.clientWidth; }"))
                .as("the 200-character name is the one that ellipses")
                .isTrue();
    }

    /**
     * Below 768px a row gives its name and chips the whole width and puts the track and the
     * duration on the line under them, so neither the name nor the track is squeezed out.
     */
    @Test
    void aNarrowViewportGivesEachSpanNameALineOfItsOwn() {
        page.setViewportSize(375, 667);
        String traceId = storeDeepQueryTrace();

        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/" + traceId);
        overlay.awaitMeasurable(".pk-span-logs-toggle");

        assertThat(overlay.evaluate("""
                root => [...root.querySelectorAll('.pk-gantt-row')].every(row => {
                    const name = row.querySelector('.pk-gantt-name').getBoundingClientRect();
                    const track = row.querySelector('.pk-gantt-track').getBoundingClientRect();
                    return Math.abs(name.width - row.getBoundingClientRect().width) < 1 && track.top >= name.bottom;
                })
                """)).isEqualTo(true);
    }

    /**
     * Stores the shape /orders produces - a query span five levels down, under a nested HTTP
     * call - with the result-set span and the log that give its row both chips, and returns
     * the trace id.
     */
    private String storeDeepQueryTrace() {
        String traceId = "overlay-deep-query-" + System.nanoTime();
        traceStore.addSpan(TestSpans.span(traceId, "root")
                .named("http get /orders")
                .kind(Span.Kind.SERVER)
                .at(0, 40)
                .tag("http.method", "GET")
                .tag("url.path", "/orders")
                .build());
        traceStore.addSpan(TestSpans.span(traceId, "handler")
                .parent("root")
                .named("spring.handler")
                .at(1, 38)
                .build());
        traceStore.addSpan(TestSpans.span(traceId, "client")
                .parent("handler")
                .named("http get")
                .kind(Span.Kind.CLIENT)
                .at(2, 30)
                .build());
        traceStore.addSpan(TestSpans.span(traceId, "server")
                .parent("client")
                .named("http get /api/person/{id}")
                .kind(Span.Kind.SERVER)
                .at(3, 28)
                .build());
        traceStore.addSpan(TestSpans.span(traceId, "inner-handler")
                .parent("server")
                .named("spring.handler")
                .at(4, 26)
                .build());
        traceStore.addSpan(TestSpans.span(traceId, "query")
                .parent("inner-handler")
                .named("SELECT customer_order")
                .kind(Span.Kind.CLIENT)
                .at(5, 2)
                .tag("db.system.name", "postgresql")
                .tag("db.query.text", "select * from customer_order")
                .build());
        traceStore.addSpan(TestSpans.span(traceId, "result-set")
                .parent("inner-handler")
                .named("result-set")
                .kind(Span.Kind.CLIENT)
                .at(7, 1)
                .tag("jdbc.row-count", "8")
                .build());
        traceStore.addLog(new LogCapturedEvent(
                traceId, "query", Instant.EPOCH.plusMillis(6), "INFO", "fixture", "found 8 orders", "main", null));
        return traceId;
    }

    /**
     * Serves the toolbar's current trace with its trace-level {@code slow} flag and total
     * duration replaced, so the header can be driven through both verdicts on one real trace.
     */
    private void openOverlayWithTracePatched(boolean slow, long durationMs) {
        page.route("**/api/traces/*/insights", route -> {
            APIResponse response = route.fetch();
            ObjectNode trace = (ObjectNode) readJson(response.text());
            trace.put("slow", slow).put("durationMs", durationMs);
            route.fulfill(new Route.FulfillOptions().setResponse(response).setBody(trace.toString()));
        });
        toolbar.openOverlay();
        overlay.waitFor(".pk-overlay__meta");
        page.unroute("**/api/traces/*/insights");
    }

    /**
     * The header's SLOW marking is the backend's per-trace verdict ({@code trace.slow}: some
     * span carries a SLOW or VERY_SLOW issue), the very flag the Traces tab's badge reads.
     * Applying the span thresholds to the trace's total instead called a 120 ms request slow
     * in the header while the list beside it did not, and a 5 s trace with no slow span
     * (the toolbar's own fetch ladder, say) the other way round.
     */
    @Test
    void headerSlowMarkingFollowsTheBackendsVerdict() {
        openPersonsPage();
        toolbar.traceId();

        openOverlayWithTracePatched(true, 5);
        assertThat(overlay.text(".pk-overlay__meta .pk-badge--warn").trim()).isEqualTo("SLOW");
        assertThat((String) overlay.evaluate("root => root.querySelector('.pk-overlay__duration').className"))
                .contains("slow");

        openOverlayWithTracePatched(false, 5000);
        assertThat((Boolean) overlay.evaluate("root => !!root.querySelector('.pk-overlay__meta .pk-badge--warn')"))
                .isFalse();
        assertThat((String) overlay.evaluate("root => root.querySelector('.pk-overlay__duration').className"))
                .doesNotContain("slow");
    }

    /**
     * The Spans tab renders the facts the backend serves rather than re-deriving them from
     * tags and names: the row count is {@code span.rowCount}, which the backend pairs onto
     * the query span itself (null for a count that did not parse, even where the result
     * set's own tag is right there), an error bar follows {@code span.status} alone, and
     * every tag on the span is shown - the backend already keeps the statement tags out,
     * so the tab does not sniff for them. The row count chip is also where the locale
     * passed to {@code m.render} must land: rendered in de-DE, 12345 rows reads "12.345".
     */
    @Test
    void spansTabTrustsTheBackendsSpanFacts() {
        Object facts = importModule("trace-detail/tabs/spans.js", """
            (() => {
                const rendered = (span, locale) => {
                    const container = document.createElement('div');
                    m.render(container, {durationMs: 10, startTimeMs: 0, rootSpan: span}, {locale});
                    return container;
                };
                const rowCountOf = (span, locale) => rendered(span, locale).querySelector('.pk-span-row-count')?.textContent ?? null;
                const errorBar = span => rendered(span).querySelector('.pk-gantt-bar').className.includes('--error');
                const tagKeys = span => Array.from(rendered(span).querySelectorAll('.pk-span-tags__key')).map(el => el.textContent);
                return [
                    rowCountOf({spanId: 'a', name: 'SELECT orders', rowCount: 1234, query: 'select * from orders'}),
                    rowCountOf({spanId: 'b', name: 'result-set', rowCount: null, tags: {'jdbc.row-count': '3'}}),
                    rowCountOf({spanId: 'f', name: 'SELECT big', rowCount: 12345}, 'de-DE'),
                    errorBar({spanId: 'c', name: 'x', status: 'ERROR'}),
                    errorBar({spanId: 'd', name: 'x', status: 'OK', errorMessage: 'ignored'}),
                    tagKeys({spanId: 'e', name: 'x', tags: {'db.system': 'h2', 'db.statement': 'SELECT 1'}}).join(',')
                ];
            })()
            """);

        @SuppressWarnings("unchecked")
        List<Object> spanFacts = (List<Object>) facts;
        assertThat(spanFacts).containsExactly("1,234 rows", null, "12.345 rows", true, false, "db.statement,db.system");
    }

    /**
     * A span's tags show their full keys, in key order. A query span carries several keys
     * that end in the same word (db.system.name, db.operation.name, jdbc.datasource.name),
     * which a key shortened to its last segment renders as three indistinguishable "name"s,
     * and the backend's tag map carries no order of its own.
     */
    @Test
    void spanTagsShowTheirFullKeysInKeyOrder() {
        Object keys = importModule("trace-detail/tabs/spans.js", """
            (() => {
                const container = document.createElement('div');
                m.render(container, {durationMs: 10, startTimeMs: 0, rootSpan: {spanId: 'a', name: 'SELECT person',
                    tags: {'jdbc.datasource.name': 'sample_app_db', 'db.system.name': 'postgresql', 'db.operation.name': 'SELECT'}}});
                return Array.from(container.querySelectorAll('.pk-span-tags__key')).map(el => el.textContent);
            })()
            """);

        @SuppressWarnings("unchecked")
        List<String> tagKeys = (List<String>) keys;
        assertThat(tagKeys).containsExactly("db.operation.name", "db.system.name", "jdbc.datasource.name");
    }

    /**
     * The gantt's subtree toggle: collapsing the root hides every deeper row and flips the
     * control's own state, so a screen reader and the eye agree. Measured on rows rather than
     * on the button, since hiding is what the reader came for.
     */
    @Test
    void collapsingASpanHidesItsSubtreeAndSaysSo() {
        openOverlayFromToolbar();
        overlay.waitFor("#pk-gantt-rows .pk-gantt-toggle");

        int allRows = visibleGanttRows();
        assertThat(allRows)
                .as("a nested tree is what makes a collapse observable")
                .isGreaterThan(1);

        overlay.click("#pk-gantt-rows .pk-gantt-toggle");

        assertThat(visibleGanttRows()).isEqualTo(1);
        assertThat(overlay.evaluate(
                        "root => root.querySelector('#pk-gantt-rows .pk-gantt-toggle').getAttribute('aria-expanded')"))
                .isEqualTo("false");
        assertThat(overlay.text("#pk-gantt-rows .pk-gantt-toggle"))
                .as("the chevron is drawn in CSS, so there is no glyph to announce over the label")
                .isEmpty();

        overlay.click("#pk-gantt-rows .pk-gantt-toggle");

        assertThat(visibleGanttRows()).isEqualTo(allRows);
    }

    private int visibleGanttRows() {
        return ((Number) overlay.evaluate("root => [...root.querySelectorAll('#pk-gantt-rows .pk-gantt-span')]"
                        + ".filter(entry => entry.style.display !== 'none').length"))
                .intValue();
    }

    /**
     * An open details panel belongs to its span's entry, so it hides and returns with that
     * span: collapsing an ancestor takes it away, expanding the ancestor brings it back still
     * open, and a collapsed span in between keeps its own subtree hidden throughout.
     */
    @Test
    void collapsingASpanTakesItsDescendantsOpenDetailsWithIt() {
        Object states = importModule("trace-detail/tabs/spans.js", """
            (() => {
                const container = document.createElement('div');
                m.render(container, {durationMs: 10, startTimeMs: 0, rootSpan: {spanId: 'root', name: 'root', children: [
                    {spanId: 'mid', name: 'mid', children: [{spanId: 'leaf', name: 'leaf', tags: {'db.system.name': 'h2'}}]},
                    {spanId: 'sibling', name: 'sibling'}]}});
                const entry = id => container.querySelector(`.pk-gantt-row[data-span-id="${id}"]`).closest('.pk-gantt-span');
                const toggle = id => entry(id).querySelector('.pk-gantt-toggle');
                const state = id => entry(id).style.display === 'none' ? 'hidden'
                    : entry(id).classList.contains('pk-gantt-span--open') ? 'open' : 'shown';
                const snapshot = () => ['mid', 'leaf', 'sibling'].map(state).join(',');
                entry('leaf').querySelector('.pk-gantt-name__toggle').click();
                const opened = snapshot();
                toggle('root').click();
                const rootCollapsed = snapshot();
                toggle('root').click();
                const rootExpanded = snapshot();
                toggle('mid').click();
                toggle('root').click();
                toggle('root').click();
                const midStillCollapsed = snapshot();
                return [opened, rootCollapsed, rootExpanded, midStillCollapsed];
            })()
            """);

        @SuppressWarnings("unchecked")
        List<String> stateSnapshots = (List<String>) states;
        assertThat(stateSnapshots)
                .containsExactly("shown,open,shown", "hidden,hidden,hidden", "shown,open,shown", "shown,hidden,shown");
    }

    /**
     * The track is the widest part of a row, so a pointer opens the details from there as
     * well as from the name. An event marker on the track is a control of its own and does
     * not, and a click on the bar counts as a click on the track.
     */
    @Test
    void clickingASpansTrackTogglesItsDetails() {
        Object states = importModule("trace-detail/tabs/spans.js", """
            (() => {
                const container = document.createElement('div');
                m.render(container, {durationMs: 10, startTimeMs: 0, rootSpan: {spanId: 'a', name: 'a',
                    events: [{name: 'exception', timestamp: '1970-01-01T00:00:00.005Z'}]}});
                const entry = container.querySelector('.pk-gantt-span');
                const open = () => entry.classList.contains('pk-gantt-span--open')
                    && entry.querySelector('.pk-gantt-name__toggle').getAttribute('aria-expanded') === 'true';
                const states = [];
                entry.querySelector('.pk-gantt-track').click();
                states.push(open());
                entry.querySelector('.pk-gantt-event-marker').click();
                states.push(open());
                entry.querySelector('.pk-gantt-bar').click();
                states.push(open());
                return states;
            })()
            """);

        @SuppressWarnings("unchecked")
        List<Object> trackClickStates = (List<Object>) states;
        assertThat(trackClickStates).containsExactly(true, true, false);
    }

    /**
     * A span's kind is its dot's and its bar's colour, keyed by a legend of only the kinds the
     * trace has. Colour never carries it alone: the name button's accessible name and the
     * details panel say the kind in words. A kind the tab does not know reads as internal
     * rather than as a colour with no legend entry.
     */
    @Test
    void spanKindsAreColouredAndKeyedByTheLegend() {
        Object facts = importModule("trace-detail/tabs/spans.js", """
            (() => {
                const container = document.createElement('div');
                m.render(container, {durationMs: 10, startTimeMs: 0, rootSpan: {spanId: 'a', name: 'http get /orders', kind: 'SERVER', children: [
                    {spanId: 'b', name: 'SELECT person', kind: 'CLIENT'},
                    {spanId: 'c', name: 'spring.handler', kind: null},
                    {spanId: 'd', name: 'odd', kind: 'SOMETHING_NEW'}]}});
                const entry = id => container.querySelector(`.pk-gantt-row[data-span-id="${id}"]`).closest('.pk-gantt-span');
                return [
                    Array.from(container.querySelectorAll('.pk-gantt-legend__item')).map(el => el.textContent).join(','),
                    entry('b').classList.contains('pk-gantt-kind--client'),
                    entry('b').querySelector('.pk-gantt-name__toggle').getAttribute('aria-label'),
                    entry('b').querySelector('.pk-span-details__kind').textContent,
                    entry('d').classList.contains('pk-gantt-kind--internal'),
                    container.querySelectorAll('.pk-gantt-kind').length === 0
                ];
            })()
            """);

        @SuppressWarnings("unchecked")
        List<Object> spanKindFacts = (List<Object>) facts;
        assertThat(spanKindFacts)
                .containsExactly(
                        "Server,Client,Internal", true, "SELECT person, client span", "Client span", true, true);
    }

    /**
     * An error span says what went wrong where the reader is looking: its name takes the
     * danger colour on the row, following the backend's status verdict like the bar does,
     * its row carries a visible "error" chip, and its details panel leads with the exception
     * class and message the backend recorded. A span that recorded neither renders no error
     * section and no chip. The chip is aria-hidden: the name button's accessible name above
     * already ends in ", error".
     */
    @Test
    void anErrorSpansDetailsShowTheExceptionItRecorded() {
        Object facts = importModule("trace-detail/tabs/spans.js", """
            (() => {
                const container = document.createElement('div');
                m.render(container, {durationMs: 10, startTimeMs: 0, rootSpan: {spanId: 'a', name: 'root', children: [
                    {spanId: 'b', name: 'http get', status: 'ERROR',
                        errorClass: 'org.springframework.web.client.ResourceAccessException', errorMessage: 'Connection refused'},
                    {spanId: 'c', name: 'ok', status: 'OK'}]}});
                const entry = id => container.querySelector(`.pk-gantt-row[data-span-id="${id}"]`).closest('.pk-gantt-span');
                const nameToggle = id => entry(id).querySelector('.pk-gantt-name__toggle');
                const erroneous = id => nameToggle(id).classList.contains('pk-gantt-name__toggle--error');
                const errorChips = id => entry(id).querySelectorAll('.pk-span-error-chip');
                return [
                    erroneous('b'),
                    entry('b').querySelector('.pk-span-details__error-class')?.textContent ?? null,
                    entry('b').querySelector('.pk-span-details__error-message')?.textContent ?? null,
                    nameToggle('b').getAttribute('aria-label'),
                    errorChips('b').length,
                    errorChips('b')[0].textContent,
                    errorChips('b')[0].getAttribute('aria-hidden'),
                    erroneous('c'),
                    entry('c').querySelector('.pk-span-details__error') === null,
                    nameToggle('c').getAttribute('aria-label'),
                    errorChips('c').length
                ];
            })()
            """);

        @SuppressWarnings("unchecked")
        List<Object> errorFacts = (List<Object>) facts;
        assertThat(errorFacts)
                .containsExactly(
                        true,
                        "org.springframework.web.client.ResourceAccessException",
                        "Connection refused",
                        "http get, internal span, error",
                        1,
                        "error",
                        "true",
                        false,
                        true,
                        "ok, internal span",
                        0);
    }

    /**
     * A span's row is one line; its statement and its tags wait in a details panel under the
     * row until the reader opens it from the span's name. A statement runs to hundreds of
     * characters and a query span carries a dozen tags - more than a row can carry alongside
     * every sibling row's own name and bar, so the tree stays the thing on screen by default.
     */
    @Test
    void aSpanNameOpensTheDetailsPanelUnderItsRow() {
        openPersonsPage();
        awaitTrace(toolbar.traceId(), "trace => (trace.queries || []).length > 0");
        toolbar.openOverlay();
        overlay.waitFor(".pk-span-query-link");
        String spanId = (String) overlay.evaluate("root => root.querySelector('.pk-span-query-link').dataset.spanId");
        String nameToggle = ".pk-gantt-row[data-span-id='" + spanId + "'] .pk-gantt-name__toggle";

        assertThat(overlay.evaluate("(root, sel) => root.querySelector(sel).getAttribute('aria-expanded')", nameToggle))
                .isEqualTo("false");
        assertThat(detailsPanelShown(spanId)).as("closed until asked for").isFalse();

        overlay.click(nameToggle);

        assertThat(detailsPanelShown(spanId)).isTrue();
        assertThat(overlay.evaluate("(root, sel) => root.querySelector(sel).getAttribute('aria-expanded')", nameToggle))
                .isEqualTo("true");
        String panelId = (String)
                overlay.evaluate("(root, sel) => root.querySelector(sel).getAttribute('aria-controls')", nameToggle);
        assertThat(overlay.evaluate(
                        "(root, id) => root.getElementById(id).closest('.pk-gantt-span').querySelector('.pk-gantt-row').dataset.spanId",
                        panelId))
                .as("the name names the panel it opens")
                .isEqualTo(spanId);
        assertThat(overlay.text("#" + panelId + " .pk-code-block").toLowerCase(Locale.ROOT))
                .contains("select");
        assertThat(((Number) overlay.evaluate(
                                "(root, id) => root.getElementById(id).querySelectorAll('.pk-span-tags__key').length",
                                panelId))
                        .intValue())
                .as("the query span's tags are in its panel")
                .isPositive();

        overlay.click(nameToggle);

        assertThat(detailsPanelShown(spanId)).isFalse();
    }

    private boolean detailsPanelShown(String spanId) {
        return (Boolean) overlay.evaluate(
                "(root, id) => getComputedStyle(root.querySelector(`.pk-gantt-row[data-span-id='${id}']`)"
                        + ".closest('.pk-gantt-span').querySelector('.pk-span-details')).display !== 'none'",
                spanId);
    }

    /**
     * A level filter that matches no row leaves the list rendered but empty rather than
     * dropping back to "no logs recorded": the trace does carry logs, the filter is simply
     * hiding them, and clearing it has to bring them back.
     */
    @Test
    void aLevelFilterThatMatchesNoRowHidesEveryLog() {
        openOverlayForTheMultiSpanLogTrace();
        overlay.openTab("logs");
        overlay.waitFor(".pk-log");

        int allLogs = visibleLogRows();
        assertThat(allLogs).isPositive();

        overlay.evaluate("root => { const select = root.querySelector('#pk-log-level');"
                + " select.value = 'TRACE'; select.dispatchEvent(new Event('change')); }");

        assertThat(visibleLogRows())
                .as("nothing in this trace logs at TRACE level")
                .isZero();
        assertThat((Boolean) overlay.evaluate("root => !!root.querySelector('#pk-logs-list')"))
                .as("the list stays; it is the rows that are filtered out")
                .isTrue();

        overlay.evaluate("root => { const select = root.querySelector('#pk-log-level');"
                + " select.value = ''; select.dispatchEvent(new Event('change')); }");

        assertThat(visibleLogRows()).isEqualTo(allLogs);
    }

    private int visibleLogRows() {
        return ((Number) overlay.evaluate("root => [...root.querySelectorAll('.pk-log')]"
                        + ".filter(row => !row.classList.contains('pk-log--hidden')).length"))
                .intValue();
    }

    /**
     * A span event is drawn on that span's own track, named, so a reader spots an exception or
     * a checkpoint without opening anything. Written straight to the store: the sample app's
     * instrumentation records no events, and one that started to would not do it on request.
     */
    @Test
    void aSpanEventIsMarkedOnItsTrackWithItsName() {
        String traceId = "overlay-event-" + System.nanoTime();
        traceStore.addSpan(TestSpans.span(traceId, "root")
                .named("GET /events-fixture")
                .kind(Span.Kind.SERVER)
                .at(0, 40)
                .tag("http.method", "GET")
                .tag("url.path", "/events-fixture")
                .event("exception", 20)
                .build());

        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/" + traceId);
        overlay.waitFor("#pk-gantt-rows");

        assertThat(overlay.evaluate("root => root.querySelector('.pk-gantt-event-marker').getAttribute('aria-label')"))
                .isEqualTo("Event: exception");
        assertThat(overlay.text(".pk-gantt-event-tooltip")).isEqualTo("exception");
    }

    /**
     * Task 10: the listing row for an async subtree, and its link back to the enclosing
     * trace. /orders/enrich dispatches {@code EnrichmentService.enrich} on a task executor
     * with the testing app's context propagation on (see {@code AsyncTraceCaptureIT}, the
     * only other test that drives this endpoint), so the store ends up with one bundle
     * carrying both the request's own root span and the async entry span - and
     * {@code TraceInsightsService.rowsOf} lists the two as separate rows sharing one
     * trace id.
     */
    @Test
    void anAsyncRowLinksToItsEnclosingTraceAndOpensItsOwnSubtree() {
        String traceId = triggerOrderEnrichment();
        awaitTrace(traceId, ASYNC_SPAN_CAPTURED);

        openDashboard();
        dashboard.openTracesTab();
        dashboard.awaitListedTraceRowCount(traceId, 2);

        Locator asyncRow = page.locator(Dashboard.traceItem(traceId) + "[data-subtree-root-span-id]");
        String asyncSpanId = asyncRow.getAttribute("data-subtree-root-span-id");

        assertThat(asyncRow.locator(".pk-trace-item__icon").getAttribute("aria-label"))
                .as("the async row's icon carries root-actions.js's ASYNC_TASK label")
                .isEqualTo("Async Task");

        Locator enclosingLink = asyncRow.locator(".pk-trace-item__enclosing-link");
        assertThat(enclosingLink.getAttribute("aria-label"))
                .as("the link back names the enclosing trace, distinct from the SCHEDULED_JOB "
                        + "row's own scheduler link")
                .isEqualTo("View the trace this ran under");

        dashboard.openListedTrace(Dashboard.asyncTraceItem(traceId, asyncSpanId), traceId);

        assertThat(overlay.selectedTab()).isEqualTo("spans");
        assertThat(page.url())
                .as("the async row's open button lands on the Spans tab scoped to its own "
                        + "subtree, not the whole trace")
                .contains("#traces/" + traceId + "/spans?root=" + asyncSpanId);
    }

    /**
     * Task 11: an async subtree renders collapsed by default - its own toggle starts
     * {@code aria-expanded="false"} with its descendants hidden - while a sibling
     * synchronous span stays visible, and the entry itself carries a visible "background"
     * marker. The entry's own bar stays on the trace's basis, so it sits where the task was
     * dispatched - 100ms into a 1000ms trace, 50ms wide - and its share of the column reads
     * against the same denominator as its synchronous sibling's. Only its children are
     * re-based, against the subtree's own 220ms window (100 to 320). The fixture's
     * grandchild ends well after its async parent's own declared end (100+50=150ms vs
     * 120+200=320ms): a fixture where the child ends before the parent cannot tell a correct
     * subtree walk from one that merely reads the entry's own duration, since both would
     * answer the same number - the trap {@code subtreeWindowMs} exists for, and the one its
     * Java twin in {@code TraceTreeMapper} was already caught by once. Reading that 50ms
     * duration as the basis instead would put the grandchild's bar 40% of the way in, against
     * the 9.1% asserted below.
     */
    @Test
    void anAsyncSubtreeCollapsesByDefaultAndRebasesAgainstItsOwnWindow() {
        Object facts = importModule("trace-detail/tabs/spans.js", """
            (() => {
                const container = document.createElement('div');
                m.render(container, {durationMs: 1000, startTimeMs: 0, rootSpan: {spanId: 'root', name: 'root', children: [
                    {spanId: 'a', name: 'async task', asyncEntry: true, startTimeMs: 100, durationMs: 50, children: [
                        {spanId: 'g', name: 'grandchild', startTimeMs: 120, durationMs: 200}]},
                    {spanId: 's', name: 'sibling', startTimeMs: 600, durationMs: 50}]}});
                const entry = id => container.querySelector(`.pk-gantt-row[data-span-id="${id}"]`).closest('.pk-gantt-span');
                const hidden = id => entry(id).style.display === 'none';
                const bar = id => entry(id).querySelector('.pk-gantt-bar');
                const pct = value => Number(parseFloat(value)).toFixed(1);
                return [
                    entry('a').querySelector('.pk-gantt-toggle').getAttribute('aria-expanded'),
                    hidden('g'),
                    hidden('s'),
                    entry('a').querySelector('.pk-span-async-chip')?.textContent ?? null,
                    entry('a').querySelector('.pk-span-async-chip')?.getAttribute('aria-hidden'),
                    entry('a').querySelector('.pk-gantt-name__toggle').getAttribute('aria-label'),
                    pct(bar('a').style.left), pct(bar('a').style.width),
                    pct(bar('g').style.left), pct(bar('g').style.width)
                ];
            })()
            """);

        @SuppressWarnings("unchecked")
        List<Object> subtreeFacts = (List<Object>) facts;
        assertThat(subtreeFacts)
                .containsExactly(
                        "false",
                        true,
                        false,
                        "background",
                        "true",
                        "async task, internal span, background work",
                        "10.0",
                        "5.0",
                        "9.1",
                        "90.9");
    }

    /**
     * Task 11's other half, on the same fixture: {@code ?root=<spanId>} scopes the tab to
     * one subtree, on that subtree's own basis. Two things this guards: the default-collapse
     * pass must not collapse the very entry the scoped view is rendering - skipped as the
     * tab's own first entry - or the isolated view would open on nothing; and the trace's
     * other spans (the enclosing root and the sibling) must not appear at all.
     */
    @Test
    void scopingToAnAsyncEntryRendersOnlyThatSubtreeExpanded() {
        Object facts = importModule("trace-detail/tabs/spans.js", """
            (() => {
                const trace = {durationMs: 1000, startTimeMs: 0, rootSpan: {spanId: 'root', name: 'root', children: [
                    {spanId: 'a', name: 'async task', asyncEntry: true, startTimeMs: 100, durationMs: 50, children: [
                        {spanId: 'g', name: 'grandchild', startTimeMs: 120, durationMs: 200}]},
                    {spanId: 's', name: 'sibling', startTimeMs: 600, durationMs: 50}]}};
                const container = document.createElement('div');
                m.render(container, trace, {filters: {root: 'a'}});
                const rows = [...container.querySelectorAll('.pk-gantt-row')].map(row => row.dataset.spanId);
                const gEntry = container.querySelector('.pk-gantt-row[data-span-id="g"]').closest('.pk-gantt-span');
                return [rows.join(','), container.querySelector('.pk-gantt-toggle').getAttribute('aria-expanded'), gEntry.style.display];
            })()
            """);

        @SuppressWarnings("unchecked")
        List<Object> scopedFacts = (List<Object>) facts;
        assertThat(scopedFacts).containsExactly("a,g", "true", "");
    }

    /**
     * Task 11's URL half, on a real captured async subtree from the same endpoint Task 10's
     * row links to: the {@code ?root=<spanId>} deep link that row's own open button lands
     * on scopes the Spans tab to that span alone - its enclosing request root and
     * spring.handler are excluded - and it carries the "background" marker end to end from
     * the real backend. Then pins {@code url-state.js}'s documented rule rather than
     * fighting it (see spans.js's own module doc): switching tabs scopes params to the tab
     * that restored them, so a round trip through another tab must widen the Spans tab back
     * to the full tree, and the param must leave the URL with it.
     */
    @Test
    void deepLinkedRootParamScopesTheSpansTabAndIsClearedByATabRoundTrip() {
        String traceId = triggerOrderEnrichment();
        awaitTrace(traceId, ASYNC_SPAN_CAPTURED);

        openDashboard();
        dashboard.openTracesTab();
        dashboard.awaitListedTraceRowCount(traceId, 2);
        Locator asyncRow = page.locator(Dashboard.traceItem(traceId) + "[data-subtree-root-span-id]");
        String asyncSpanId = asyncRow.getAttribute("data-subtree-root-span-id");

        dashboard.openListedTrace(Dashboard.asyncTraceItem(traceId, asyncSpanId), traceId);
        overlay.waitFor("#pk-gantt-rows");

        assertThat(page.url())
                .as("the async row's own open button lands here - Task 10's own pin")
                .contains("#traces/" + traceId + "/spans?root=" + asyncSpanId);
        assertThat(visibleGanttRowCount())
                .as("scoped to the async entry alone; its enclosing request root and spring.handler are excluded")
                .isEqualTo(1);
        assertThat((String)
                        overlay.evaluate("root => root.querySelector('#pk-gantt-rows .pk-gantt-row').dataset.spanId"))
                .isEqualTo(asyncSpanId);
        assertThat((String) overlay.evaluate("root => root.querySelector('.pk-span-async-chip')?.textContent ?? null"))
                .as("the real backend's asyncEntry flag reaches the rendered chip")
                .isEqualTo("background");

        overlay.openTab("queries");
        overlay.openTab("spans");
        overlay.waitFor("#pk-gantt-rows");

        assertThat(visibleGanttRowCount())
                .as("a tab round-trip clears the tab's own params, widening back to the full tree")
                .isGreaterThan(1);
        assertThat(page.url()).as("the param is gone from the URL too").doesNotContain("root=");
    }

    /** The number of span rows currently rendered in the Spans tab's gantt, scoped or not. */
    private int visibleGanttRowCount() {
        return ((Number) overlay.evaluate("root => root.querySelectorAll('#pk-gantt-rows .pk-gantt-row').length"))
                .intValue();
    }
}

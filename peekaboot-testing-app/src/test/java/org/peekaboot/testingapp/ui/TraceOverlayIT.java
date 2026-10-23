package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.ColorScheme;
import io.micrometer.tracing.Span;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.tags.AsyncTaskMarker;
import org.peekaboot.testingapp.integration.ScheduledJobs;
import org.peekaboot.testingapp.integration.TestSpans;
import org.peekaboot.testingapp.order.OrderReconciler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import tools.jackson.databind.JsonNode;

/**
 * Exercises the real trace-detail overlay served by the running app in a real browser.
 * The overlay shares the design system with the toolbar (see ToolbarIT) and follows the
 * same theme; a dark-only palette hardcoded on :host would open a hard-dark fullscreen
 * overlay over a light dashboard, which is what overlayIsLightWhenTheStoredPreferenceIsLight
 * guards.
 */
class TraceOverlayIT extends TraceOverlayTestBase {

    /** The colour of the stand-in page header {@link #overlayStacksAboveHostPageChrome} puts on the page. */
    private static final int HOST_HEADER_RGB = 0x123456;

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    private String labelledBy() {
        return (String)
                overlay.evaluate("root => root.querySelector('#pk-tab-content').getAttribute('aria-labelledby')");
    }

    private String selectedTabId() {
        return (String) overlay.evaluate("root => root.querySelector('.pk-tab[aria-selected=\"true\"]').id");
    }

    /** The span duration the backend's SLOW verdict starts at, as the running app publishes it. */
    private long slowSpanThresholdMs() {
        return awaitJson(
                        contextPath() + "/peekaboot/api/features",
                        "features => features.slowSpanThresholdMs",
                        "/api/features named no slow-span threshold")
                .asLong();
    }

    /**
     * A trace the backend calls slow whose total is short: the request answers in 10ms and its
     * {@code @Async} task works for exactly the slow-span threshold, outside the synchronous
     * window the total reads.
     */
    private String storeSlowTraceWithAShortTotal(long thresholdMs) {
        String traceId = "overlay-slow-async-" + System.nanoTime();
        traceStore.addSpan(TestSpans.span(traceId, "root")
                .named("http get /orders/enrich")
                .kind(Span.Kind.SERVER)
                .at(0, 10)
                .tag("http.method", "GET")
                .tag("url.path", "/orders/enrich")
                .build());
        traceStore.addSpan(TestSpans.span(traceId, "async")
                .parent("root")
                .named(AsyncTaskMarker.CONTEXTUAL_NAME)
                .at(5, thresholdMs)
                .tag(AsyncTaskMarker.TAG_KEY, AsyncTaskMarker.TAG_VALUE)
                .build());
        return traceId;
    }

    /**
     * A trace with a long total and no slow span: a 10ms request and a 10ms call under it that
     * starts a threshold's length later, so the window spans the gap while neither span comes
     * near the threshold.
     */
    private String storeLongTraceWithoutASlowSpan(long thresholdMs) {
        String traceId = "overlay-long-quick-" + System.nanoTime();
        traceStore.addSpan(TestSpans.span(traceId, "root")
                .named("http get /api/person/all")
                .kind(Span.Kind.SERVER)
                .at(0, 10)
                .tag("http.method", "GET")
                .tag("url.path", "/api/person/all")
                .build());
        traceStore.addSpan(TestSpans.span(traceId, "late")
                .parent("root")
                .named("http get")
                .kind(Span.Kind.CLIENT)
                .at(thresholdMs, 10)
                .build());
        return traceId;
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
     * The overlay keeps the features it is handed for its whole lifetime - every SLOW
     * colour in its header, Spans and Queries tabs comes from those thresholds - and
     * nothing re-opens it once /api/features answers. So a deep link straight to a trace
     * must not open the overlay until the features are known, or a reader who configured
     * their own thresholds sees the shared link coloured by the defaults instead.
     *
     * <p>The features request is parked (a real request whose real response is merely
     * held back by parkFirst, as ComponentBuilderIT does for the api client's race) rather
     * than stubbed: the assertion is purely about ordering. A bounded wait for the
     * overlay's absence would pass vacuously if the deep link were broken outright, so
     * the same page then proves the overlay does open once the features are released.
     */
    @Test
    void aDeepLinkedOverlayOpensOnlyOnceTheFeaturesAreKnown() {
        openPersonsPage();
        String traceId = toolbar.traceId();

        ParkedRequest features = parkFirst("**/peekaboot/api/features");
        page.navigate(dashboardUrl("#traces/" + traceId));
        page.waitForSelector("#traces-tab.active");
        features.awaitParked();

        overlay.assertNotOpenedWithin(Duration.ofSeconds(1), "no overlay while /api/features is still outstanding");

        features.resume();

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

        openTraceDeepLink(traceId);
        overlay.waitFor(".pk-overlay__title-method");

        String methodText = overlay.text(".pk-overlay__title-method");
        assertThat(methodText)
                .as("no HTTP method exists for a scheduled job, so the header must fall back to "
                        + "the root-action label rather than a fake method")
                .isEqualTo("Scheduled");

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

        openTraceDeepLink(traceId);
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
     * The header's SLOW marking is the backend's per-trace verdict ({@code trace.slow}: some
     * span carries a SLOW or VERY_SLOW issue), the very flag the Traces tab's badge reads.
     * Applying the span thresholds to the trace's total instead called a 120 ms request slow
     * in the header while the list beside it did not, and a long trace with no slow span the
     * other way round. Each stored trace sits on the side of the threshold where the two rules
     * disagree, which the backend confirms before the header is read.
     */
    @Test
    void headerSlowMarkingFollowsTheBackendsVerdict() {
        long thresholdMs = slowSpanThresholdMs();
        String slowWithAShortTotal = storeSlowTraceWithAShortTotal(thresholdMs);
        String longWithoutASlowSpan = storeLongTraceWithoutASlowSpan(thresholdMs);
        awaitTrace(slowWithAShortTotal, "trace => trace.slow && trace.durationMs < " + thresholdMs);
        awaitTrace(longWithoutASlowSpan, "trace => !trace.slow && trace.durationMs >= " + thresholdMs);

        openOverlayFromTheDashboard(slowWithAShortTotal);
        assertThat(overlay.text(".pk-overlay__meta .pk-badge--warn").trim()).isEqualTo("SLOW");
        assertThat((String) overlay.evaluate("root => root.querySelector('.pk-overlay__duration').className"))
                .contains("slow");

        openOverlayFromTheDashboard(longWithoutASlowSpan);
        assertThat((Boolean) overlay.evaluate("root => !!root.querySelector('.pk-overlay__meta .pk-badge--warn')"))
                .isFalse();
        assertThat((String) overlay.evaluate("root => root.querySelector('.pk-overlay__duration').className"))
                .doesNotContain("slow");
    }
}

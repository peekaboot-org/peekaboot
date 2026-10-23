package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.BoundingBox;
import io.micrometer.tracing.Span;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.peekaboot.testingapp.Scheduler;
import org.peekaboot.testingapp.entity.CustomerOrder;
import org.peekaboot.testingapp.integration.OrderFixtures;
import org.peekaboot.testingapp.integration.ScheduledJobs;
import org.peekaboot.testingapp.integration.TestSpans;
import org.peekaboot.testingapp.order.OrderReconciler;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import tools.jackson.databind.JsonNode;

class TracesTabIT extends PlaywrightTestBase {

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderLineRepository orderLineRepository;

    @Autowired
    private PeekabootTracingProperties tracingProperties;

    @Autowired
    private TraceStore traceStore;

    private static final Pattern TRACES_PAGE_SIZE_PARAM = Pattern.compile("[?&]limit=(\\d+)");

    /** Mirrors the limit traces.js sends with every listing request. */
    private static final int TRACES_PAGE_SIZE = 50;

    /** OrderService.listOrders' deliberate N+1: findByOrderId, countByOrderId, existsById. */
    private static final int QUERIES_PER_ORDER = 3;

    /** The shared query stat on a listed trace row (trace-stats.js): grouped, like every other count. */
    private static final Pattern QUERY_STAT = Pattern.compile("([\\d,.]+) quer(?:y|ies)");

    /** A duration as format.js renders it: a number and its unit ("850ms", "1.23s", "1.50m"). */
    private static final Pattern RENDERED_DURATION = Pattern.compile("([0-9.]+)(ms|s|m)$");

    /**
     * Puts a failed SCHEDULED_JOB trace in the store by running the sample app's failing
     * job, and returns its id once the Errors bucket lists it - the same reasoning as
     * seedAnHttpTrace for a test that opens that bucket. TraceDeepLinkIT fires the same job
     * against the same store, so the wait names the run this call fired.
     */
    private String seedAnErrorTrace() {
        String traceId = ScheduledJobs.run(scheduledTaskHolder, Scheduler.class, "fixedRate");
        return awaitListedTrace("bucket=errors", "trace => trace.traceId === '" + traceId + "'");
    }

    /** One order with one line, for a test that needs a row to read rather than a query count. */
    private CustomerOrder seedAnOrder() {
        return OrderFixtures.seedOrder(
                orderRepository, orderLineRepository, 1L, "PLACED", 1, "PK-TABS-", "WIDGET-TABS");
    }

    /** The millis behind a rendered duration, so a row's own text can be compared with a threshold. */
    private static long durationMsOf(String rendered) {
        Matcher matcher = RENDERED_DURATION.matcher(rendered.trim());
        assertThat(matcher.find())
                .as("a rendered duration reads '<number><unit>': %s", rendered)
                .isTrue();
        double value = Double.parseDouble(matcher.group(1));
        return switch (matcher.group(2)) {
            case "ms" -> Math.round(value);
            case "s" -> Math.round(value * 1_000);
            default -> Math.round(value * 60_000);
        };
    }

    /** What the frontend colours and buckets a trace duration by; the Slow bucket's admission threshold. */
    private long slowTraceThresholdMs() {
        return awaitJson(
                        contextPath() + "/peekaboot/api/features",
                        "features => features.slowTraceThresholdMs",
                        "/api/features named no slow-trace threshold")
                .asLong();
    }

    /**
     * The loading block sits above the bucket row, so showing it for a refresh of a list
     * already on screen pushes the controls and the list down for the round trip. Only the
     * tab's first fetch shows it.
     */
    @Test
    void aRefreshOfTheShownListKeepsTheLoadingBlockHidden() {
        openTracesTabWithATrace();
        page.evaluate("""
                () => {
                    window.pkLoadingShown = false;
                    const loading = document.getElementById('traces-loading');
                    new MutationObserver(() => {
                        if (!loading.classList.contains('hidden')) window.pkLoadingShown = true;
                    }).observe(loading, {attributes: true, attributeFilter: ['class']});
                }
                """);

        page.waitForResponse(
                response -> response.url().contains("/api/traces/insights"), () -> page.click("#refresh-btn"));
        page.waitForFunction("() => document.getElementById('traces-loading').classList.contains('hidden')");

        assertThat(page.evaluate("() => window.pkLoadingShown")).isEqualTo(false);
    }

    /**
     * tabStrip()'s {silent: true} option exists for this:
     * handleHashChange() calls mainTabs.select(tabId, {silent: true}) precisely
     * so that syncing the strip's visual selection on a hash-driven boot doesn't also
     * re-trigger onSelect() - which pushes a bare {tab} hash with no detail, and would
     * silently strip the "/deadbeef" segment off a URL like "#traces/deadbeef"
     * before expandTraceById() even runs. Deep-linking straight to a trace detail URL
     * (not clicking into it - clickingATraceOpensTheOverlayAndDeepLinks below goes
     * through context.openTrace, which pushes the detail itself and would self-correct
     * the hash even without silent) must land with the URL intact once routing settles.
     */
    @Test
    void deepLinkingDirectlyToATraceDetailPreservesTheDetailSegment() {
        openTraceDeepLink("deadbeef");
        overlay.waitFor(".pk-overlay__error");

        assertThat(page.url()).endsWith("#traces/deadbeef");
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
     * and lands in the errors bucket with an error-log count but no status badge, while a
     * job that really throws does get one.
     * <p>
     * What genuinely differs between bucket responses is the item count, so the list is
     * checked against the count carried by the very response that rendered it: the
     * bucket count is app-global and uncapped, the list is capped at the page size, and
     * any class running alongside this one can log an ERROR between two requests. It has
     * to be the <em>filtered</em> count: the backend's default view leaves MAINTENANCE
     * out, and such a hidden trace that logs an ERROR is counted in {@code bucketCounts} but
     * never listed. The sanity assertion that the errors count is smaller than the all count
     * is what keeps this non-vacuous: the store always holds a mix of error and non-error
     * traces - the fixedRate() run below, whose ERROR log puts it in the errors bucket the
     * moment it is captured, alongside ordinary HTTP request traces for the dashboard's own
     * page loads.
     */
    @Test
    void tracesTabListsTracesAndBucketsThem() {
        seedAnErrorTrace();
        openTracesTabWithATrace();
        assertThat(page.textContent("#traces-bucket .pk-btn[data-bucket='all']"))
                .contains("All (");

        Response errorsResponse = page.waitForResponse(
                response -> response.url().contains("/api/traces/insights")
                        && response.url().contains("bucket=errors"),
                () -> page.click("#traces-bucket .pk-btn[data-bucket='errors']"));
        JsonNode errorsBucket = readJson(errorsResponse.text());
        JsonNode counts = errorsBucket.path("filteredBucketCounts");
        assertThat(counts.isObject())
                .as("the default view is a filter, so its listing carries the counts that match it")
                .isTrue();
        int errorsCount = counts.path("errors").asInt();
        int listedCount = errorsBucket.path("traces").size();

        assertThat(errorsCount).isPositive().isLessThan(counts.path("all").asInt());
        assertThat(pageSizeOf(errorsResponse))
                .as("mirrors the page size traces.js is written with")
                .isEqualTo(TRACES_PAGE_SIZE);
        assertThat(listedCount).isEqualTo(Math.min(errorsCount, TRACES_PAGE_SIZE));
        page.waitForFunction(
                "(expected) => document.querySelectorAll('#traces-list .pk-trace-item').length === expected",
                listedCount);
    }

    /**
     * The bucket buttons' counts are counts like any other: grouped, and in the dashboard's
     * locale. A request naming no type still gets the backend's default view (every type but
     * Maintenance, see TraceInsightsService.parseRootActionTypes), which counts as a
     * filter of its own - so {@code filteredBucketCounts} is what a bucket button reads even
     * on the very first, unfiltered load; the plain {@code bucketCounts} only ever reaches the
     * UI as the "/ total" denominator once the reader picks an explicit type or operation.
     * Nothing here selects the errors/type filter, so {@code userFiltered} is false and the
     * "All" button's label comes from {@code filteredBucketCounts.all} alone.
     *
     * <p>Proven on the real listing response's own {@code all} count, grouped with
     * 'ar-EG-u-nu-arab' - its Arabic-Indic digits differ from the browser's en-US ones
     * however small the count is - checked live with Intl in the page rather than
     * hard-coded, so this holds regardless of how many traces the shared store carries when
     * the suite runs.
     */
    @Test
    void tracesTabBucketCountsAreGroupedInTheDashboardsLocale() {
        page.addInitScript("localStorage.setItem('peekaboot-locale', 'ar-EG-u-nu-arab')");
        openDashboard();

        Response listing = page.waitForResponse(
                response -> response.url().contains("/api/traces/insights"), dashboard::openTracesTab);
        int all = readJson(listing.text())
                .path("filteredBucketCounts")
                .path("all")
                .asInt();

        assertThat(page.textContent("#traces-bucket .pk-btn[data-bucket='all']"))
                .isEqualTo("All (" + localeFormatted(all, "ar-EG-u-nu-arab") + ")");
    }

    /**
     * With up to four stat parts (spans, a query with its time, logs, a warning badge) plus
     * the row's id and its timestamp, a phone-width row must wrap between parts rather than
     * inside one - and {@code .pk-trace-item}'s own {@code overflow: hidden} must not clip a
     * part off the row's right edge either way.
     *
     * <p>Driven by a real {@link OrderReconciler#reconcileOrders()} run rather than a
     * rewritten listing response: against a PLACED order it seeds itself (the test profile
     * disables Flyway, so V4__order-data.sql's fixture rows never load here), it issues one
     * query (findAll()), logs an INFO summary and a WARN for that order, so its own trace
     * carries every part this row can show except an error badge - reconcileOrders() logs
     * no ERROR. The order is seeded first and removed in a {@code finally}:
     * {@code reconcileOrders()} never moves an order out of PLACED, and the H2 database is
     * shared by every IT the context cache reuses, so leaving it behind would permanently raise
     * every later real run's warning count.
     */
    @Test
    void tracesRowStatsFitAPhoneViewportWithoutWrappingOrClippingAPart() {
        page.setViewportSize(375, 800);
        CustomerOrder order = seedAnOrder();
        try {
            String traceId = ScheduledJobs.run(scheduledTaskHolder, OrderReconciler.class, "reconcileOrders");
            awaitTrace(traceId, warnedAbout(order));

            openDashboard();
            dashboard.openTracesTab();
            dashboard.awaitListedTrace(traceId);

            Object misfits = page.evaluate("""
                    selector => {
                        const row = document.querySelector(selector);
                        const rowRight = row.getBoundingClientRect().right;
                        return [...row.children].flatMap(part => {
                            const rect = part.getBoundingClientRect();
                            const problems = [];
                            if (rect.right > rowRight + 1) problems.push('past the row edge');
                            if (part.getClientRects().length > 1) problems.push('wraps inside itself');
                            return problems.length ? [part.textContent + ': ' + problems.join(', ')] : [];
                        });
                    }
                    """, Dashboard.traceItem(traceId) + " .pk-trace-item__stats");
            @SuppressWarnings("unchecked")
            List<Object> misfitParts = (List<Object>) misfits;
            assertThat(misfitParts).isEmpty();
        } finally {
            deleteOrder(order);
        }
    }

    /** A JS predicate on the trace JSON: a reconciler run's own trace, carrying its WARN about {@code order}. */
    private static String warnedAbout(CustomerOrder order) {
        return "trace => trace.rootActionType === 'SCHEDULED_JOB' && (trace.logs || []).some(log => "
                + "log.level === 'WARN' && log.message === 'order " + order.getReference()
                + " is still PLACED and has not been acknowledged')";
    }

    /** Undoes {@link #seedAnOrder()}, so a test that seeds its own order leaves the shared H2 database as it found it. */
    private void deleteOrder(CustomerOrder order) {
        OrderFixtures.deleteOrder(orderRepository, orderLineRepository, order);
    }

    /** The positive control for TracesTabWithoutSpanSourceIT: with a span exporter wired, the tab lists as usual. */
    @Test
    void theTracesTabListsAsUsualWhenSpansArePossible() {
        openDashboard();
        dashboard.openTracesTab();

        assertThat(page.isVisible("#traces-bucket")).isTrue();
        assertThat(page.isVisible("#traces-unavailable")).isFalse();
    }

    @Test
    void clickingATraceOpensTheOverlayAndDeepLinks() {
        String traceId = openTracesTabWithATrace();

        dashboard.openListedTrace(traceId);

        assertThat(page.url()).endsWith("#traces/" + traceId);
    }

    /**
     * The row is a pointer shortcut to its open button: a click on the stats line, outside
     * every control, opens the same trace. The row stays a plain element, so the button
     * remains its one tab stop.
     */
    @Test
    void clickingAnywhereOnATraceRowOpensIt() {
        String traceId = openTracesTabWithATrace();
        Locator header = page.locator(Dashboard.traceItem(traceId) + " .pk-trace-item__header");

        assertThat(header.evaluate("header => getComputedStyle(header).cursor")).isEqualTo("pointer");
        assertThat(header.evaluate("header => [header, header.parentElement].map(el => el.tabIndex)"))
                .isEqualTo(List.of(-1, -1));

        header.locator(".pk-trace-item__time").click();

        overlay.awaitTrace(traceId);
        assertThat(page.url()).endsWith("#traces/" + traceId);
    }

    /**
     * A link inside the row keeps its click to itself. Were the row to open the trace as
     * well, the history would hold that trace under the link's target, and Back would land
     * on a trace the reader never asked for.
     */
    @Test
    void followingALinkInsideATraceRowDoesNotOpenTheTraceToo() {
        String traceId = seedAnErrorTrace();
        openDashboard();
        dashboard.openTracesTab();
        dashboard.awaitListedTrace(traceId);

        page.click(Dashboard.traceItem(traceId) + " .pk-trace-item__scheduler-link");
        page.waitForSelector("#scheduled-tasks-tab.active");
        page.goBack();

        assertThat(page.url()).endsWith("#traces");
    }

    /** A drag that selects text in the row ends in a click on it, which selects rather than opens. */
    @Test
    void selectingTextInATraceRowDoesNotOpenIt() {
        String traceId = openTracesTabWithATrace();
        BoundingBox time = page.locator(Dashboard.traceItem(traceId) + " .pk-trace-item__time")
                .boundingBox();
        double y = time.y + time.height / 2;

        page.mouse().move(time.x + 1, y);
        page.mouse().down();
        page.mouse().move(time.x + time.width - 1, y, new Mouse.MoveOptions().setSteps(5));
        page.mouse().up();

        assertThat(page.querySelector(TraceOverlay.HOST)).isNull();
        assertThat(page.url()).endsWith("#traces");
        assertThat((String) page.evaluate("() => String(document.getSelection())"))
                .isNotBlank();
    }

    @Test
    void closingTheOverlayCleansTheHash() {
        dashboard.openListedTrace(openTracesTabWithATrace());

        page.keyboard().press("Escape");
        overlay.awaitClosed();

        assertThat(page.url()).endsWith("#traces");
    }

    /**
     * context.setUrlParams (main.js's currentContext()) re-parses the hash at call time
     * rather than closing over a detail/subview snapshot taken at the tab's last render().
     * A snapshot goes stale: opening a trace (context.openTrace, which pushes the hash and
     * calls expandTraceById) and closing it (expandTraceById's onClose, which pushes the
     * bare "#traces" back) both write the hash with pushAppHash, which fires no hashchange
     * and re-renders no tab. So the snapshot would only pick up "detail = the open trace's
     * id" through some *other* render while the overlay is open - in real use, the 30s
     * auto-refresh cycle; here, a manual refresh click makes it deterministic. Closing the
     * overlay then clears the real hash back to plain "#traces" but leaves the snapshot
     * behind, and the very next filter change would replace the hash with the just-closed
     * trace's id still attached, silently reopening it on reload/share.
     */
    @Test
    void closingAnOverlayThenFilteringDoesNotResurrectTheClosedTrace() {
        dashboard.openListedTrace(openTracesTabWithATrace());

        // Forces a full renderData() cycle while the overlay is open, so a setUrlParams
        // closure taken at render time would pick up the open trace's id as "detail" -
        // the same thing a real 30s auto-refresh cycle would eventually do on its own.
        // A real pointer click on the button is unusable here: the full-screen overlay
        // intercepts it, so this invokes the button's own click handler directly instead.
        page.evaluate("() => document.getElementById('refresh-btn').click()");
        page.waitForFunction("() => !document.getElementById('refresh-icon').classList.contains('pk-spinning')");

        page.keyboard().press("Escape");
        overlay.awaitClosed();
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
        String traceId = seedAnErrorTrace();
        openDashboard("#traces?bucket=errors", "#traces-bucket .pk-btn[data-bucket='errors'][aria-pressed='true']");
        dashboard.awaitListedTrace(traceId);

        dashboard.openListedTrace(traceId);
        overlay.openTab("logs");
        overlay.waitFor("#pk-log-level");
        page.waitForFunction("() => window.location.hash.includes('/logs')");

        overlay.evaluate("root => { const sel = root.querySelector('#pk-log-level'); sel.value = 'ERROR';"
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
     * The trace-detail re-open guard asks the overlay what it shows (trace-detail.js's
     * shownTraceTarget) rather than keeping a private flag in main.js. A flag desyncs on two
     * hash-driven opens in a row - e.g. deep-linking from
     * one trace straight into another, or a Back/Forward step that lands on a different
     * trace: openTraceDetail's synchronous closeTraceDetail() call fires the *first*
     * trace's still-registered onClose callback, which would clear the flag before the
     * *second* traceId was even recorded, and the next hashchange landing back on the
     * (already open) second trace would then fail the "already open" check and tear the
     * overlay down to rebuild it for no reason - the exact flicker the guard exists to
     * prevent. The overlay's own record cannot desync: openTraceDetail clears the old one and
     * sets the new one in a single call, whichever entry point opened it.
     * <p>
     * Marks the host with a throwaway attribute right after switching traces, then
     * re-fires the very hashchange event Back/Forward (or a redundant navigation) would
     * produce for the trace already showing: a rebuilt overlay is a fresh DOM node and
     * loses the marker, while a guard that correctly recognizes the trace is already open
     * leaves the marked node untouched.
     */
    @Test
    void revisitingAnAlreadyOpenTraceAfterSwitchingDoesNotRebuildTheOverlay() {
        // two page loads, so two distinct traces of this test's own to switch between
        String firstTraceId = seedAnHttpTrace();
        String secondTraceId = openTracesTabWithATrace();
        assertThat(secondTraceId).isNotEqualTo(firstTraceId);

        // Deep-link straight to the first trace - main.js's own hash-driven
        // expandTraceById path, which is what registers the onClose callback that the
        // switch below fires early.
        page.evaluate("id => { window.location.hash = '#traces/' + id; }", firstTraceId);
        overlay.awaitTrace(firstTraceId);

        // Straight to a *different* trace by hash, without closing the first - the
        // sequence that desyncs a flag-based guard (see the javadoc above).
        page.evaluate("id => { window.location.hash = '#traces/' + id; }", secondTraceId);
        overlay.awaitTrace(secondTraceId);

        page.evaluate("() => { document.getElementById('peekaboot-trace-overlay').dataset.testMarker = 'stable'; }");

        // Re-fire the hashchange for the trace that's already open, without changing the
        // hash itself - what Back/Forward landing back on it produces.
        page.evaluate("() => window.dispatchEvent(new Event('hashchange'))");

        assertThat(page.getAttribute(TraceOverlay.HOST, "data-test-marker")).isEqualTo("stable");
    }

    /**
     * A tab switch inside the overlay rewrites the URL, and Back/Forward can land on that URL
     * again. The re-open guard has to count it as already showing, or it tears the overlay down
     * and loses the reader's place in it.
     */
    @Test
    void replayingTheUrlAnOverlayTabSwitchWroteDoesNotRebuildTheOverlay() {
        String traceId = seedAnHttpTrace();
        openTraceDeepLink(traceId);
        overlay.openTab("queries");
        page.waitForFunction("id => window.location.hash === '#traces/' + id + '/queries'", traceId);

        page.evaluate("() => { document.getElementById('peekaboot-trace-overlay').dataset.testMarker = 'stable'; }");
        page.evaluate("() => window.dispatchEvent(new Event('hashchange'))");

        assertThat(page.getAttribute(TraceOverlay.HOST, "data-test-marker")).isEqualTo("stable");
    }

    /** The page size traces.js asked the listing endpoint for. */
    private static int pageSizeOf(Response listing) {
        Matcher matcher = TRACES_PAGE_SIZE_PARAM.matcher(listing.url());
        assertThat(matcher.find())
                .as("traces.js names its page size: %s", listing.url())
                .isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    /**
     * What the README promises the {@code /orders} page shows: the deliberate N+1's queries
     * counted on the listed trace row, rendered as the shared query stat. The count is read
     * off the row, not off the API, because the row is what a reader judges the page by.
     */
    @Test
    void theOrdersPageTraceListsTheQueryCountOfItsNPlusOne() {
        CustomerOrder order = seedAnOrder();
        try {
            long orders = orderRepository.count();
            page.navigate(baseUrl + "/orders");
            String traceId = toolbar.traceId();
            awaitTrace(traceId, ROOT_SPAN_EXPORTED);

            openDashboard();
            dashboard.openTracesTab();
            dashboard.awaitListedTrace(traceId);

            List<String> stats =
                    page.locator(Dashboard.traceItem(traceId) + " .pk-stat").allTextContents();
            String queryStat = stats.stream()
                    .filter(stat -> QUERY_STAT.matcher(stat).find())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no stat reads '<n> queries': " + stats));
            Matcher queries = QUERY_STAT.matcher(queryStat);
            assertThat(queries.find())
                    .as("the row's query stat reads '<n> queries': %s", queryStat)
                    .isTrue();
            assertThat(parseGroupedInt(queries.group(1)))
                    .as(
                            "the N+1 runs one query for the list plus %d per order, over %d orders",
                            QUERIES_PER_ORDER, orders)
                    .isGreaterThanOrEqualTo((int) (orders * QUERIES_PER_ORDER + 1));
        } finally {
            deleteOrder(order);
        }
    }

    /**
     * The Slow bucket, which no other UI test opens: the report endpoint sleeps its way past
     * the slow-trace threshold, so its own trace has to be there and not in the default view's
     * company by accident.
     */
    @Test
    void theSlowReportTraceIsListedInTheSlowBucket() {
        CustomerOrder order = seedAnOrder();
        try {
            String traceId = traceIdOf(page.navigate(baseUrl + "/api/orders/" + order.getId() + "/report"));
            awaitListedTrace("bucket=slow", "trace => trace.traceId === '" + traceId + "'");

            openDashboard("#traces?bucket=slow", "#traces-bucket .pk-btn[data-bucket='slow'][aria-pressed='true']");
            dashboard.awaitListedTrace(traceId);

            String rendered = page.locator(Dashboard.traceItem(traceId) + " .pk-trace-item__duration")
                    .textContent();
            assertThat(durationMsOf(rendered))
                    .as("a Slow-bucket row shows the duration that put it there: %s", rendered)
                    .isGreaterThanOrEqualTo(slowTraceThresholdMs());
        } finally {
            deleteOrder(order);
        }
    }

    /**
     * The reverse of schedulerTracesLinkArrivesFiltered: a scheduled-job row links back to the
     * Scheduled Tasks tab. Deliberately unfiltered - the tab lists every task - so the
     * assertion is where it lands, not what it carries.
     */
    @Test
    void aScheduledJobRowLinksToTheScheduledTasksTab() {
        String traceId = seedAnErrorTrace();
        openDashboard();
        dashboard.openTracesTab();
        dashboard.awaitListedTrace(traceId);

        page.click(Dashboard.traceItem(traceId) + " .pk-trace-item__scheduler-link");

        page.waitForSelector("#scheduled-tasks-tab.active");
        assertThat(dashboard.selectedTab()).isEqualTo("scheduled-tasks");
    }

    /**
     * A trace past the max-spans-per-trace cap says so wherever it is shown: the store dropped
     * its oldest spans, so the counts beside the badge are incomplete and the reader has to be
     * told once in the listing and again in the overlay they opened from it. Written straight
     * to the store - no demo endpoint issues five hundred spans.
     */
    @Test
    void aTruncatedTraceSaysSoInTheListingAndInTheOverlay() {
        String traceId = "tabs-truncated-" + System.nanoTime();
        // The children go in first: the store drops the oldest span past the cap, and the root
        // is what the listing and the overlay hang everything else from.
        for (int i = 0; i < tracingProperties.getMaxSpansPerTrace(); i++) {
            traceStore.addSpan(TestSpans.span(traceId, String.format("child%011x", i))
                    .parent("root")
                    .named("work")
                    .at(1, 1)
                    .build());
        }
        traceStore.addSpan(TestSpans.span(traceId, "root")
                .named("GET /truncated-fixture")
                .kind(Span.Kind.SERVER)
                .at(0, 40)
                .tag("http.method", "GET")
                .tag("url.path", "/truncated-fixture")
                .build());

        openDashboard();
        dashboard.openTracesTab();
        dashboard.awaitListedTrace(traceId);

        assertThat(page.textContent(Dashboard.traceItem(traceId) + " .pk-badge--warn"))
                .isEqualTo("TRUNCATED");
        assertThat(page.getAttribute(Dashboard.traceItem(traceId) + " .pk-badge--warn", "title"))
                .as("the badge says what the counts beside it are missing")
                .contains("max-spans-per-trace");

        dashboard.openListedTrace(traceId);

        assertThat(overlay.text(".pk-overlay__meta .pk-badge--warn")).isEqualTo("TRUNCATED");
    }
}

package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitForSelectorState;
import io.micrometer.tracing.Span;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.peekaboot.testingapp.Scheduler;
import org.peekaboot.testingapp.entity.CustomerOrder;
import org.peekaboot.testingapp.entity.OrderLine;
import org.peekaboot.testingapp.integration.ScheduledJobs;
import org.peekaboot.testingapp.integration.TestSpans;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import tools.jackson.databind.JsonNode;

class DashboardTabsIT extends PlaywrightTestBase {

    /** The observed datasource - a connection acquired on it outside any traced work starts a pool trace. */
    @Autowired
    private DataSource dataSource;

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

    /** The shared query stat on a listed trace row (trace-stats.js). */
    private static final Pattern QUERY_STAT = Pattern.compile("(\\d+) quer(?:y|ies)");

    /** The meters tab's readout while a filter is active (meters.js's updateCount). */
    private static final Pattern METERS_COUNT_READOUT = Pattern.compile("(\\d+) / (\\d+) metrics");

    /** A duration as format.js renders it: a number and its unit ("850ms", "1.23s", "1.50m"). */
    private static final Pattern RENDERED_DURATION = Pattern.compile("([0-9.]+)(ms|s|m)$");

    /** Layout edges compare to within a pixel: grid tracks land on subpixel positions. */
    private static final Offset<Double> ONE_PIXEL = within(1.0);

    /**
     * Puts an ordinary HTTP_REQUEST trace in the store by loading the page under the dev
     * toolbar, and returns its id once the store serves it. Nothing else guarantees a trace:
     * the failing scheduled jobs run once at startup, whether or not the tracer was ready to
     * capture them, so a test waiting for a listed trace would otherwise depend on the
     * tests that happened to run before it.
     */
    private String seedAnHttpTrace() {
        openPersonsPage();
        String traceId = toolbar.traceId();
        awaitTrace(traceId, ROOT_SPAN_EXPORTED);
        return traceId;
    }

    /**
     * Puts a failed SCHEDULED_JOB trace in the store by running the sample app's failing
     * job, and returns its id once the Errors bucket lists it - the same reasoning as
     * seedAnHttpTrace for a test that opens that bucket. TraceDeepLinkIT fires the same job
     * against the same store, so the wait names the run this call fired.
     */
    private String seedAnErrorTrace() {
        String traceId =
                awaitErrorLoggingJobRun(() -> ScheduledJobs.run(scheduledTaskHolder, Scheduler.class, "fixedRate"));
        return awaitListedTrace("bucket=errors", "trace => trace.traceId === '" + traceId + "'");
    }

    /** One order with one line, for a test that needs a row to read rather than a query count. */
    private CustomerOrder seedAnOrder() {
        CustomerOrder order = new CustomerOrder();
        order.setReference("PK-TABS-" + System.nanoTime());
        order.setCustomerId(1L);
        order.setStatus("PLACED");
        order.setPlacedAt(Instant.parse("2026-08-20T08:00:00Z"));
        CustomerOrder saved = orderRepository.save(order);

        OrderLine line = new OrderLine();
        line.setOrderId(saved.getId());
        line.setSku("WIDGET-TABS");
        line.setQuantity(1);
        line.setUnitPrice(new BigDecimal("19.99"));
        orderLineRepository.save(line);
        return saved;
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

    /** Opens the Traces tab with the caller's own trace listed, and returns that trace's id. */
    private String openTracesTabWithATrace() {
        String traceId = seedAnHttpTrace();
        openDashboard();
        dashboard.openTracesTab();
        dashboard.awaitListedTrace(traceId);
        return traceId;
    }

    /** One Overview stat tile, by the id its insights config gives it. */
    private static String tile(String tileId) {
        return "#insights-tiles .pk-insight-tile[data-tile-id='" + tileId + "']";
    }

    /** An element's border box in viewport pixels, as getBoundingClientRect reports it. */
    private record Box(double left, double right, double top, double bottom) {
        double width() {
            return right - left;
        }
    }

    private Box box(String selector) {
        @SuppressWarnings("unchecked")
        Map<String, Number> rect = (Map<String, Number>) page.evalOnSelector(
                selector,
                "el => { const r = el.getBoundingClientRect();"
                        + " return {left: r.left, right: r.right, top: r.top, bottom: r.bottom}; }");
        return new Box(
                rect.get("left").doubleValue(),
                rect.get("right").doubleValue(),
                rect.get("top").doubleValue(),
                rect.get("bottom").doubleValue());
    }

    /** Two tiles side by side on one row, together spanning {@code column} edge to edge. */
    private static void assertPairSpans(Box leftTile, Box rightTile, Box column) {
        assertThat(rightTile.top()).as("the pair shares a row").isCloseTo(leftTile.top(), ONE_PIXEL);
        assertThat(leftTile.left()).as("the pair's left edge").isCloseTo(column.left(), ONE_PIXEL);
        assertThat(rightTile.right()).as("the pair's right edge").isCloseTo(column.right(), ONE_PIXEL);
    }

    /**
     * Both values travel the actuator endpoints and the mappers before they reach a row, so
     * pinning them to the running JVM's own is what tells a real render from a card of
     * labels with nothing behind them.
     */
    @Test
    void overviewShowsJavaAndSystemCards() {
        openDashboard();
        page.waitForSelector("#java-info .pk-kv");

        assertThat(dashboard.kvValue("#java-info", "Version")).isEqualTo(System.getProperty("java.version"));
        assertThat(dashboard.kvValue("#os-info", "Architecture")).isEqualTo(System.getProperty("os.arch"));
    }

    @Test
    void healthBannerReflectsApplicationHealth() {
        openDashboard();
        page.waitForFunction("() => document.querySelector('#health-status-text').textContent.trim() !== ''");

        assertThat(page.textContent("#health-status-text")).isEqualTo("UP");
    }

    /**
     * A live JVM always holds some heap and never all of it, so a fill outside (0, 100] means
     * the real percentage never reached the primitive - a bare meter() with no argument
     * clamps to 0 and would still render.
     */
    @Test
    void memoryMeterIsRenderedWithTheSharedPrimitive() {
        openDashboard();
        page.waitForSelector("#memory-info .pk-meter__fill");

        String width = (String) page.evalOnSelector("#memory-info .pk-meter__fill", "el => el.style.width");
        assertThat(Double.parseDouble(width.replace("%", "")))
                .as("heap fill, rendered as %s", width)
                .isGreaterThan(0)
                .isLessThanOrEqualTo(100);
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
        assertThat(page.textContent("#insights-tiles [data-tile-id='uptime'] .pk-insight-tile__value"))
                .as("a live tile resolves in a real app")
                .isNotEqualTo("-");
    }

    /**
     * Beside two card columns the four stat tiles share one row, each pair spanning one card
     * column edge to edge.
     */
    @Test
    void theInsightTilesLineUpWithTheTwoCardColumns() {
        openDashboard();
        page.waitForSelector(tile("uptime"));

        Box startedAt = box(tile("started-at"));
        Box readyTime = box(tile("ready-time"));
        assertPairSpans(startedAt, box(tile("startup-time")), box("#build-card"));
        assertPairSpans(readyTime, box(tile("uptime")), box("#git-card"));
        assertThat(readyTime.top()).as("both pairs share one row").isCloseTo(startedAt.top(), ONE_PIXEL);
    }

    /**
     * Over a single card column the stat tiles form a two-by-two, both rows spanning the cards
     * edge to edge.
     */
    @Test
    void theInsightTilesFormATwoByTwoOverASingleCardColumn() {
        page.setViewportSize(600, 900);
        openDashboard();
        page.waitForSelector(tile("uptime"));

        Box cards = box("#build-card");
        Box startedAt = box(tile("started-at"));
        Box startupTime = box(tile("startup-time"));
        Box readyTime = box(tile("ready-time"));
        assertPairSpans(startedAt, startupTime, cards);
        assertPairSpans(readyTime, box(tile("uptime")), cards);
        assertThat(readyTime.top()).as("the second pair wraps below the first").isGreaterThan(startedAt.bottom());
        assertThat(readyTime.right()).as("the columns line up row to row").isCloseTo(startedAt.right(), ONE_PIXEL);
        assertThat(startupTime.width()).as("two equal columns").isCloseTo(startedAt.width(), ONE_PIXEL);
    }

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
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/deadbeef");
        overlay.waitFor(".pk-overlay__error");

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
     * Filtering never auto-expands a group - a matching group renders collapsed just like an
     * unfiltered one, so the header must be clicked open before a {@code <mark>} inside its
     * list becomes visible. That click is also the group's own collapse/expand contract, which
     * is why the collapsed state is asserted on the way in.
     */
    @Test
    void environmentFilterHighlightsMatchesInsideAGroupTheReaderOpens() {
        openDashboard();
        dashboard.openTab("environment");

        page.fill("#env-filter", "server.port");
        page.waitForSelector(
                "#property-sources mark", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.ATTACHED));
        assertThat(page.isVisible("#property-sources .pk-group__list")).isFalse();

        page.click("#property-sources .pk-group__header");

        page.waitForSelector("#property-sources mark");
        assertThat(page.isVisible("#property-sources .pk-group__list")).isTrue();
        assertThat(page.textContent("#property-sources mark")).contains("server.port");
    }

    @Test
    void expandedGroupSurvivesARefresh() {
        openDashboard();
        dashboard.openTab("config");
        page.click("#config-groups .pk-group__header");
        assertThat(page.isVisible("#config-groups .pk-group__list")).isTrue();

        page.click("#refresh-btn");
        page.waitForFunction("() => !document.getElementById('refresh-icon').classList.contains('pk-spinning')");

        assertThat(page.isVisible("#config-groups .pk-group__list")).isTrue();
    }

    @Test
    void loggersTabShowsLevelsAndRespectsConfiguredOnly() {
        openDashboard();
        dashboard.openTab("loggers");

        int rows = page.locator("#loggers-list .pk-kv").count();
        assertThat(page.locator("#loggers-list .pk-kv .pk-badge").count())
                .as("every logger row renders its effective level, not just its name")
                .isEqualTo(rows);

        int all = page.querySelectorAll("#loggers-list .pk-group").size();
        page.check("#loggers-configured-only");
        page.waitForFunction("(prev) => document.querySelectorAll('#loggers-list .pk-group').length !== prev", all);
        int configured = page.querySelectorAll("#loggers-list .pk-group").size();

        assertThat(configured).isLessThan(all);
        assertThat(page.locator("#loggers-list .pk-kv__key").allTextContents())
                .as("ROOT always carries a configured level, so the filtered list keeps it")
                .contains("ROOT");
    }

    @Test
    void scheduledTasksTabGroupsByScheduleType() {
        openDashboard();
        dashboard.openTab("scheduled-tasks");

        assertThat(page.locator("#scheduled-tasks-groups .pk-group__name").allTextContents())
                .as("a group per schedule type the app actually registers, and no other")
                .isNotEmpty()
                .isSubsetOf("Cron Tasks", "Fixed Delay Tasks", "Fixed Rate Tasks");
        assertThat(page.locator("#scheduled-tasks-groups .pk-tasks-summary .pk-badge")
                        .first()
                        .textContent())
                .as("the summary counts the tasks Spring registered, not the rows that rendered")
                .isEqualTo("Total: " + scheduledTaskHolder.getScheduledTasks().size());
    }

    @Test
    void metersTabFiltersAndCounts() {
        openDashboard();
        dashboard.openTab("meters");

        page.fill("#meters-filter", "jvm.memory");
        page.waitForFunction("() => document.querySelector('#meters-count').textContent.includes('/')");

        String readout = page.textContent("#meters-count");
        Matcher counts = METERS_COUNT_READOUT.matcher(readout);
        assertThat(counts.matches())
                .as("the count readout reads '<matched> / <all> metrics': %s", readout)
                .isTrue();
        assertThat(Integer.parseInt(counts.group(1)))
                .as("jvm.memory matches some meters but not all of them: %s", readout)
                .isPositive()
                .isLessThan(Integer.parseInt(counts.group(2)));
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
        dashboard.openTab("meters");

        page.fill("#meters-filter", "jvm.memory");
        page.waitForFunction("() => window.location.hash.includes('q=jvm.memory')");

        dashboard.openTab("environment");

        dashboard.openTab("meters");
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
        dashboard.openTab("meters");

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
     * and lands in the errors bucket with an error-log count but no status badge, while a
     * job that really throws does get one.
     * <p>
     * What genuinely differs between bucket responses is the item count, so the list is
     * checked against the count carried by the very response that rendered it: the
     * bucket count is app-global and uncapped, the list is capped at the page size, and
     * any class running alongside this one can log an ERROR between two requests. It has
     * to be the <em>filtered</em> count: the backend's default view leaves CONNECTION_POOL
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
        String traceId = seedAnHttpTrace();
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces?bucket=bogus");
        dashboard.awaitListedTrace(traceId);

        assertThat(page.getAttribute("#traces-bucket .pk-btn[data-bucket='all']", "aria-pressed"))
                .isEqualTo("true");
        assertThat(page.getAttribute("#traces-bucket .pk-btn[data-bucket='errors']", "aria-pressed"))
                .isEqualTo("false");
        assertThat(page.getAttribute("#traces-bucket .pk-btn[data-bucket='slow']", "aria-pressed"))
                .isEqualTo("false");
    }

    @Test
    void clickingATraceOpensTheOverlayAndDeepLinks() {
        String traceId = openTracesTabWithATrace();

        dashboard.openListedTrace(traceId);

        assertThat(page.url()).endsWith("#traces/" + traceId);
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
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces?bucket=errors");
        page.waitForSelector("#traces-bucket .pk-btn[data-bucket='errors'][aria-pressed='true']");
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
     * The scheduled-tasks "view traces" link pre-filters the Traces tab to that
     * scheduler's own SCHEDULED_JOB traces (rootActionType + rootOperation): a plain
     * "#traces?type=...&op=..." href the hash router lands on, restored by traces.js's own
     * URL reconciliation. Proves the link actually arrives filtered, not just that it
     * switches tabs.
     */
    @Test
    void schedulerTracesLinkArrivesFiltered() {
        openDashboard();
        dashboard.openTab("scheduled-tasks");
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
     * Connection-pool traces sit in the store but not in the default view: with no type in
     * the URL, traces.js names none in its request either and the backend answers with the
     * default view. Selecting the type's own chip reveals them and lands in the URL
     * (#traces?type=CONNECTION_POOL), so the revealed view stays shareable.
     */
    @Test
    void connectionPoolTracesAreHiddenByDefaultAndRevealedByTheirChip() throws SQLException {
        // What HikariCP maintenance does: acquire a pooled connection outside any traced
        // work, yielding a standalone CONNECTION_POOL trace.
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(1)).isTrue();
        }
        // the connection trace is the one the default view must hide, so the list needs a
        // trace of this test's own to render before the absence means anything
        String httpTraceId = seedAnHttpTrace();

        openDashboard();
        Response defaultResponse = page.waitForResponse(
                response -> response.url().contains("/api/traces/insights"),
                () -> page.click(Dashboard.tabButton("traces")));
        assertThat(defaultResponse.url()).doesNotContain("rootActionType");
        dashboard.awaitListedTrace(httpTraceId);
        assertThat(page.locator("#traces-list .pk-trace-item__icon[aria-label='Connection Pool']")
                        .count())
                .isZero();

        page.waitForResponse(
                response -> response.url().contains("rootActionType=CONNECTION_POOL"),
                () -> page.check("#traces-filter input[value='CONNECTION_POOL']"));

        assertThat(page.url()).endsWith("#traces?type=CONNECTION_POOL");
        page.waitForSelector("#traces-list .pk-trace-item__icon[aria-label='Connection Pool']");
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

    /**
     * The Overview tile row reads /api/insights/config on the dashboard's own refresh
     * cycle, but only while it is the tab on screen: a refresh with another tab showing
     * must not spend a request on tiles nobody is looking at. Switching back renders the
     * tab again, which is when the row catches up.
     */
    @Test
    void overviewSkipsTheTileFetchWhileAnotherTabIsShowing() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#environment");
        page.waitForSelector("#property-sources .pk-group__header");

        assertThatThrownBy(() -> page.waitForRequest(
                        "**/api/insights/config**",
                        new Page.WaitForRequestOptions().setTimeout(1000),
                        () -> page.click("#refresh-btn")))
                .as("no tile fetch while the Overview tab is hidden")
                .isInstanceOf(TimeoutError.class);

        page.waitForRequest("**/api/insights/config**", () -> page.click(Dashboard.tabButton("overview")));
        page.waitForSelector("#insights-tiles .pk-insight-tile");
    }

    /**
     * What the README promises the {@code /orders} page shows: the deliberate N+1's queries
     * counted on the listed trace row, rendered as the shared query stat. The count is read
     * off the row, not off the API, because the row is what a reader judges the page by.
     */
    @Test
    void theOrdersPageTraceListsTheQueryCountOfItsNPlusOne() {
        seedAnOrder();
        long orders = orderRepository.count();
        page.navigate(baseUrl + "/orders");
        String traceId = toolbar.traceId();
        awaitTrace(traceId, ROOT_SPAN_EXPORTED);

        openDashboard();
        dashboard.openTracesTab();
        dashboard.awaitListedTrace(traceId);

        String queryStat =
                page.locator(Dashboard.traceItem(traceId) + " .pk-stat").first().textContent();
        Matcher queries = QUERY_STAT.matcher(queryStat);
        assertThat(queries.find())
                .as("the row's query stat reads '<n> queries': %s", queryStat)
                .isTrue();
        assertThat(Integer.parseInt(queries.group(1)))
                .as("the N+1 runs one query for the list plus %d per order, over %d orders", QUERIES_PER_ORDER, orders)
                .isGreaterThanOrEqualTo((int) (orders * QUERIES_PER_ORDER + 1));
    }

    /**
     * The Slow bucket, which no other UI test opens: the report endpoint sleeps its way past
     * the slow-trace threshold, so its own trace has to be there and not in the default view's
     * company by accident.
     */
    @Test
    void theSlowReportTraceIsListedInTheSlowBucket() {
        long orderId = seedAnOrder().getId();
        page.navigate(baseUrl + "/api/orders/" + orderId + "/report");
        String traceId = awaitListedTrace("bucket=slow", "trace => trace.rootOperation.includes('/report')");

        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces?bucket=slow");
        page.waitForSelector("#traces-bucket .pk-btn[data-bucket='slow'][aria-pressed='true']");
        dashboard.awaitListedTrace(traceId);

        String rendered = page.locator(Dashboard.traceItem(traceId) + " .pk-trace-item__duration")
                .textContent();
        assertThat(durationMsOf(rendered))
                .as("a Slow-bucket row shows the duration that put it there: %s", rendered)
                .isGreaterThanOrEqualTo(slowTraceThresholdMs());
    }

    /**
     * The negative half of the feature gating: the strip hides a tab whose feature is off.
     * The real /api/features response is served with one flag flipped rather than a fabricated
     * body, so every other flag - and the thresholds the tabs colour by - stay as the running
     * app reports them; the Traces tab is the positive control that the flip was surgical.
     */
    @Test
    void aTabIsHiddenWhenItsFeatureIsOff() {
        page.route("**/peekaboot/api/features", route -> {
            APIResponse features = route.fetch();
            route.fulfill(new Route.FulfillOptions()
                    .setResponse(features)
                    .setBody(features.text().replace("\"metrics\":true", "\"metrics\":false")));
        });

        openDashboard();

        assertThat(page.isVisible(Dashboard.tabButton("meters"))).isFalse();
        assertThat(page.isVisible(Dashboard.tabButton("traces")))
                .as("only the metrics flag was flipped")
                .isTrue();
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
     * The meters tab fetches its own endpoint, so it owns the failure too: a rejection renders
     * the tab's own message in place of the group list rather than leaving the loading block
     * up for good. The request is refused by Chromium's real network stack.
     */
    @Test
    void theMetersTabSaysSoWhenItsOwnFetchFails() {
        page.route("**/peekaboot/api/metrics", route -> route.abort());

        openDashboard();
        page.click(Dashboard.tabButton("meters"));

        page.waitForSelector("#meters-list .pk-empty");
        assertThat(page.textContent("#meters-list .pk-empty")).startsWith("Failed to load metrics");
    }

    /**
     * A task whose last run threw shows the exception beside its FAILED badge. The sample app
     * has a failing job, but nothing records an outcome for a run fired outside the scheduler,
     * so the row is rendered from the payload shape the backend would send.
     */
    @Test
    void aFailedTaskShowsItsStatusAndTheExceptionFromTheLastRun() {
        importModule("dashboard/tabs/scheduled-tasks.js", """
            (() => {
                const container = document.createElement('div');
                container.innerHTML = '<div id="scheduled-tasks-groups"></div>';
                container.id = 'pk-tasks-test-container';
                document.body.appendChild(container);
                m.render(container, {scheduledTasks: {tasks: [{target: 'demo.Job.run', type: 'FIXED_RATE',
                    intervalMs: 60000, lastStatus: 'FAILED', lastExecution: 0,
                    lastException: 'java.lang.IllegalStateException: fixedDelay failed'}],
                    cronCount: 0, fixedDelayCount: 0, fixedRateCount: 1}}, {});
            })()
            """);

        assertThat(page.textContent("#pk-tasks-test-container .pk-badge--error"))
                .isEqualTo("FAILED");
        assertThat(page.textContent("#pk-tasks-test-container .pk-task__exception"))
                .contains("Error during last Execution:")
                .contains("fixedDelay failed");
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

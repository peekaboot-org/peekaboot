package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.ColorScheme;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real trace-detail overlay served by the running app in a real browser.
 * Migrated onto the shared design system alongside the toolbar (see ToolbarTest) - the
 * overlay used to hardcode a dark-only palette on :host with no theme awareness at all,
 * so a light dashboard opened a hard-dark fullscreen overlay. That is the specific defect
 * overlayIsLightWhenTheStoredPreferenceIsLight proves fixed.
 */
class TraceOverlayTest extends PlaywrightTestBase {

    private void openOverlayFromToolbar() {
        openPersonsPage();
        openOverlayFromLoadedToolbar();
    }

    /**
     * Opens the overlay for the index page's error-path trace - the one trace this app
     * produces whose logs are spread over more than one span: the request handler's own
     * ERROR log, and the INFO line PersonQueryService.findAll() writes inside its own
     * observed span. Returns that trace's id.
     *
     * <p>Two things this deliberately does not do the short way. It polls the insights
     * endpoint until the second logging span has actually landed, because the toolbar
     * publishes the trace id as soon as the response is written while spans reach the
     * store asynchronously afterwards - the same ingestion race
     * overlayTabStripExposesAsARealTablistInTheAccessibilityTree documents (observed on
     * macOS). The poll lives in a single evaluate() for that test's reason too: a second,
     * separate fetch could race the trace's eviction from a bounded store. It counts spans
     * carrying logs the way spans.js itself derives them (span.logs, walked down
     * span.children), so the precondition is measured against the very shape the Spans tab
     * renders its "N logs" toggles from.
     *
     * <p>And it opens through the dashboard's hash route rather than the toolbar, because
     * only that path supplies an urlState (main.js's expandTraceById -> buildTraceUrlState).
     * The toolbar calls openTraceDetail with none, so there every urlState write is a silent
     * no-op and the URL assertions below could not fail even if the wiring were deleted.
     */
    private String openOverlayForTheMultiSpanLogTrace() {
        page.navigate(baseUrl + "/?error=true");
        page.waitForSelector("#peekaboot-toolbar-host");
        page.waitForFunction("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-trace').textContent.trim() !== '-'");

        String traceId = (String) page.evaluate("""
                async () => {
                    const spansWithLogs = span => !span ? 0
                        : ((span.logs || []).length > 0 ? 1 : 0)
                          + (span.children || []).reduce((n, child) => n + spansWithLogs(child), 0);
                    for (let attempt = 0; attempt < 150; attempt++) {
                        const copyEl = document.getElementById('peekaboot-toolbar-host')
                            .shadowRoot.querySelector('#pk-trace .pk-copy');
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

        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/" + traceId);
        page.waitForFunction(
                "() => !!document.getElementById('peekaboot-trace-overlay')?.shadowRoot"
                        + "?.querySelector('#pk-gantt-rows')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));
        return traceId;
    }

    private void openOverlayFromLoadedToolbar() {
        page.waitForFunction("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-trace').textContent.trim() !== '-'");
        page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('.pk-toolbar').click()");
        page.waitForSelector("#peekaboot-trace-overlay");
        // #peekaboot-trace-overlay (the host) exists as soon as openTraceDetail() creates
        // it - well before fetchAndRender() replaces the loading placeholder with either
        // the success render() (which registers the ESC handler) or the error state. This
        // helper is used by both paths (see closeButtonDismissesTheOverlayOnTheErrorPath),
        // so it waits for the loading placeholder to be gone rather than for a
        // success-only element, and cannot race either outcome.
        page.waitForFunction(
                "() => !document.getElementById('peekaboot-trace-overlay').shadowRoot"
                        + ".querySelector('.pk-overlay__loading')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));
    }

    private String overlayVar(String property) {
        return (String) page.evaluate(
                "prop => getComputedStyle(document.getElementById('peekaboot-trace-overlay')"
                        + ".shadowRoot.querySelector('.pk-overlay')).getPropertyValue(prop).trim()",
                property);
    }

    /**
     * Headless Chromium's own default is prefers-color-scheme: light, so a naive
     * "storage wins" test in the light direction would pass even with resolveTheme()/
     * applyTheme() deleted entirely - light is also tokens.css's bare :root,:host default.
     * Forcing the OS preference to the opposite of what's stored (mirroring ToolbarTest)
     * makes each test fail if the stored preference ever stops taking priority.
     */
    private void emulateOppositeOsPreference(ColorScheme osPreference) {
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(osPreference));
    }

    /** The defect that motivated this work: a light dashboard opening a dark overlay. */
    @Test
    void overlayIsLightWhenTheStoredPreferenceIsLight() {
        setStoredTheme("light");
        emulateOppositeOsPreference(ColorScheme.DARK);
        openOverlayFromToolbar();

        assertThat(overlayVar("--pk-bg")).isEqualTo("#ffffff");
    }

    @Test
    void overlayIsDarkWhenTheStoredPreferenceIsDark() {
        setStoredTheme("dark");
        emulateOppositeOsPreference(ColorScheme.LIGHT);
        openOverlayFromToolbar();

        assertThat(overlayVar("--pk-bg")).isEqualTo("#0d1117");
    }

    /**
     * Regression guard for a real defect an earlier review caught: fills that reuse
     * --pk-primary (this chip, the gantt "server" kind badge) or --pk-success (the
     * result-set row-count badge) for their background had their foreground accidentally
     * set to --pk-text-strong instead of the contrast-tuned --pk-on-primary/--pk-on-success
     * that components.css's .pk-badge already uses for the same fills - near-white text on
     * light-blue/light-green at ~2.3:1 in dark mode, where the pre-migration hardcoded
     * #000 gave 8.2-8.3:1. Pins the literal resolved colour rather than comparing against
     * the --pk-on-primary token itself, which would pass even if both sides regressed back
     * to the same wrong token.
     *
     * The pinned value is dark ink in both themes now that --pk-primary is the brand
     * green: white on it measures 2.61:1, so the light theme can no longer get away
     * with the plain white it used while --pk-primary was a blue.
     *
     * Drives a real ERROR log entry (matching ToolbarTest's
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
        page.waitForSelector("#peekaboot-toolbar-host");
        page.waitForFunction("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-trace').textContent.trim() !== '-'");
        page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('.pk-toolbar').click()");
        page.waitForSelector("#peekaboot-trace-overlay");
        // #peekaboot-trace-overlay exists as soon as openTraceDetail() creates the host -
        // well before render() builds the tab strip, which only happens once the trace
        // fetch and the shared stylesheets have both resolved. Wait for the real tab
        // before clicking it.
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
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-log__span').click()");
        page.waitForFunction("() => !!document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-logs-filter-span')");

        String color = (String)
                page.evaluate("() => getComputedStyle(document.getElementById('peekaboot-trace-overlay').shadowRoot"
                        + ".querySelector('.pk-logs-filter-span')).color");

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
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-log__span').click()");
        page.waitForFunction("() => !!document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-logs-filter-span')");

        String chipText = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-logs-filter-span').textContent");

        assertThat(chipText.trim()).matches("^Span: .+\\([0-9a-f]{8}\\)\\s*×?$");
    }

    @Test
    void overlayShowsSpansTabByDefault() {
        openOverlayFromToolbar();

        String selected = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[aria-selected=\"true\"]').dataset.tab");
        assertThat(selected).isEqualTo("spans");
    }

    @Test
    void escapeClosesTheOverlay() {
        openOverlayFromToolbar();

        page.keyboard().press("Escape");

        page.waitForCondition(() -> page.querySelector("#peekaboot-trace-overlay") == null);
        assertThat(page.querySelector("#peekaboot-trace-overlay")).isNull();
    }

    /** role=dialog + aria-modal, and a real accessible name, not just visual chrome. */
    @Test
    void overlayExposesDialogSemantics() {
        openOverlayFromToolbar();

        String role = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-overlay').getAttribute('role')");
        String ariaModal = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-overlay').getAttribute('aria-modal')");
        String accessibleName = (String)
                page.evaluate("() => { const el = document.getElementById('peekaboot-trace-overlay').shadowRoot"
                        + ".querySelector('.pk-overlay'); const labelledBy = el.getAttribute('aria-labelledby');"
                        + " return labelledBy ? el.getRootNode().getElementById(labelledBy).textContent.trim()"
                        + " : el.getAttribute('aria-label'); }");

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
        page.waitForFunction("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-trace').textContent.trim() !== '-'");
        page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('.pk-toolbar__open').focus()");
        page.keyboard().press("Enter");
        page.waitForSelector("#peekaboot-trace-overlay");
        // container.focus() only happens once render() actually runs (after the trace
        // fetch and shared stylesheets both resolve) - wait for real content so the
        // assertion below cannot race a still-loading overlay.
        page.waitForFunction(
                "() => !!document.getElementById('peekaboot-trace-overlay').shadowRoot" + ".querySelector('.pk-tab')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));

        boolean focusIsInsideOverlay =
                (Boolean) page.evaluate("() => { const host = document.getElementById('peekaboot-trace-overlay');"
                        + " return host.shadowRoot.activeElement !== null; }");
        assertThat(focusIsInsideOverlay).isTrue();

        page.keyboard().press("Escape");
        page.waitForCondition(() -> page.querySelector("#peekaboot-trace-overlay") == null);

        boolean focusIsBackOnTheInvoker = (Boolean)
                page.evaluate("() => document.getElementById('peekaboot-toolbar-host').shadowRoot.activeElement"
                        + "?.classList.contains('pk-toolbar__open') ?? false");
        assertThat(focusIsBackOnTheInvoker).isTrue();
    }

    /**
     * Forces the overlay's error path (a real network failure, not a mocked response) and
     * proves its Close button actually works. Pre-existing defect: the button called
     * this.closest('#peekaboot-trace-overlay').remove() from inside the shadow root, where
     * closest() cannot cross the shadow boundary, so the button threw and did nothing.
     */
    @Test
    void closeButtonDismissesTheOverlayOnTheErrorPath() {
        page.route("**/api/traces/*/insights", route -> route.abort());
        openOverlayFromToolbar();

        page.waitForFunction("() => !!document.getElementById('peekaboot-trace-overlay')"
                + ".shadowRoot.querySelector('.pk-overlay__error')");
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-overlay__error button').click()");

        page.waitForCondition(() -> page.querySelector("#peekaboot-trace-overlay") == null);
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
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[data-tab=\"spans\"]').focus()");

        page.keyboard().press("ArrowRight");

        String focused =
                (String) page.evaluate("() => { const host = document.getElementById('peekaboot-trace-overlay');"
                        + " return host.shadowRoot.activeElement?.dataset.tab; }");
        String selected = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[aria-selected=\"true\"]').dataset.tab");

        assertThat(focused).isEqualTo("queries");
        assertThat(selected).isEqualTo("queries");

        String content = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('#pk-tab-content').innerHTML");
        assertThat(content).isNotEmpty();
    }

    /** Only the selected main tab stays in the tab order - roving tabindex. */
    @Test
    void onlyTheSelectedOverlayTabIsInTheTabOrder() {
        openOverlayFromToolbar();

        Object selectedTabIndex = page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[aria-selected=\"true\"]').tabIndex");
        Object otherTabIndex = page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[aria-selected=\"false\"]').tabIndex");

        assertThat(selectedTabIndex).isEqualTo(0);
        assertThat(otherTabIndex).isEqualTo(-1);
    }

    /**
     * Inspects the real accessibility tree for the overlay's strip too - confirms it
     * exposes as an actual tablist with the right tabs and selected state, same as
     * the dashboard's equivalent check. This alone does NOT prove TABS.count is
     * load-bearing: the pre-change hand-rolled markup rendered an identical
     * "Queries 1" from its own separately-computed queryCount, so this snapshot
     * would very likely have passed before this change too. The actual proof is
     * that render() no longer has that duplicate template text at all - a
     * code-level fact (see the task report's TDD section for the discriminating
     * evidence). Also pins the spans tab's own count badge, computed from the same
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
        page.waitForFunction("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-trace').textContent.trim() !== '-'");
        // Polls (inside one evaluate(), not a separate waitForFunction + a later re-fetch)
        // until the query span lands, then returns the span count from that very same
        // response - both to dodge the ingestion race documented above, and to read the
        // count from the exact same JSON payload the "queries present" check just parsed,
        // rather than a second independent fetch that could race the trace being evicted
        // from the store (a bounded ring buffer under constant pressure from this app's
        // own background scheduler). Reads the id from the copy button's data-pk-copy
        // attribute - #pk-trace's own textContent is "traceId<hex>⧉" (label + icon baked
        // in by copyableIdHtml), not the bare id a URL path segment needs.
        int spanCount = ((Number) page.evaluate("async () => {"
                        + "for (let i = 0; i < 150; i++) {"
                        + "  const copyEl = document.getElementById('peekaboot-toolbar-host')"
                        + ".shadowRoot.querySelector('#pk-trace .pk-copy');"
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
        page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('.pk-toolbar').click()");
        page.waitForSelector("#peekaboot-trace-overlay");
        page.waitForFunction(
                "() => !document.getElementById('peekaboot-trace-overlay').shadowRoot"
                        + ".querySelector('.pk-overlay__loading')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[data-tab=\"queries\"]').click()");

        Locator tablist = page.locator("#peekaboot-trace-overlay .pk-overlay__container > .pk-tabs");
        String snapshot = tablist.ariaSnapshot();

        assertThat(snapshot).contains("tablist");
        assertThat(snapshot).contains("\"Spans " + spanCount + "\"");
        // The " 1" is the queries count TABS.count(trace) computes for this real trace -
        // pins that count is actually rendered into the tab, not just present in TABS.
        assertThat(snapshot).contains("\"Queries 1\" [selected]");
    }

    /**
     * The Request tab's own overview/request-headers/response-headers sub-tabs reuse
     * the same shared tabStrip() helper as the main strips - migrated off hand-rolled
     * click wiring (and a bespoke data-subtab attribute) alongside them, since it was
     * the same missing-roving-tabindex defect.
     */
    @Test
    void requestSubTabsAreKeyboardNavigable() {
        openOverlayFromToolbar();
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[data-tab=\"request\"]').click()");
        page.waitForFunction("() => !!document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('#pk-tab-content .pk-tab[data-tab=\"overview\"]')");
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('#pk-tab-content .pk-tab[data-tab=\"overview\"]').focus()");

        page.keyboard().press("ArrowRight");

        String selected = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('#pk-tab-content .pk-tab[aria-selected=\"true\"]').dataset.tab");
        assertThat(selected).isEqualTo("request-headers");

        String content = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('#pk-request-subtab-content').innerHTML");
        assertThat(content).isNotEmpty();
    }

    @Test
    void everyOverlayTabRendersContent() {
        openOverlayFromToolbar();

        for (String tab : java.util.List.of("request", "spans", "queries", "logs")) {
            page.evaluate(
                    "id => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                            + ".querySelector(`.pk-tab[data-tab=\"${id}\"]`).click()",
                    tab);

            String content =
                    (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                            + ".querySelector('#pk-tab-content').innerHTML");
            assertThat(content).as("tab %s renders something", tab).isNotEmpty();
        }
    }

    /**
     * The span tree used to carry a copyable full-length span id on every row, which made
     * the tree too crowded (see logsTableRendersCopyableSpanIds and
     * clickingTheLogSpanIdCopiesItWithoutFiltering in CopyableIdTest for its new home).
     * A row still keeps its span name, duration, badges and the logs/SQL toggles - just
     * not a copy control.
     */
    @Test
    @DisplayName("the Spans tab's tree rows no longer carry a copyable span id")
    void spanTreeRowsDoNotRenderACopyableSpanId() {
        openOverlayFromToolbar();

        boolean anyRowHasACopyControl =
                (boolean) page.evaluate("() => !!document.getElementById('peekaboot-trace-overlay').shadowRoot"
                        + ".querySelector('#pk-gantt-rows .pk-copy')");

        assertThat(anyRowHasACopyControl).isFalse();
    }

    /**
     * The Spans tab's per-span "N logs" toggle used to open a bespoke fullscreen popup
     * that reused the Logs tab's own row renderer verbatim - fully redundant once the
     * Logs tab grew its own span filter (99345f81, which the popup - af1fa88a - predates).
     * It now hands off to that existing filter instead: switch the overlay to the Logs
     * tab, seed its span filter, and rely on the filter chip's own clear button for a
     * reversible "back to all logs".
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
    @DisplayName("a span's \"N logs\" toggle opens the Logs tab filtered to that span, and the filter is clearable")
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

        page.waitForFunction("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[aria-selected=\"true\"]')?.dataset.tab === 'logs'");
        assertThat(page.url())
                .as("the hand-off is a real location, not just a DOM change - the same hash shape a "
                        + "deep link into this filtered view would use")
                .contains("#traces/" + traceId + "/logs?span=" + spanId);
        String content = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('#pk-tab-content').innerHTML");
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

        page.waitForFunction("() => !document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-logs-filter-span')");
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

    @Test
    void queriesTabListsTheJdbcQueryFromThePersonsPage() {
        openOverlayFromToolbar();
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[data-tab=\"queries\"]').click()");

        String sql = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-query__sql')?.textContent ?? ''");
        assertThat(sql.toLowerCase(Locale.ROOT)).contains("select");
    }

    /**
     * Each span's duration cell also shows its share of the whole trace's duration, and
     * the gantt header's tick marks line up with the row tracks below them - both track
     * and header timeline carry the same 8px side margin, so the 0%/100% ticks sit right
     * above the start/end of the bars they describe rather than 8px further out.
     */
    @Test
    void spansTabShowsPercentOfTotalTraceTimeNextToEachDuration() {
        openOverlayFromToolbar();

        Object allDurationsMatchPattern =
                page.evaluate("() => Array.from(document.getElementById('peekaboot-trace-overlay').shadowRoot"
                        + ".querySelectorAll('.pk-gantt-duration'))"
                        + ".every(el => /^\\d+ms \u00B7 \\d{1,3}%$/.test(el.textContent.trim()))");
        assertThat((Boolean) allDurationsMatchPattern)
                .as("every duration cell reads '<ms>ms \u00B7 <pct>%'")
                .isTrue();

        BoundingBox headerBox = page.locator("#peekaboot-trace-overlay .pk-gantt-header-timeline")
                .boundingBox();
        BoundingBox trackBox = page.locator("#peekaboot-trace-overlay .pk-gantt-row")
                .first()
                .locator(".pk-gantt-track")
                .boundingBox();

        assertThat(headerBox.x)
                .as("header timeline's left edge lines up with the first row's track")
                .isCloseTo(trackBox.x, org.assertj.core.data.Offset.offset(1.0));
        assertThat(headerBox.x + headerBox.width)
                .as("header timeline's right edge lines up with the first row's track")
                .isCloseTo(trackBox.x + trackBox.width, org.assertj.core.data.Offset.offset(1.0));
    }

    /**
     * Regression test for a residual duplication: the SLOW label used to re-derive the
     * 100ms slow threshold with a bare literal (`duration > 100`) on the same line that
     * already computes `durationClass` from severity.js's durationSeverity() - now it just
     * reads durationClass. Imports queries.js directly (SharedModuleTest's pk-blank.html
     * pattern) rather than driving a real slow query through the app, and pins the exact
     * SLOW_MS boundary (100ms itself must NOT get the label; 101ms must) since an earlier
     * off-by-one at this same boundary was a real bug in this project (see severity.js's
     * own boundary test in SharedModuleTest).
     */
    @Test
    void queriesTabSlowLabelFollowsTheSharedSeverityThresholdAtTheBoundary() {
        page.navigate(baseUrl + "/peekaboot/ui/pk-blank.html");

        Object labels = page.evaluate("""
            async () => {
                const m = await import('/peekaboot/ui/trace-detail/tabs/queries.js');
                const container = document.createElement('div');
                m.render(container, {queries: [
                    {sql: 'SELECT 1', durationMs: 99,  dbSystem: 'h2', rowCount: 1},
                    {sql: 'SELECT 2', durationMs: 100, dbSystem: 'h2', rowCount: 1},
                    {sql: 'SELECT 3', durationMs: 101, dbSystem: 'h2', rowCount: 1},
                    {sql: 'SELECT 4', durationMs: 501, dbSystem: 'h2', rowCount: 1}
                ]});
                return Array.from(container.querySelectorAll('.pk-query__duration')).map(el => el.textContent);
            }
            """);

        @SuppressWarnings("unchecked")
        java.util.List<String> durationLabels = (java.util.List<String>) labels;
        assertThat(durationLabels).containsExactly("99ms", "100ms", "101ms SLOW", "501ms SLOW");
    }

    /**
     * Root-cause pin for the misaligned back button: .pk-overlay__back and .pk-overlay__close
     * used to be position:absolute against .pk-overlay__container, with the title carrying a
     * hand-rolled margin-left hack to fake reserving space for the button - two independent
     * layouts that only looked aligned by coincidence, and drifted the moment the title's UA
     * margin-top pushed it down without moving the absolutely-positioned button. Both buttons
     * now sit in the header's own flex flow next to a .pk-overlay__header-main wrapper, so
     * they cannot drift from the title's first line.
     */
    @Test
    void overlayHeaderKeepsBackAndCloseInTheFlowAlignedWithTheTitle() {
        openOverlayFromToolbar();

        assertThat((String) page.evaluate(
                        "() => getComputedStyle(document.getElementById('peekaboot-trace-overlay').shadowRoot"
                                + ".querySelector('.pk-overlay__back')).position"))
                .isEqualTo("static");
        assertThat((String) page.evaluate(
                        "() => getComputedStyle(document.getElementById('peekaboot-trace-overlay').shadowRoot"
                                + ".querySelector('.pk-overlay__close')).position"))
                .isEqualTo("static");

        BoundingBox backBox =
                page.locator("#peekaboot-trace-overlay .pk-overlay__back").boundingBox();
        BoundingBox closeBox =
                page.locator("#peekaboot-trace-overlay .pk-overlay__close").boundingBox();
        BoundingBox titleBox =
                page.locator("#peekaboot-trace-overlay .pk-overlay__title").boundingBox();

        assertThat(backBox.y)
                .as("back button top should be within the title's vertical span")
                .isLessThan(titleBox.y + titleBox.height);
        assertThat(backBox.y + backBox.height)
                .as("back button bottom should overlap the title's vertical span")
                .isGreaterThan(titleBox.y);

        assertThat(closeBox.y)
                .as("close button top should be within the title's vertical span")
                .isLessThan(titleBox.y + titleBox.height);
        assertThat(closeBox.y + closeBox.height)
                .as("close button bottom should overlap the title's vertical span")
                .isGreaterThan(titleBox.y);
    }

    /**
     * Regression guard for the fake "UNKNOWN" HTTP method rendered on non-HTTP traces (a
     * scheduled job here): trace-detail.js used to hardcode 'UNKNOWN' as the method fallback,
     * even though httpExchange/http.* tags are only ever populated for real HTTP requests.
     * The method now falls back to null, which the header renders as the trace's root-action
     * label instead (root-actions.js) - precedent for stubbing the insights endpoint with a
     * canned response is closeButtonDismissesTheOverlayOnTheErrorPath, above. Also covers the
     * "1 queries" pluralisation defect on the same header (formatCount() in format.js).
     */
    @Test
    void overlayHeaderShowsTheRootActionLabelForNonHttpTraces() {
        String cannedScheduledJobTrace = """
                {
                  "traceId": "scheduled-canned-trace",
                  "startTimeMs": 1000,
                  "durationMs": 42,
                  "status": "OK",
                  "rootActionType": "SCHEDULED_JOB",
                  "rootOperation": "task orderReconciler.reconcileOrders",
                  "rootSpan": {
                    "spanId": "span-1",
                    "name": "task orderReconciler.reconcileOrders",
                    "kind": "INTERNAL",
                    "startTimeMs": 1000,
                    "durationMs": 42,
                    "status": "OK",
                    "children": [],
                    "tags": {},
                    "events": [],
                    "issues": []
                  },
                  "summary": {"spans": {"count": 1}},
                  "inheritedAttributes": {},
                  "httpExchange": null,
                  "logs": [],
                  "queries": [{"sql": "SELECT 1", "durationMs": 5, "dbSystem": "h2", "rowCount": 1}],
                  "truncated": false
                }
                """;
        page.route(
                "**/api/traces/*/insights",
                route -> route.fulfill(new Route.FulfillOptions()
                        .setStatus(200)
                        .setContentType("application/json")
                        .setBody(cannedScheduledJobTrace)));
        openOverlayFromToolbar();

        String methodText = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay')"
                + ".shadowRoot.querySelector('.pk-overlay__title-method').textContent");
        assertThat(methodText).isEqualTo("Scheduled Job");

        String metaText = (String) page.evaluate("() => document.getElementById('peekaboot-trace-overlay')"
                + ".shadowRoot.querySelector('.pk-overlay__meta').textContent");
        assertThat(metaText).contains("1 query").doesNotContain("1 queries");
    }
}

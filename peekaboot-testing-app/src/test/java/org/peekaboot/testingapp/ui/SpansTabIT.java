package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.BoundingBox;
import io.micrometer.tracing.Span;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.testingapp.integration.TestSpans;
import tools.jackson.databind.JsonNode;

/**
 * The trace-detail overlay's Spans tab: the gantt, a span's panels and its async subtrees, on real
 * captured traces, on traces written straight to the store, and on spans.js rendered directly.
 */
class SpansTabIT extends TraceOverlayTestBase {

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
        traceStore.addLog(
                new LogCapturedEvent(traceId, "query", Instant.EPOCH.plusMillis(6), "INFO", "found 8 orders", null));
        return traceId;
    }

    /** The span rows the Spans tab's gantt shows: rendered, and not hidden by a collapsed parent. */
    private int visibleGanttRows() {
        return ((Number) overlay.evaluate("root => [...root.querySelectorAll('#pk-gantt-rows .pk-gantt-span')]"
                        + ".filter(entry => entry.style.display !== 'none').length"))
                .intValue();
    }

    /** Every span row the Spans tab's gantt has rendered, shown or hidden; a scoped tab renders only its subtree. */
    private int renderedGanttRows() {
        return ((Number) overlay.evaluate("root => root.querySelectorAll('#pk-gantt-rows .pk-gantt-row').length"))
                .intValue();
    }

    /** Whether `panelSelector` (one of a span's three `.pk-span-panel` ids) is currently the open one. */
    private boolean panelOpen(String panelSelector) {
        return (Boolean) overlay.evaluate(
                "(root, sel) => { const el = root.querySelector(sel); return el != null && getComputedStyle(el).display !== 'none'; }",
                panelSelector);
    }

    /**
     * A full-length span id on every row would crowd the tree, so a row carries none. The id
     * sits in the span's attributes panel instead, for the reader who opened the panel for
     * this span's particulars; CopyableIdIT covers the copy itself.
     */
    @Test
    void aSpanIdIsCopyableFromItsAttributesPanelNotItsRow() {
        openOverlayFromToolbar();

        assertThat((Boolean) overlay.evaluate("root => !!root.querySelector('#pk-gantt-rows .pk-gantt-row .pk-copy')"))
                .as("no row carries a copy control")
                .isFalse();
        overlay.click(".pk-gantt-all-details");
        assertThat(overlay.evaluate("root => [...root.querySelectorAll('#pk-gantt-rows .pk-gantt-span')]"
                        + ".every(entry => entry.querySelector('.pk-span-panel--attrs .pk-copy')?.dataset.pkCopy"
                        + " === entry.querySelector('.pk-gantt-row').dataset.spanId)"))
                .as("every attributes panel offers its own span's id")
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
                m.render(container, {durationMs: 10, startTime: '1970-01-01T00:00:00Z', rootSpan: {spanId: 'a', name: 'a', children: [{spanId: 'b', name: 'b'}]}});
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
     * A span's panels are built the first time each opens, not up front with every row, and slot
     * in by their toggle's place in the row, so their order holds whatever order they open in.
     * The all-details switch builds whatever is still missing.
     */
    @Test
    void aSpansPanelsAreBuiltWhenFirstOpenedInRowOrder() {
        Object facts = importModule("trace-detail/tabs/spans.js", """
            (() => {
                const container = document.createElement('div');
                m.render(container, {durationMs: 10, startTime: '1970-01-01T00:00:00Z',
                    rootSpan: {spanId: 'a', name: 'a', tags: {'db.system.name': 'h2'},
                        statement: {text: 'select 1', formatted: null, parameters: []}},
                    logs: [{spanId: 'a', level: 'INFO', message: 'from a', timestamp: '2026-01-01T00:00:00Z'}]});
                const entry = container.querySelector('.pk-gantt-span');
                const panels = () => [...entry.querySelectorAll('.pk-span-panel')].map(panel => panel.id).join(',');
                const before = panels();
                entry.querySelector('.pk-span-logs-toggle').click();
                const logsOpened = [panels(), entry.querySelector('#pk-span-logs-a .pk-log__message')?.textContent ?? null];
                entry.querySelector('.pk-gantt-name__toggle').click();
                const attrsOpened = [panels(), entry.querySelector('#pk-span-attrs-a .pk-span-tags__key')?.textContent ?? null];
                container.querySelector('.pk-gantt-all-details').click();
                return [before, ...logsOpened, ...attrsOpened, panels()];
            })()
            """);

        assertThat(facts)
                .isEqualTo(List.of(
                        "",
                        "pk-span-logs-a",
                        "from a",
                        "pk-span-attrs-a,pk-span-logs-a",
                        "db.system.name",
                        "pk-span-attrs-a,pk-span-query-a,pk-span-logs-a"));
    }

    /**
     * The Spans tab's per-span "N logs" toggle opens that span's own logs in a panel under
     * its row, in place - no tab switch, no URL change - rather than handing off to the Logs
     * tab's own span filter the way it used to (spanLogsToggleOpensTheLogsTabFilteredToThat
     * SpanAndTheFilterIsClearable, before this became an inline panel; the Logs tab's own
     * span-name button still does that hand-off, unchanged - see LogsTabIT.logRowSpanLinkJumpsToTheSpanTree).
     *
     * <p>Runs against a real captured trace with nothing stubbed. The index page's error
     * path writes its ERROR log inside the request handler's span while
     * PersonQueryService.findAll() writes an INFO line inside its own observed span, so
     * the trace's logs genuinely sit on two different spans - proof the panel lists exactly
     * the clicked span's own logs, not every log in the trace.
     */
    @Test
    void spanLogsToggleOpensThatSpansOwnLogsInPlace() {
        openOverlayForTheMultiSpanLogTrace();

        @SuppressWarnings("unchecked")
        List<String> spansOfferingLogs = (List<String>) page.evalOnSelectorAll(
                ".pk-span-logs-toggle", "els => els.map(el => el.closest('.pk-gantt-row').dataset.spanId)");
        assertThat(spansOfferingLogs)
                .as("the Spans tab must offer a logs toggle per logging span - the helper already "
                        + "waited for the backend to serve more than one, so a shortfall here is the "
                        + "tree failing to render them, not ingestion still catching up")
                .hasSizeGreaterThan(1);
        String spanId = spansOfferingLogs.getFirst();
        String panel = "[id='pk-span-logs-" + spanId + "']";
        String toggle = ".pk-gantt-row[data-span-id='" + spanId + "'] .pk-span-logs-toggle";

        assertThat(panelOpen(panel)).as("closed until asked for").isFalse();

        overlay.click(toggle);

        assertThat(panelOpen(panel)).isTrue();
        assertThat(overlay.evaluate("root => root.querySelector('.pk-tab[aria-selected=\"true\"]').dataset.tab"))
                .as("no tab switch - the panel opened in place")
                .isEqualTo("spans");
        assertThat(page.url()).as("no URL change either").doesNotContain("span=");
        @SuppressWarnings("unchecked")
        List<String> listedSpanIds = (List<String>) overlay.evaluate(
                "(root, sel) => [...root.querySelectorAll(sel + ' .pk-log')].map(el => el.dataset.spanId)", panel);
        assertThat(listedSpanIds).as("only that span's own logs are listed").containsOnly(spanId);

        overlay.click(toggle);

        assertThat(panelOpen(panel)).isFalse();
    }

    /**
     * A span's logs panel lists that span's logs the way the Logs tab does, stack trace
     * folded included. The span tree's own log copies carry no trace, so the panel reads the
     * flat list; the trace's logs sit on more than one span, so a panel that listed them all,
     * or listed the span tree's copies, fails here. The filter-to-span chip is left out: every
     * entry already belongs to the span.
     */
    @Test
    void anOpenSpanListsExactlyItsOwnLogsWithTheirFoldedTraces() {
        String traceId = openOverlayForTheMultiSpanLogTrace();
        JsonNode logs = awaitTrace(traceId, ROOT_SPAN_EXPORTED).path("logs");
        JsonNode errorLog = logs.valueStream()
                .filter(log -> !log.path("stackTrace").isNull())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no captured log carried a stack trace"));
        String spanId = errorLog.path("spanId").asString();
        List<String> ownMessages = logs.valueStream()
                .filter(log -> spanId.equals(log.path("spanId").asString()))
                .map(log -> log.path("message").asString())
                .toList();
        assertThat(logs.valueStream().map(log -> log.path("spanId").asString()))
                .as("the trace's logs sit on another span too, or listing them all would pass")
                .anyMatch(other -> !spanId.equals(other));
        String panel = "[id='pk-span-logs-" + spanId + "']";

        overlay.click(".pk-gantt-row[data-span-id='" + spanId + "'] .pk-span-logs-toggle");

        assertThat(panelOpen(panel)).isTrue();
        @SuppressWarnings("unchecked")
        List<String> listedMessages = (List<String>) overlay.evaluate(
                "(root, panel) => [...root.querySelectorAll(panel + ' .pk-log .pk-log__message')].map(el => el.textContent)",
                panel);
        assertThat(listedMessages).containsExactlyElementsOf(ownMessages);
        assertThat(overlay.evaluate("(root, panel) => root.querySelectorAll(panel + ' .pk-log__span').length", panel))
                .as("no filter-to-span chip in the span's own panel")
                .isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<String> renderedFrames = (List<String>) overlay.evaluate(
                "(root, panel) => [...root.querySelectorAll(panel + ' .pk-log__trace .pk-stack-trace__frame')].map(el => el.textContent)",
                panel);
        assertThat(renderedFrames)
                .containsExactlyElementsOf(
                        List.of(errorLog.path("stackTrace").asString().split("\n", -1)));

        String hiddenRuns = panel + " .pk-log__trace details.pk-stack-trace__hidden";
        assertThat(page.locator(hiddenRuns).count()).isGreaterThan(0);
        assertThat(page.locator(hiddenRuns + "[open]").count()).isEqualTo(0);

        page.click(panel + " .pk-log__reveal");

        assertThat(page.locator(hiddenRuns + "[open]").count())
                .isEqualTo(page.locator(hiddenRuns).count());
        assertThat(page.getAttribute(panel + " .pk-log__reveal", "aria-pressed"))
                .isEqualTo("true");
    }

    /** A span that wrote no log gets neither a logs panel nor a toggle to open one. */
    @Test
    void aSpanWithoutLogsHasNoLogsSection() {
        Object sections = importModule("trace-detail/tabs/spans.js", """
            (() => {
                const container = document.createElement('div');
                m.render(container, {durationMs: 10, startTime: '1970-01-01T00:00:00Z',
                    rootSpan: {spanId: 'a', name: 'a', children: [{spanId: 'b', name: 'b'}]},
                    logs: [{spanId: 'a', level: 'INFO', message: 'from a', timestamp: '2026-01-01T00:00:00Z'}]});
                container.querySelector('.pk-gantt-all-details').click();
                const row = id => container.querySelector(`.pk-gantt-row[data-span-id="${id}"]`);
                const panel = id => container.querySelector(`#pk-span-logs-${id}`);
                return ['a', 'b'].map(id => [
                    panel(id)?.querySelectorAll('.pk-log').length ?? 0,
                    row(id).querySelectorAll('.pk-span-logs-toggle').length].join(':'));
            })()
            """);

        assertThat(sections).isEqualTo(List.of("1:1", "0:0"));
    }

    /**
     * A row's logs toggle counts the span's entries in the trace's flat log list, the one its
     * panel renders. The span tree carries no per-span copy, so a toggle counting one would
     * never appear.
     */
    @Test
    void aSpansLogsToggleCountsItsEntriesInTheTracesLogList() {
        Object label = importModule("trace-detail/tabs/spans.js", """
            (() => {
                const container = document.createElement('div');
                const log = message => ({spanId: 'a', level: 'INFO', message, timestamp: '2026-01-01T00:00:00Z'});
                m.render(container, {durationMs: 10, startTime: '1970-01-01T00:00:00Z', rootSpan: {spanId: 'a', name: 'a'},
                    logs: [log('first'), log('second')]});
                return container.querySelector('.pk-span-logs-toggle')?.textContent ?? null;
            })()
            """);

        assertThat(label).isEqualTo("2 logs");
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
     * JUMP_FLASH_MS's 2s removal timer - QueriesTabIT.queryEntrySpanLinkJumpsBackToItsSpanRow
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
     * Every collapsed span above a row opens, not just the nearest: here the reader collapsed
     * both the root and the row's parent. A span the path does not pass through keeps its
     * own subtree collapsed.
     */
    @Test
    void revealingARowExpandsEveryCollapsedSpanAboveIt() {
        Object facts = importModule("trace-detail/tabs/spans.js", """
            (() => {
                const container = document.createElement('div');
                m.render(container, {durationMs: 10, startTime: '1970-01-01T00:00:00Z', rootSpan: {spanId: 'root', name: 'root', children: [
                    {spanId: 'mid', name: 'mid', children: [{spanId: 'leaf', name: 'leaf'}]},
                    {spanId: 'sibling', name: 'sibling', children: [{spanId: 'cousin', name: 'cousin'}]}]}});
                const entry = id => container.querySelector(`.pk-gantt-row[data-span-id="${id}"]`).closest('.pk-gantt-span');
                const toggle = id => entry(id).querySelector('.pk-gantt-toggle');
                toggle('mid').click();
                toggle('sibling').click();
                toggle('root').click();
                m.revealRow(container.querySelector('.pk-gantt-row[data-span-id="leaf"]'));
                return ['mid', 'leaf', 'sibling', 'cousin'].map(id => entry(id).style.display === 'none' ? 'hidden' : 'shown')
                    .concat(['root', 'mid', 'sibling'].map(id => toggle(id).getAttribute('aria-expanded')));
            })()
            """);

        assertThat(facts).isEqualTo(List.of("shown", "shown", "shown", "hidden", "true", "true", "false"));
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

        Response navigation = openTraceDeepLink(traceId);
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
        overlay.click(".pk-gantt-span[data-depth='1'] .pk-gantt-name__toggle");
        assertThat(
                        overlay.evaluate(
                                "root => getComputedStyle(root.querySelector('.pk-gantt-span[data-depth=\"1\"] .pk-span-panel--attrs')).marginLeft"))
                .as("its attributes panel sits under its name")
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

        openTraceDeepLink(traceId);
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

        openTraceDeepLink(traceId);
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

        openTraceDeepLink(traceId);
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
     * The Spans tab renders the facts the backend serves rather than re-deriving them from
     * tags and names: the query row's row count is {@code span.rowCount}, which the backend
     * pairs onto the query span itself (left off the button, and the query panel gets no row
     * count chip at all, for a count that did not parse, even where the result set's own tag
     * is right there), an error bar follows {@code span.status} alone, and every tag on the
     * span is shown - the backend already keeps the statement tags out, so the tab does not
     * sniff for them. Also the one place the locale passed to {@code m.render} is pinned on
     * both the button ("N result rows") and the query panel's own chip ("N rows"): rendered
     * in de-DE, 12345 reads "12.345" in either.
     */
    @Test
    void spansTabTrustsTheBackendsSpanFacts() {
        Object facts = importModule("trace-detail/tabs/spans.js", """
            (() => {
                const rendered = (span, locale) => {
                    const container = document.createElement('div');
                    m.render(container, {durationMs: 10, startTime: '1970-01-01T00:00:00Z', rootSpan: span}, {locale});
                    container.querySelector('.pk-gantt-all-details').click();
                    return container;
                };
                const queryButton = (span, locale) => rendered(span, locale).querySelector('.pk-span-query-toggle')?.textContent ?? null;
                const panelRowCount = (span, locale) => rendered(span, locale).querySelector('.pk-span-panel--query .pk-span-row-count')?.textContent ?? null;
                const errorBar = span => rendered(span).querySelector('.pk-gantt-bar').className.includes('--error');
                const tagKeys = span => Array.from(rendered(span).querySelectorAll('.pk-span-tags__key')).map(el => el.textContent);
                const statement = {text: 'select * from orders', formatted: null, parameters: []};
                return [
                    queryButton({spanId: 'a', name: 'SELECT orders', rowCount: 1234, statement}),
                    panelRowCount({spanId: 'a', name: 'SELECT orders', rowCount: 1234, statement}),
                    queryButton({spanId: 'g', name: 'SELECT one', rowCount: 1, statement}),
                    queryButton({spanId: 'h', name: 'SELECT none', statement}),
                    !!rendered({spanId: 'b', name: 'result-set', rowCount: null, tags: {'jdbc.row-count': '3'}}).querySelector('.pk-span-query-toggle'),
                    queryButton({spanId: 'f', name: 'SELECT big', rowCount: 12345, statement}, 'de-DE'),
                    panelRowCount({spanId: 'f', name: 'SELECT big', rowCount: 12345, statement}, 'de-DE'),
                    errorBar({spanId: 'c', name: 'x', status: 'ERROR'}),
                    errorBar({spanId: 'd', name: 'x', status: 'OK', errorMessage: 'ignored'}),
                    tagKeys({spanId: 'e', name: 'x', tags: {'db.system': 'h2', 'db.statement': 'SELECT 1'}}).join(',')
                ];
            })()
            """);

        @SuppressWarnings("unchecked")
        List<Object> spanFacts = (List<Object>) facts;
        assertThat(spanFacts)
                .containsExactly(
                        "1 query, 1,234 result rows",
                        "1,234 rows",
                        "1 query, 1 result row",
                        "1 query",
                        false,
                        "1 query, 12.345 result rows",
                        "12.345 rows",
                        true,
                        false,
                        "db.statement,db.system");
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
                m.render(container, {durationMs: 10, startTime: '1970-01-01T00:00:00Z', rootSpan: {spanId: 'a', name: 'SELECT person',
                    tags: {'jdbc.datasource.name': 'sample_app_db', 'db.system.name': 'postgresql', 'db.operation.name': 'SELECT'}}});
                container.querySelector('.pk-gantt-all-details').click();
                return Array.from(container.querySelectorAll('.pk-span-tags__key')).map(el => el.textContent);
            })()
            """);

        @SuppressWarnings("unchecked")
        List<String> tagKeys = (List<String>) keys;
        assertThat(tagKeys).containsExactly("db.operation.name", "db.system.name", "jdbc.datasource.name");
    }

    /**
     * Key order is code-point order, the one the Request tab sorts headers by: the browser's
     * collation would put "a.token" before "B.cache", and two readers must not see the same
     * trace's tags in different orders.
     */
    @Test
    void spanTagsAreInCodePointOrderNotTheBrowsersCollation() {
        Object keys = importModule("trace-detail/tabs/spans.js", """
            (() => {
                const container = document.createElement('div');
                m.render(container, {durationMs: 10, startTime: '1970-01-01T00:00:00Z', rootSpan: {spanId: 'a', name: 'a',
                    tags: {'a.token': '1', 'B.cache': '2', '_internal': '3'}}});
                container.querySelector('.pk-gantt-all-details').click();
                return Array.from(container.querySelectorAll('.pk-span-tags__key')).map(el => el.textContent);
            })()
            """);

        @SuppressWarnings("unchecked")
        List<String> tagKeys = (List<String>) keys;
        assertThat(tagKeys).containsExactly("B.cache", "_internal", "a.token");
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

    /**
     * A row's toggles keep one name and carry their state in aria-expanded, so a screen reader
     * announces the state once and never against the name ("Collapse child spans, collapsed").
     */
    @Test
    void spanRowTogglesKeepOneNameAndCarryTheirStateInAriaExpanded() {
        Object facts = importModule("trace-detail/tabs/spans.js", """
            (() => {
                const container = document.createElement('div');
                m.render(container, {durationMs: 10, startTime: '1970-01-01T00:00:00Z', rootSpan: {spanId: 'a', name: 'a',
                    statement: {text: 'select 1', formatted: null, parameters: []},
                    children: [{spanId: 'b', name: 'b'}]}});
                const subtree = container.querySelector('.pk-gantt-toggle');
                const query = container.querySelector('.pk-span-query-toggle');
                const state = control => `${control.getAttribute('aria-label')}|${control.getAttribute('aria-expanded')}`;
                const before = [state(subtree), state(query)];
                subtree.click();
                query.click();
                return [...before, state(subtree), state(query)];
            })()
            """);

        assertThat(facts)
                .isEqualTo(List.of(
                        "Child spans|true",
                        "1 query for this span|false",
                        "Child spans|false",
                        "1 query for this span|true"));
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
                m.render(container, {durationMs: 10, startTime: '1970-01-01T00:00:00Z', rootSpan: {spanId: 'root', name: 'root', children: [
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
                m.render(container, {durationMs: 10, startTime: '1970-01-01T00:00:00Z', rootSpan: {spanId: 'a', name: 'a',
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
                m.render(container, {durationMs: 10, startTime: '1970-01-01T00:00:00Z', rootSpan: {spanId: 'a', name: 'http get /orders', kind: 'SERVER', children: [
                    {spanId: 'b', name: 'SELECT person', kind: 'CLIENT'},
                    {spanId: 'c', name: 'spring.handler', kind: null},
                    {spanId: 'd', name: 'odd', kind: 'SOMETHING_NEW'}]}});
                container.querySelector('.pk-gantt-all-details').click();
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
                m.render(container, {durationMs: 10, startTime: '1970-01-01T00:00:00Z', rootSpan: {spanId: 'a', name: 'root', children: [
                    {spanId: 'b', name: 'http get', status: 'ERROR',
                        errorClass: 'org.springframework.web.client.ResourceAccessException', errorMessage: 'Connection refused'},
                    {spanId: 'c', name: 'ok', status: 'OK'}]}});
                container.querySelector('.pk-gantt-all-details').click();
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
     * A span's row is one line; its tags wait in its attributes panel under the row until the
     * reader opens it from the span's name - a query span carries a dozen tags, more than a
     * row can hold alongside every sibling row's own name and bar, so the tree stays the thing
     * on screen by default. The statement is not there: it moved to a query panel of its own
     * (aSpanQueryToggleOpensTheQueryPanelWithItsStatementAndRowCount below), so a span's name
     * click must not reveal it.
     */
    @Test
    void aSpanNameOpensTheAttributesPanelUnderItsRow() {
        openPersonsPage();
        awaitTrace(toolbar.traceId(), "trace => (trace.queries || []).length > 0");
        toolbar.openOverlay();
        overlay.waitFor(".pk-span-query-toggle");
        String spanId = (String) overlay.evaluate(
                "root => root.querySelector('.pk-span-query-toggle').closest('.pk-gantt-row').dataset.spanId");
        String nameToggle = ".pk-gantt-row[data-span-id='" + spanId + "'] .pk-gantt-name__toggle";

        assertThat(overlay.evaluate("(root, sel) => root.querySelector(sel).getAttribute('aria-expanded')", nameToggle))
                .isEqualTo("false");
        assertThat(panelOpen("[id='pk-span-attrs-" + spanId + "']"))
                .as("closed until asked for")
                .isFalse();

        overlay.click(nameToggle);

        assertThat(panelOpen("[id='pk-span-attrs-" + spanId + "']")).isTrue();
        assertThat(overlay.evaluate("(root, sel) => root.querySelector(sel).getAttribute('aria-expanded')", nameToggle))
                .isEqualTo("true");
        String panelId = (String)
                overlay.evaluate("(root, sel) => root.querySelector(sel).getAttribute('aria-controls')", nameToggle);
        assertThat(overlay.evaluate(
                        "(root, id) => root.getElementById(id).closest('.pk-gantt-span').querySelector('.pk-gantt-row').dataset.spanId",
                        panelId))
                .as("the name names the panel it opens")
                .isEqualTo(spanId);
        assertThat(((Number) overlay.evaluate(
                                "(root, id) => root.getElementById(id).querySelectorAll('.pk-span-tags__key').length",
                                panelId))
                        .intValue())
                .as("the query span's tags are in its attributes panel")
                .isPositive();
        assertThat((Boolean) overlay.evaluate(
                        "(root, id) => !!root.getElementById(id).querySelector('.pk-code-block')", panelId))
                .as("the statement lives in the query panel, not here")
                .isFalse();

        overlay.click(nameToggle);

        assertThat(panelOpen("[id='pk-span-attrs-" + spanId + "']")).isFalse();
    }

    /**
     * The query panel: opened only by its own "1 query[, N result rows]" toggle, never by the
     * span's name - the counterpart to aSpanNameOpensTheAttributesPanelUnderItsRow above.
     * Carries the statement, and the row count moved here from the row when the backend paired
     * one (spansTabTrustsTheBackendsSpanFacts covers both the with- and without-row-count
     * shapes at the unit level; this is the real-backend, end-to-end open/close proof).
     */
    @Test
    void aSpanQueryToggleOpensTheQueryPanelWithItsStatementAndRowCount() {
        openPersonsPage();
        awaitTrace(toolbar.traceId(), "trace => (trace.queries || []).length > 0");
        toolbar.openOverlay();
        overlay.waitFor(".pk-span-query-toggle");
        String spanId = (String) overlay.evaluate(
                "root => root.querySelector('.pk-span-query-toggle').closest('.pk-gantt-row').dataset.spanId");
        String queryToggle = ".pk-gantt-row[data-span-id='" + spanId + "'] .pk-span-query-toggle";
        String panel = "[id='pk-span-query-" + spanId + "']";

        assertThat(panelOpen(panel)).as("closed until asked for").isFalse();

        overlay.click(queryToggle);

        assertThat(panelOpen(panel)).isTrue();
        assertThat(overlay.text(queryToggle)).matches("1 query(, [0-9.,]+ result rows?)?");
        assertThat(overlay.text(panel + " .pk-code-block").toLowerCase(Locale.ROOT))
                .contains("select");

        overlay.click(queryToggle);

        assertThat(panelOpen(panel)).isFalse();
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

        openTraceDeepLink(traceId);
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
                m.render(container, {durationMs: 1000, startTime: '1970-01-01T00:00:00Z', rootSpan: {spanId: 'root', name: 'root', children: [
                    {spanId: 'a', name: 'async task', asyncEntry: true, startTime: '1970-01-01T00:00:00.100Z', durationMs: 50, children: [
                        {spanId: 'g', name: 'grandchild', startTime: '1970-01-01T00:00:00.120Z', durationMs: 200}]},
                    {spanId: 's', name: 'sibling', startTime: '1970-01-01T00:00:00.600Z', durationMs: 50}]}});
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
     * The backend serves span starts as Java's Instant writes them, to the nanosecond. Every
     * engine must drop what lies past the millisecond, as the epoch longs did: WebKit's own
     * Date.parse rounds 0.250999999s up, which put the bar at 25.1%, not 25%.
     */
    @Test
    void readsNanosecondStartsToTheMillisecond() {
        Object facts = importModule("trace-detail/tabs/spans.js", """
            (() => {
                const container = document.createElement('div');
                m.render(container, {durationMs: 1000, startTime: '2026-10-23T10:00:00.000123456Z', rootSpan: {
                    spanId: 'root', name: 'root', startTime: '2026-10-23T10:00:00.000123456Z', durationMs: 1000, children: [
                        {spanId: 'a', name: 'a', startTime: '2026-10-23T10:00:00.250999999Z', durationMs: 500}]}});
                const bar = id => container.querySelector(`.pk-gantt-row[data-span-id="${id}"]`)
                    .closest('.pk-gantt-span').querySelector('.pk-gantt-bar');
                return [bar('root').style.left, bar('a').style.left, bar('a').style.width];
            })()
            """);

        assertThat(facts).isEqualTo(List.of("0%", "25%", "50%"));
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
                const trace = {durationMs: 1000, startTime: '1970-01-01T00:00:00Z', rootSpan: {spanId: 'root', name: 'root', children: [
                    {spanId: 'a', name: 'async task', asyncEntry: true, startTime: '1970-01-01T00:00:00.100Z', durationMs: 50, children: [
                        {spanId: 'g', name: 'grandchild', startTime: '1970-01-01T00:00:00.120Z', durationMs: 200}]},
                    {spanId: 's', name: 'sibling', startTime: '1970-01-01T00:00:00.600Z', durationMs: 50}]}};
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
        assertThat(renderedGanttRows())
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

        assertThat(renderedGanttRows())
                .as("a tab round-trip clears the tab's own params, widening back to the full tree")
                .isGreaterThan(1);
        assertThat(page.url()).as("the param is gone from the URL too").doesNotContain("root=");
    }
}

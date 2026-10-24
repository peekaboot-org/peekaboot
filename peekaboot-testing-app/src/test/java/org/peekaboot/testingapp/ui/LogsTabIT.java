package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The trace-detail overlay's Logs tab: the span filter, the level filter, the link back to the span
 * tree and folded stack traces, the partner feed's two logged throwables among them.
 */
class LogsTabIT extends TraceOverlayTestBase {

    /** The messages PartnerFeedImporter logs its two throwables under. */
    private static final String REJECTED_ROW_LOG = "skipped partner feed row PK-FEED-1;two";

    private static final String BROKEN_FEED_LOG = "partner feed import broke off";

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

    /** Imports the partner feed and returns its trace's id once the root span is stored; its logs arrive with the request. */
    private String importPartnerFeed() {
        String traceId = traceIdOf(page.navigate(baseUrl + "/orders/import"));
        awaitTrace(traceId, ROOT_SPAN_EXPORTED);
        return traceId;
    }

    private static Map<String, JsonNode> thrownLogsByMessage(JsonNode trace) {
        Map<String, JsonNode> thrown = new LinkedHashMap<>();
        trace.path("logs")
                .valueStream()
                .filter(entry -> !entry.path("stackTrace").isNull())
                .forEach(entry -> thrown.put(entry.path("message").asString(), entry));
        return thrown;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    /** The line indices a list of {@code {start, endExclusive}} ranges covers. */
    private static List<Integer> indicesIn(JsonNode ranges) {
        List<Integer> indices = new ArrayList<>();
        ranges.forEach(range -> {
            for (int i = range.path("start").asInt();
                    i < range.path("endExclusive").asInt();
                    i++) {
                indices.add(i);
            }
        });
        return indices;
    }

    private static List<Integer> lengthsOf(JsonNode ranges) {
        return ranges.valueStream()
                .map(range ->
                        range.path("endExclusive").asInt() - range.path("start").asInt())
                .toList();
    }

    /** What the Logs tab rendered for the row logged with {@code message}, read off that row alone. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> renderedLogRow(String message) {
        return (Map<String, Object>) overlay.evaluate("""
                (root, message) => {
                    const row = [...root.querySelectorAll('.pk-log')]
                        .find(entry => entry.querySelector('.pk-log__message').textContent === message);
                    const frames = [...row.querySelectorAll('.pk-log__trace .pk-stack-trace__frame')];
                    return {
                        lines: frames.map(frame => frame.textContent),
                        applicationFrames: frames.flatMap((frame, i) =>
                            frame.classList.contains('pk-stack-trace__frame--app') ? [i] : []),
                        hiddenRuns: [...row.querySelectorAll('details.pk-stack-trace__hidden')]
                            .map(run => run.querySelectorAll('.pk-stack-trace__frame').length),
                        openRuns: row.querySelectorAll('details.pk-stack-trace__hidden[open]').length
                    };
                }
                """, message);
    }

    private int visibleLogRows() {
        return ((Number) overlay.evaluate("root => [...root.querySelectorAll('.pk-log')]"
                        + ".filter(row => !row.classList.contains('pk-log--hidden')).length"))
                .intValue();
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

    /**
     * Cross-link from the Logs tab: beside the existing filter-to-span button, each log
     * row links to its span in the Spans tab's tree the same way the Queries tab does.
     */
    @Test
    void logRowSpanLinkJumpsToTheSpanTree() {
        openPageThatLogsAnError();
        toolbar.openOverlay();
        overlay.openLogsTab();
        overlay.waitFor(".pk-log .pk-span-tree-link");
        String spanId =
                (String) overlay.evaluate("root => root.querySelector('.pk-log .pk-span-tree-link').dataset.spanId");

        overlay.click(".pk-log .pk-span-tree-link");

        overlay.waitUntil("root => root.querySelector('.pk-tab[aria-selected=\"true\"]')?.dataset.tab === 'spans'");
        assertThat(awaitJumpLanding()).containsEntry("flashed", spanId).containsEntry("focused", spanId);
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
     * every {@code .pk-stack-trace__frame} rendered inside {@code .pk-log__trace}, read off in
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

        assertThat(page.locator(".pk-log__trace details.pk-stack-trace__hidden").count())
                .isGreaterThan(0);
        assertThat(page.locator(".pk-log__trace .pk-stack-trace__frame--app").count())
                .isGreaterThan(0);

        String stackTrace = capturedStackTrace(traceId);
        @SuppressWarnings("unchecked")
        List<String> renderedFrames = (List<String>)
                overlay.evaluate(
                        "root => [...root.querySelectorAll('.pk-log__trace .pk-stack-trace__frame')].map(el => el.textContent)");
        assertThat(renderedFrames).containsExactlyElementsOf(List.of(stackTrace.split("\n", -1)));
    }

    /** The overlay's trace takes the same shared block as the error page, through its shadow root's components.css. */
    @Test
    void aLoggedTracesApplicationFramesStandOutInBold() {
        openPageThatLogsAnError();
        toolbar.openOverlay();
        overlay.openLogsTab();
        overlay.waitFor(".pk-log__trace");

        assertThat(overlay.evaluate("root => { const frame = root.querySelector('.pk-stack-trace__frame--app');"
                        + " return frame && getComputedStyle(frame).fontWeight; }"))
                .isEqualTo("600");
    }

    /**
     * Each row whose trace actually hid something gets exactly one reveal control - the
     * scoping rule {@code revealControl(pre)} implements by closing over that row's own
     * {@code <pre>} rather than querying the document. Clicking the control then opens
     * every hidden run inside that trace, and - since {@code open} is recomputed from
     * {@code aria-pressed} on every click - a second click closes every run again. This
     * fixture produces one row with a throwable, so it pins exactly one control per qualifying
     * row; that a click leaves a different row's trace alone is
     * {@link #everyThrowableInATraceTravelsOnceAndFoldsByItsOwnRanges}'s to show.
     */
    @Test
    void theLogTraceRevealControlOpensEveryHiddenRun() {
        openPageThatLogsAnError();
        toolbar.openOverlay();
        overlay.openLogsTab();
        overlay.waitFor(".pk-log__reveal");

        int rowsWithHiddenRuns = (int) (Integer) overlay.evaluate("root => [...root.querySelectorAll('.pk-log')]"
                + ".filter(row => row.querySelector('details.pk-stack-trace__hidden')).length");
        assertThat(page.locator(".pk-log__reveal").count()).isEqualTo(rowsWithHiddenRuns);

        page.click(".pk-log__reveal");

        assertThat(page.locator(".pk-log__trace details.pk-stack-trace__hidden[open]")
                        .count())
                .isEqualTo(page.locator(".pk-log__trace details.pk-stack-trace__hidden")
                        .count());
        assertThat(page.getAttribute(".pk-log__reveal", "aria-pressed")).isEqualTo("true");
        assertThat(page.textContent(".pk-log__reveal")).isEqualTo("Show full stack trace");

        page.click(".pk-log__reveal");

        assertThat(page.locator(".pk-log__trace details.pk-stack-trace__hidden[open]")
                        .count())
                .isEqualTo(0);
        assertThat(page.getAttribute(".pk-log__reveal", "aria-pressed")).isEqualTo("false");
        assertThat(page.textContent(".pk-log__reveal")).isEqualTo("Show full stack trace");
    }

    /**
     * One request whose trace carries two logged throwables, one with a cause chain and one with
     * a suppressed exception that has a cause of its own. Each trace crosses the wire once - the
     * span tree's copies of a log carry none - and each row renders its own trace by its own
     * ranges: the server indexes every log's lines separately, so a row read against another
     * row's ranges, or a suppressed frame's two leading tabs missed, shows here as a wrong fold
     * or an application frame left unmarked. The reveal control of one row leaves the other alone.
     */
    @Test
    void everyThrowableInATraceTravelsOnceAndFoldsByItsOwnRanges() {
        String traceId = importPartnerFeed();
        String payload = page.request()
                .get(baseUrl + "/peekaboot/api/traces/" + traceId + "/insights")
                .text();
        Map<String, JsonNode> thrownLogs = thrownLogsByMessage(readJson(payload));
        assertThat(thrownLogs).containsOnlyKeys(REJECTED_ROW_LOG, BROKEN_FEED_LOG);
        assertThat(thrownLogs.get(REJECTED_ROW_LOG).path("stackTrace").asString())
                .contains("Caused by: java.lang.NumberFormatException");
        assertThat(thrownLogs.get(BROKEN_FEED_LOG).path("stackTrace").asString())
                .contains("\tSuppressed: java.io.UncheckedIOException")
                .contains("Caused by: java.io.IOException: partner feed connection reset");
        thrownLogs.forEach((message, entry) -> assertThat(
                        occurrences(payload, entry.path("stackTrace").toString()))
                .as("the payload carries the trace logged with '%s' once", message)
                .isOne());

        openOverlayFromTheDashboard(traceId);
        overlay.openLogsTab();

        thrownLogs.forEach((message, entry) -> {
            List<String> lines = List.of(entry.path("stackTrace").asString().split("\n", -1));
            List<Integer> applicationLines = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).stripLeading().startsWith("at org.peekaboot.testingapp.")) {
                    applicationLines.add(i);
                }
            }
            Map<String, Object> rendered = renderedLogRow(message);
            assertThat(rendered.get("lines"))
                    .as("'%s' renders its own trace", message)
                    .isEqualTo(lines);
            assertThat(rendered.get("hiddenRuns"))
                    .as("'%s' folds by its own ranges", message)
                    .isEqualTo(lengthsOf(entry.path("hiddenFrames")));
            assertThat(rendered.get("applicationFrames"))
                    .as("'%s' marks the application's own frames, suppressed ones included", message)
                    .isEqualTo(indicesIn(entry.path("applicationFrames")))
                    .isEqualTo(applicationLines);
        });

        page.locator(".pk-log")
                .filter(new Locator.FilterOptions().setHasText(REJECTED_ROW_LOG))
                .locator(".pk-log__reveal")
                .click();
        assertThat(renderedLogRow(REJECTED_ROW_LOG).get("openRuns"))
                .isEqualTo(lengthsOf(thrownLogs.get(REJECTED_ROW_LOG).path("hiddenFrames"))
                        .size());
        assertThat(renderedLogRow(BROKEN_FEED_LOG).get("openRuns"))
                .as("revealing one row's trace leaves the other folded")
                .isEqualTo(0);
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
}

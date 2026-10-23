package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.tags.AsyncTaskMarker;
import org.peekaboot.testingapp.integration.TestSpans;

/** The trace-detail overlay's Queries tab, and the links from its entries back to the span tree. */
class QueriesTabIT extends TraceOverlayTestBase {

    /**
     * Stores a request whose {@code @Async} task ran a query two levels under its entry span,
     * and returns the trace id. The sample app's async work runs no query, so the shape is
     * written straight to the store.
     */
    private String storeAsyncQueryTrace() {
        String traceId = "overlay-async-query-" + System.nanoTime();
        traceStore.addSpan(TestSpans.span(traceId, "root")
                .named("http get /orders/enrich")
                .kind(Span.Kind.SERVER)
                .at(0, 20)
                .tag("http.method", "GET")
                .tag("url.path", "/orders/enrich")
                .build());
        traceStore.addSpan(TestSpans.span(traceId, "handler")
                .parent("root")
                .named("spring.handler")
                .at(1, 18)
                .build());
        traceStore.addSpan(TestSpans.span(traceId, "async-task")
                .parent("handler")
                .named(AsyncTaskMarker.CONTEXTUAL_NAME)
                .at(5, 200)
                .tag(AsyncTaskMarker.TAG_KEY, AsyncTaskMarker.TAG_VALUE)
                .build());
        traceStore.addSpan(TestSpans.span(traceId, "enrich")
                .parent("async-task")
                .named("enrich order")
                .at(6, 190)
                .build());
        traceStore.addSpan(TestSpans.span(traceId, "async-query")
                .parent("enrich")
                .named("SELECT customer_order")
                .kind(Span.Kind.CLIENT)
                .at(10, 5)
                .tag("db.system.name", "h2")
                .tag("db.query.text", "select * from customer_order")
                .build());
        return traceId;
    }

    /**
     * Each Queries-tab entry links back to its span in the Spans tab's tree - the row is
     * scrolled to, focused and temporarily highlighted. The reverse link (a query span's
     * panel jumping to its Queries-tab entry) no longer exists: the statement sits in the
     * span's own query panel now (see spans.js's task brief), so this is a one-way cross-link.
     * Also the one test that proves a real jump applies {@code pk-jump-flash} at all -
     * SpansTabIT.jumpFlashOutranksAHoveredRow adds the class by hand, so it never exercises
     * jumpToElement itself.
     */
    @Test
    void queryEntrySpanLinkJumpsBackToItsSpanRow() {
        openOverlayFromToolbar();
        overlay.openTab("queries");
        overlay.waitFor(".pk-query-item .pk-span-tree-link");
        String spanId = (String)
                overlay.evaluate("root => root.querySelector('.pk-query-item .pk-span-tree-link').dataset.spanId");

        overlay.click(".pk-query-item .pk-span-tree-link");

        overlay.waitUntil("root => root.querySelector('.pk-tab[aria-selected=\"true\"]')?.dataset.tab === 'spans'");
        assertThat(awaitJumpLanding()).containsEntry("flashed", spanId).containsEntry("focused", spanId);
    }

    /** The Queries and Logs tabs' links back to the span tree are one control: same class, same glyph, a name each. */
    @Test
    void queriesAndLogsLinkToTheSpanTreeWithTheSameControl() {
        Object queryLink = importModule("trace-detail/tabs/queries.js", """
            (() => {
                const container = document.createElement('div');
                m.render(container, {rootSpan: {spanId: 's1', statement: {text: 'SELECT 1', formatted: null, parameters: []}},
                    queries: [{durationMs: 1, dbSystem: 'h2', spanId: 's1'}]}, {goToSpan: () => {}});
                const link = container.querySelector('.pk-query-meta button');
                return [link.className, link.textContent, link.dataset.spanId, link.getAttribute('aria-label')].join('|');
            })()
            """);
        Object logLink = importModule("trace-detail/tabs/logs.js", """
            (() => {
                const container = document.createElement('div');
                m.render(container, {rootSpan: {spanId: 's1', name: 'a'},
                    logs: [{spanId: 's1', level: 'INFO', message: 'x', timestamp: '2026-01-01T00:00:00Z'}]}, {goToSpan: () => {}});
                const link = container.querySelector('.pk-log__span-row button:not(.pk-log__span)');
                return [link.className, link.textContent, link.dataset.spanId, link.getAttribute('aria-label')].join('|');
            })()
            """);

        assertThat(queryLink)
                .isEqualTo("pk-unbutton pk-icon-btn pk-span-tree-link|⤶|s1|Show this query's span in the span tree");
        assertThat(logLink).isEqualTo("pk-unbutton pk-icon-btn pk-span-tree-link|⤶|s1|Show span s1 in the span tree");
    }

    /**
     * An async subtree starts collapsed, so a Queries link to a span inside one points at a
     * hidden row: the scroll and the focus do nothing there and the flash lands out of sight.
     * The jump opens the subtree first.
     */
    @Test
    void aQueryLinkRevealsItsSpanInsideACollapsedAsyncSubtree() {
        String traceId = storeAsyncQueryTrace();
        openTraceDeepLink(traceId, "/queries");
        overlay.waitFor(".pk-query-item .pk-span-tree-link[data-span-id='async-query']");

        overlay.click(".pk-query-item .pk-span-tree-link[data-span-id='async-query']");

        assertThat(awaitJumpLanding())
                .containsEntry("flashed", "async-query")
                .containsEntry("focused", "async-query")
                .containsEntry("visible", true);
        assertThat(
                        overlay.evaluate(
                                "root => root.querySelector('.pk-gantt-span--async .pk-gantt-toggle').getAttribute('aria-expanded')"))
                .isEqualTo("true");
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
     * The SLOW pill reads severity.js's querySeverity() - the query threshold behind the
     * backend's SLOW_QUERY issue (slowQueryThresholdMs, default 50) - not the span
     * thresholds, and not a bare literal re-derived on the same line. Imports queries.js
     * directly (SharedModuleIT's pk-blank.html pattern) rather than driving a real slow
     * query through the app, and pins the exact boundary (49ms must NOT get the label;
     * 50ms - the threshold itself, IssueDetector compares with >= - must), and that a
     * threshold one higher moves it (see severity.js's own boundary tests in SharedModuleIT). The pill is trace-stats.js's slowBadge(), the one the Traces tab and
     * the overlay header show, beside the duration rather than in its text.
     */
    @Test
    void queriesTabSlowPillFollowsTheQueryThresholdAtTheBoundary() {
        Object labels = importModule("trace-detail/tabs/queries.js", """
            (() => {
                const queries = [
                    {durationMs: 49, dbSystem: 'h2', rowCount: 1},
                    {durationMs: 50, dbSystem: 'h2', rowCount: 1}
                ];
                const atDefault = document.createElement('div');
                m.render(atDefault, {queries}, {features: {slowQueryThresholdMs: 50}});
                const published = document.createElement('div');
                m.render(published, {queries}, {features: {slowQueryThresholdMs: 51}});
                const labelsIn = el => Array.from(el.querySelectorAll('.pk-query-meta')).map(meta =>
                    meta.querySelector('.pk-query__duration').textContent + '|'
                        + Array.from(meta.querySelectorAll('.pk-badge.pk-badge--warn'), pill => pill.textContent).join());
                return [...labelsIn(atDefault), ...labelsIn(published)];
            })()
            """);

        @SuppressWarnings("unchecked")
        List<String> durationLabels = (List<String>) labels;
        assertThat(durationLabels).containsExactly("49ms|", "50ms|SLOW", "49ms|", "50ms|");
    }

    /** The label adds the DataSource name only where it says more than the system already does. */
    @Test
    void queriesTabNamesTheDataSourceBesideTheSystemOnlyWhenItAddsSomething() {
        Object labels = importModule("trace-detail/tabs/queries.js", """
            (() => {
                const statement = {text: 'SELECT 1', formatted: null, parameters: []};
                const container = document.createElement('div');
                m.render(container, {queries: [
                    {statement, durationMs: 1, dbSystem: 'postgresql', dataSourceName: 'orders-db'},
                    {statement, durationMs: 1, dbSystem: 'h2'},
                    {statement, durationMs: 1, dbSystem: 'inventory-db', dataSourceName: 'inventory-db'},
                    {statement, durationMs: 1, dbSystem: 'h2', dataSourceName: 'H2'}
                ]});
                return Array.from(container.querySelectorAll('.pk-query-system')).map(label => label.textContent);
            })()
            """);

        assertThat(labels).isEqualTo(List.of("1. POSTGRESQL · orders-db", "2. H2", "3. INVENTORY-DB", "4. H2"));
    }
}

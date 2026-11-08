package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.peekaboot.fixtures.OutcomeFixtureController;
import org.peekaboot.testingapp.entity.CustomerOrder;
import org.peekaboot.testingapp.integration.OrderFixtures;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * What a trace's summary says beyond its outcome, where the overlay shows it. Each test reads the
 * trace's own JSON first and checks the overlay renders exactly that, so a slower run moves both
 * alike.
 */
class TraceFindingsIT extends TraceOverlayTestBase {

    private static final String WITH_A_CAUSE =
            "trace => (" + ROOT_SPAN_EXPORTED + ")(trace) && trace.summary.cause !== null";

    private static final String WITH_A_REPEATED_QUERY = "trace => (" + ROOT_SPAN_EXPORTED + ")(trace)"
            + " && trace.summary.issues.some(issue => issue.type === 'REPEATED_QUERY')";

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderLineRepository orderLineRepository;

    /** /boom throws from OrderController, so its cause has a class, a message, an application frame and a span. */
    @Test
    void theHeaderNamesTheCauseAndJumpsToItsSpan() {
        page.navigate(baseUrl + contextPath() + "/boom");
        String traceId = toolbar.traceId();
        JsonNode cause = awaitTrace(traceId, WITH_A_CAUSE).path("summary").path("cause");

        openOverlayFromTheDashboard(traceId);

        assertThat(overlay.text(".pk-overlay__cause-text"))
                .isEqualTo(cause.path("type").asString() + ": "
                        + cause.path("message").asString());
        assertThat(overlay.text(".pk-overlay__cause-frame"))
                .isEqualTo("at " + cause.path("applicationFrame").asString());
        overlay.click(".pk-overlay__cause .pk-span-tree-link");
        assertThat(awaitJumpLanding())
                .containsEntry("flashed", cause.path("spanId").asString());
    }

    /** A line logged at ERROR without an exception names neither a class nor a frame, so the header shows its message alone. */
    @Test
    void aCauseWithoutAClassOrFrameShowsItsMessageAlone() {
        String traceId = traceIdOf(page.navigate(baseUrl + contextPath() + OutcomeFixtureController.ERROR_LOG_PATH));
        JsonNode cause = awaitTrace(traceId, WITH_A_CAUSE).path("summary").path("cause");
        assertThat(cause.path("type").isNull()).as("the log names no class").isTrue();
        assertThat(cause.path("applicationFrame").isNull())
                .as("the log has no stack trace")
                .isTrue();

        openOverlayFromTheDashboard(traceId);

        assertThat(overlay.text(".pk-overlay__cause-text")).isEqualTo(OutcomeFixtureController.ERROR_LOG_MESSAGE);
        assertThat(overlay.evaluate("root => root.querySelector('.pk-overlay__cause-frame') === null"))
                .isEqualTo(true);
    }

    /** The report's stages are where its time went; the list above the tree names them in the summary's order and jumps to each. */
    @Test
    void theHotspotsAboveTheTreeJumpToTheirSpans() {
        CustomerOrder order = OrderFixtures.seedOrder(orderRepository, orderLineRepository, 1L, "WIDGET-FINDINGS");
        try {
            String traceId =
                    traceIdOf(page.navigate(baseUrl + contextPath() + "/api/orders/" + order.getId() + "/report"));
            JsonNode hotspots =
                    awaitTrace(traceId, ROOT_SPAN_EXPORTED).path("summary").path("hotspots");
            assertThat(hotspots.size())
                    .as("the report's stages spend self time")
                    .isPositive();
            List<String> selfTimes = hotspots.valueStream()
                    .map(hotspot -> String.valueOf(hotspot.path("selfMs").asLong()))
                    .toList();
            @SuppressWarnings("unchecked")
            List<String> formatted = (List<String>) importModule(
                    "shared/format.js", "[" + String.join(",", selfTimes) + "].map(ms => m.formatDurationMs(ms))");
            List<List<String>> expected = new ArrayList<>();
            for (int i = 0; i < hotspots.size(); i++) {
                JsonNode hotspot = hotspots.get(i);
                expected.add(List.of(
                        hotspot.path("spanId").asString(), hotspot.path("name").asString(), formatted.get(i)));
            }
            String first = hotspots.get(0).path("spanId").asString();

            openOverlayFromTheDashboard(traceId);
            overlay.waitFor(".pk-gantt-hotspot");

            assertThat(overlay.evaluate("""
                            root => [...root.querySelectorAll('.pk-gantt-hotspot')].map(hotspot => [
                                hotspot.dataset.spanId,
                                hotspot.querySelector('.pk-gantt-hotspot__name').textContent,
                                hotspot.querySelector('.pk-gantt-hotspot__self').textContent])
                            """)).isEqualTo(expected);
            assertThat(overlay.evaluate("root => root.querySelector('.pk-overlay__cause') === null"))
                    .as("the report did not fail, so the header names no cause")
                    .isEqualTo(true);
            overlay.click(".pk-gantt-hotspot[data-span-id='" + first + "']");
            assertThat(awaitJumpLanding()).containsEntry("flashed", first);
        } finally {
            OrderFixtures.deleteOrder(orderRepository, orderLineRepository, order);
        }
    }

    /**
     * The orders page loads each order's lines one query at a time. The span that ran them carries
     * one badge per issue type, titled with every issue of that type, and its details list each.
     */
    @Test
    void aRepeatedQueryIsBadgedOnTheSpanThatRanIt() {
        OrderFixtures.withRepeatingOrders(orderRepository, orderLineRepository, "WIDGET-FINDINGS-", () -> {
            String traceId = traceIdOf(page.navigate(baseUrl + contextPath() + "/orders"));
            JsonNode summary = awaitTrace(traceId, WITH_A_REPEATED_QUERY).path("summary");
            String spanId = repeatedQuerySpanId(summary);
            List<JsonNode> onTheSpan = issuesOn(summary, spanId);
            Map<String, List<String>> messagesByType = new LinkedHashMap<>();
            onTheSpan.stream()
                    .filter(issue -> !"ms".equals(issue.path("unit").asString()))
                    .forEach(issue -> messagesByType
                            .computeIfAbsent(issue.path("type").asString(), type -> new ArrayList<>())
                            .add(issue.path("message").asString()));
            List<String> expectedBadges = messagesByType.entrySet().stream()
                    .map(type -> type.getKey().replace('_', ' ') + "|pk-badge pk-badge--warn|"
                            + String.join("\n", type.getValue()))
                    .toList();
            String row = rowOf(spanId);

            openOverlayFromTheDashboard(traceId);
            overlay.waitFor(row);

            assertThat(overlay.evaluate(
                            "(root, row) => [...root.querySelectorAll(row + ' .pk-gantt-name .pk-badge')]"
                                    + ".map(badge => badge.textContent + '|' + badge.className + '|' + badge.title)",
                            row))
                    .isEqualTo(expectedBadges);
            overlay.click(row + " .pk-gantt-name__toggle");
            assertThat(issueMessages(spanId))
                    .isEqualTo(onTheSpan.stream()
                            .map(issue -> issue.path("message").asString())
                            .toList());
        });
    }

    /**
     * The orders page repeats several statements on one span. At a desktop width that span still
     * paints its name and shows its whole log chip beside its badges.
     */
    @Test
    void aSpanRepeatingSeveralStatementsKeepsItsNameAndLogChipInSight() {
        page.setViewportSize(1440, 900);
        OrderFixtures.withRepeatingOrders(orderRepository, orderLineRepository, "WIDGET-FINDINGS-", () -> {
            String traceId = traceIdOf(page.navigate(baseUrl + contextPath() + "/orders"));
            JsonNode trace = awaitTrace(traceId, WITH_A_REPEATED_QUERY);
            String spanId = repeatedQuerySpanId(trace.path("summary"));
            assertThat(issuesOn(trace.path("summary"), spanId).stream()
                            .filter(issue ->
                                    "REPEATED_QUERY".equals(issue.path("type").asString())))
                    .as("the span repeats more than one statement")
                    .hasSizeGreaterThan(1);
            assertThat(trace.path("data")
                            .path("logs")
                            .valueStream()
                            .map(log -> log.path("spanId").asString()))
                    .as("the span wrote a log, so its row has a log chip")
                    .contains(spanId);
            String row = rowOf(spanId);

            openTraceDeepLinkToMeasure(traceId, row + " .pk-span-logs-toggle");

            assertThat(((Number) overlay.evaluate(
                                    "(root, row) => root.querySelector(row + ' .pk-gantt-name__text')"
                                            + ".getBoundingClientRect().width",
                                    row))
                            .doubleValue())
                    .as("the span name's painted width")
                    .isPositive();
            assertThat(overlay.evaluate("""
                            (root, row) => root.querySelector(row + ' .pk-span-logs-toggle').getBoundingClientRect().right
                                - root.querySelector(row + ' .pk-gantt-name').getBoundingClientRect().right <= 0.5
                            """, row))
                    .as("the log chip ends inside the name cell, which clips what overflows it")
                    .isEqualTo(true);
        });
    }

    /** A 4xx is the caller's mistake: a badge on the request's span and no cause in the header. */
    @Test
    void aClientErrorIsBadgedOnTheRequestsSpanAndNamesNoCause() {
        String traceId = traceIdOf(page.navigate(baseUrl + contextPath() + OutcomeFixtureController.statusPath(409)));
        String rootSpanId = awaitTrace(traceId, ROOT_SPAN_EXPORTED)
                .path("data")
                .path("rootSpan")
                .path("spanId")
                .asString();

        openOverlayFromTheDashboard(traceId);

        assertThat(overlay.evaluate("""
                        root => [...root.querySelectorAll('.pk-gantt-name .pk-badge')].map(badge =>
                            badge.closest('.pk-gantt-row').dataset.spanId + '|' + badge.textContent + '|' + badge.className)
                        """)).isEqualTo(List.of(rootSpanId + "|CLIENT ERROR|pk-badge pk-badge--warn"));
        assertThat(overlay.evaluate("root => root.querySelector('.pk-overlay__cause') === null"))
                .isEqualTo(true);
        overlay.click(".pk-gantt-row[data-span-id='" + rootSpanId + "'] .pk-gantt-name__toggle");
        assertThat(issueMessages(rootSpanId))
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .contains("The request was answered with status 409.");
    }

    /** The report's root waits on its stages, so its self time is a part of its duration, shown after it. */
    @Test
    void aParentShowsItsSelfTimeAfterItsDuration() {
        CustomerOrder order = OrderFixtures.seedOrder(orderRepository, orderLineRepository, 1L, "WIDGET-SELF");
        try {
            String traceId =
                    traceIdOf(page.navigate(baseUrl + contextPath() + "/api/orders/" + order.getId() + "/report"));
            JsonNode root = awaitTrace(traceId, ROOT_SPAN_EXPORTED).path("data").path("rootSpan");
            long selfMs = root.path("selfMs").asLong();
            assertThat(selfMs)
                    .as("the root's children cover part of it")
                    .isLessThan(root.path("durationMs").asLong());
            String expectedSelf = (String) importModule("shared/format.js", "m.formatDurationMs(" + selfMs + ")");

            openOverlayFromTheDashboard(traceId);

            assertThat(overlay.text(
                            ".pk-gantt-row[data-span-id='" + root.path("spanId").asString() + "'] .pk-gantt-duration"))
                    .endsWith(" · self " + expectedSelf);
        } finally {
            OrderFixtures.deleteOrder(orderRepository, orderLineRepository, order);
        }
    }

    private static String repeatedQuerySpanId(JsonNode summary) {
        return summary.path("issues")
                .valueStream()
                .filter(issue -> "REPEATED_QUERY".equals(issue.path("type").asString()))
                .findFirst()
                .orElseThrow()
                .path("spanId")
                .asString();
    }

    private static List<JsonNode> issuesOn(JsonNode summary, String spanId) {
        return summary.path("issues")
                .valueStream()
                .filter(issue -> spanId.equals(issue.path("spanId").asString()))
                .toList();
    }

    private static String rowOf(String spanId) {
        return ".pk-gantt-row[data-span-id='" + spanId + "']";
    }

    /** The sentences the span's details panel lists, once it is open. */
    private Object issueMessages(String spanId) {
        overlay.waitFor("#pk-span-attrs-" + spanId + " .pk-span-issues__message");
        return overlay.evaluate(
                "(root, id) => [...root.querySelectorAll('#pk-span-attrs-' + id + ' .pk-span-issues__message')]"
                        + ".map(message => message.textContent)",
                spanId);
    }
}

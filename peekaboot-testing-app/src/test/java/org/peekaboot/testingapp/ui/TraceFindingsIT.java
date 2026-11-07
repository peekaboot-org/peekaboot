package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
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
}

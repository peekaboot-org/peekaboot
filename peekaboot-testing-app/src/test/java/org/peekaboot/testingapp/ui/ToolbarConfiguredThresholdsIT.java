package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.entity.CustomerOrder;
import org.peekaboot.testingapp.integration.OrderFixtures;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The bar and the overlay it opens colour by the effective thresholds, not by the defaults. No
 * span is slow here however long it takes, and every query is, so each assertion reads the
 * opposite of what the defaults would show.
 */
@TestPropertySource(
        properties = {
            "peekaboot.tracing.slow-span-threshold-ms=600000",
            "peekaboot.tracing.very-slow-span-threshold-ms=600000",
            "peekaboot.tracing.slow-query-threshold-ms=0"
        })
class ToolbarConfiguredThresholdsIT extends PlaywrightTestBase {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderLineRepository orderLineRepository;

    /**
     * The list and the overlay header colour a trace by trace.slow; the bar has to agree. The
     * report sleeps well past the default 500 ms very-slow threshold, and Swagger UI's idle bar
     * picks up its trace from the fetch.
     */
    @Test
    void theBarColoursTheDurationByTheTracesSlowVerdict() {
        CustomerOrder order = OrderFixtures.seedOrder(orderRepository, orderLineRepository, 1L, "WIDGET-THRESHOLDS");
        try {
            page.navigate(baseUrl + "/swagger-ui/index.html");
            page.waitForSelector("#peekaboot-toolbar-host[data-pk-ready='true']");
            page.evaluate("id => fetch('/api/orders/' + id + '/report')", String.valueOf(order.getId()));
            toolbar.awaitMetrics("span");

            @SuppressWarnings("unchecked")
            List<String> classes = (List<String>) toolbar.evaluate(
                    "root => Array.from(root.querySelector('#pk-metrics .pk-stat__duration').classList)");
            assertThat(classes).containsExactly("pk-stat__duration");
        } finally {
            OrderFixtures.deleteOrder(orderRepository, orderLineRepository, order);
        }
    }

    @Test
    void theOverlayItOpensColoursQueriesByTheConfiguredThreshold() {
        openPersonsPage();
        toolbar.awaitMetrics("quer");

        TraceOverlay opened = toolbar.openOverlay();
        opened.openTab("queries");
        opened.waitFor(".pk-query-meta");

        assertThat(opened.text(".pk-query-meta")).contains("SLOW");
    }
}

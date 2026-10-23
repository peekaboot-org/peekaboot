package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.entity.CustomerOrder;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** The statistics page exists to put a query past Peekaboot's SLOW_QUERY threshold every time. */
class OrderStatisticsIT extends TestingAppITBase {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderLineRepository orderLineRepository;

    @Test
    void thePageCountsOrdersPerStatus() {
        CustomerOrder order = OrderFixtures.seedOrder(orderRepository, orderLineRepository, 1L, "WIDGET-1");
        try {
            assertThat(api.get("/orders/stats")).contains("data-status=\"SHIPPED\"");
        } finally {
            OrderFixtures.deleteOrder(orderRepository, orderLineRepository, order);
        }
    }

    @Test
    void theStatisticsQueryIsFlaggedAsSlow() {
        JsonNode trace = traces.awaitTrace(traces.get("/orders/stats"), TraceApiClient.ROOT_SPAN_EXPORTED);

        JsonNode query = SpanTree.spanWithStatementContaining(trace, "pg_sleep");
        assertThat(query.path("issues")
                        .valueStream()
                        .map(issue -> issue.path("type").asString("")))
                .as("pg_sleep(0.08) holds the query past the 50 ms threshold on every run")
                .contains("SLOW_QUERY");
    }
}

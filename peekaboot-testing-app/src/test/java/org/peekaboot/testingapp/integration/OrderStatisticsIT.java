package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/** The statistics page exists to put a query past Peekaboot's SLOW_QUERY threshold every time. */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OrderStatisticsIT {

    @LocalServerPort
    private int port;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderLineRepository orderLineRepository;

    private PeekabootApi api;

    private TraceApiClient traces;

    @BeforeEach
    void connect() {
        api = new PeekabootApi(port);
        traces = new TraceApiClient(port);
    }

    @Test
    void thePageCountsOrdersPerStatus() {
        OrderFixtures.seedOrder(orderRepository, orderLineRepository, 1L, "WIDGET-1");

        assertThat(api.get("/orders/stats")).contains("data-status=\"SHIPPED\"");
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

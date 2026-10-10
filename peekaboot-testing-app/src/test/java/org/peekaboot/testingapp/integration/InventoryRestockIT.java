package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.JsonNode;

/** The restock exists to put one statement with many parameter sets in front of the Queries tab. */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InventoryRestockIT {

    @LocalServerPort
    private int port;

    @Autowired
    @Qualifier("inventory")
    private JdbcClient inventory;

    private PeekabootApi api;

    private TraceApiClient traces;

    @BeforeEach
    void connect() {
        api = new PeekabootApi(port);
        traces = new TraceApiClient(port);
    }

    /** GADGET-3 is the one SKU no other class orders, so its count is this test's alone to change. */
    @Test
    void restockingPutsEverySkuBackToItsSeededQuantityAndReturnsToTheOrders() {
        inventory.sql("UPDATE stock SET quantity = 0 WHERE sku = 'GADGET-3'").update();

        ResponseEntity<String> response = api.postForm("/inventory/restock", new LinkedMultiValueMap<>());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasPath("/orders");
        assertThat(inventory
                        .sql("SELECT quantity FROM stock WHERE sku = 'GADGET-3'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(50);
    }

    @Test
    void theRestockIsOneStatementWithSevenParameterSets() {
        ResponseEntity<String> response = api.postForm("/inventory/restock", new LinkedMultiValueMap<>());

        JsonNode trace =
                traces.awaitTrace(TraceApiClient.traceIdOf(response.getHeaders()), TraceApiClient.ROOT_SPAN_EXPORTED);

        List<JsonNode> restocks = new ArrayList<>();
        trace.path("queries").forEach(query -> {
            String sql = query.path("statement").path("text").asString("").toLowerCase(Locale.ROOT);
            if (sql.contains("set quantity = ? where sku = ?")) {
                restocks.add(query);
            }
        });
        assertThat(restocks).singleElement().satisfies(query -> {
            assertThat(query.path("dataSourceName").asString("")).isEqualTo("inventory-db");
            assertThat(query.path("statement").path("parameters").size())
                    .as("one parameter set per SKU: %s", query.path("statement").path("parameters"))
                    .isEqualTo(7);
        });
    }
}

package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.JsonNode;

/** The restock exists to put one statement with many parameter sets in front of the Queries tab. */
class InventoryRestockIT extends TestingAppITBase {

    @Autowired
    @Qualifier("inventory")
    private JdbcClient inventory;

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

        JsonNode restock = TraceQueries.queryContaining(trace, "SET quantity = ? WHERE sku = ?");
        assertThat(restock.path("dataSourceName").asString("")).isEqualTo("inventory-db");
        JsonNode parameters = TraceQueries.statementOf(trace, restock).path("parameters");
        assertThat(parameters.size())
                .as("one parameter set per SKU: %s", parameters)
                .isEqualTo(7);
    }
}

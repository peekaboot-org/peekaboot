package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

/**
 * The nav's order forms: the same placement as the JSON API behind a post-redirect-get, whose
 * failures render the error page and write no order.
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OrderFormIT {

    @LocalServerPort
    private int port;

    private PeekabootApi api;

    private TraceApiClient traces;

    @BeforeEach
    void connect() {
        api = new PeekabootApi(port);
        traces = new TraceApiClient(port);
    }

    @Test
    void aPlacedOrderRedirectsToTheOrdersPage() {
        ResponseEntity<String> response = api.postForm("/orders", order("WIDGET-4", 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasPath("/orders");
        assertThat(orderInsertsOf(response)).hasSize(1);
    }

    @Test
    void anUnknownProductIsABadRequestOnTheErrorPage() {
        ResponseEntity<String> response = api.postForm("/orders", order("NO-SUCH-PRODUCT", 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("pk-error__status\">400<");
        assertThat(orderInsertsOf(response)).isEmpty();
    }

    @Test
    void moreThanInStockIsAConflictOnTheErrorPageAndWritesNoOrder() {
        ResponseEntity<String> response = api.postForm("/orders", order("GADGET-1", 100_000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("pk-error__status\">409<");
        assertThat(orderInsertsOf(response)).isEmpty();
    }

    /** Validation turns this one away before the inventory is asked. */
    @Test
    void aQuantityBelowOneIsABadRequestAndWritesNoOrder() {
        ResponseEntity<String> response = api.postForm("/orders", order("WIDGET-4", 0));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(orderInsertsOf(response)).isEmpty();
    }

    private static MultiValueMap<String, String> order(String sku, int quantity) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("customerId", "1");
        form.add("sku", sku);
        form.add("quantity", String.valueOf(quantity));
        return form;
    }

    private List<String> orderInsertsOf(ResponseEntity<String> response) {
        JsonNode trace =
                traces.awaitTrace(TraceApiClient.traceIdOf(response.getHeaders()), TraceApiClient.ROOT_SPAN_EXPORTED);
        return TraceQueries.statementsOn(trace, "orders-db").stream()
                .filter(sql -> sql.startsWith("insert into customer_order"))
                .toList();
    }
}

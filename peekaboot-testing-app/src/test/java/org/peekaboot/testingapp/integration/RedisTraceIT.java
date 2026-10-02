package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.peekaboot.testingapp.inventory.InventoryRepository;
import org.peekaboot.testingapp.inventory.Product;
import org.peekaboot.testingapp.order.NewOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

/** A repeated product lookup is served by Redis; the dashboard's handling of Lettuce's spans is logged, not pinned. */
// @Testcontainers must stay first: JUnit runs afterAll in reverse, so the context closes before the container stops.
@Testcontainers
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.cache.type=redis")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RedisTraceIT {

    private static final Logger log = LoggerFactory.getLogger(RedisTraceIT.class);

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8").withExposedPorts(6379);

    @LocalServerPort
    private int port;

    @Autowired
    private InventoryRepository inventory;

    @Autowired
    @Qualifier("inventory")
    private JdbcClient inventoryJdbcClient;

    private TraceApiClient traces;

    @BeforeEach
    void connect() {
        traces = new TraceApiClient(port);
    }

    @Test
    void aRepeatedLookupIsServedFromRedisInsteadOfTheInventory() {
        placeOrder("GADGET-2");
        ResponseEntity<String> repeated = placeOrder("GADGET-2");

        JsonNode trace =
                traces.awaitTrace(TraceApiClient.traceIdOf(repeated.getHeaders()), TraceApiClient.ROOT_SPAN_EXPORTED);

        assertThat(SpanTree.tagValues(trace, "db.system")).contains("redis");
        assertThat(TraceQueries.statementsOn(trace, "inventory-db"))
                .as("the reservation still runs on the inventory; only the product lookup is cached")
                .anyMatch(sql -> sql.toLowerCase(Locale.ROOT).startsWith("update stock"))
                .noneMatch(sql -> sql.toLowerCase(Locale.ROOT).contains("from product"));

        log.info(
                "Lettuce spans on a cached order: summary.queries.count={}, queries={}, rootActionType={}",
                trace.path("summary").path("queries").path("count").asInt(),
                trace.path("queries"),
                trace.path("rootActionType").asString(""));
    }

    /** A cached miss would hide a product added within the TTL. */
    @Test
    void aLookupOfAnUnknownSkuIsNotCached() {
        assertThat(inventory.findProduct("LATE-1")).isEmpty();

        inventoryJdbcClient
                .sql("INSERT INTO product (sku, name, price) VALUES ('LATE-1', 'Late Arrival', 9.99)")
                .update();

        assertThat(inventory.findProduct("LATE-1")).map(Product::name).contains("Late Arrival");
    }

    private ResponseEntity<String> placeOrder(String sku) {
        return traces.restClient()
                .post()
                .uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new NewOrder(1L, sku, 1))
                .retrieve()
                .toEntity(String.class);
    }
}

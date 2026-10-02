package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.peekaboot.testingapp.order.NewOrder;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * The inventory on a real MySQL beside the orders on H2: a trace that touches both shows
 * queries from both, each labelled with its own pool name. Only the second DataSource gets a
 * container, since what is under test is that it stays apart from the primary.
 */
// @Testcontainers must stay first: JUnit runs afterAll in reverse, so the context closes before the container stops.
@Testcontainers
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MultiDataSourceTraceIT {

    // No @ServiceConnection: that would hand MySQL to the primary DataSource's connection details.
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:9");

    @DynamicPropertySource
    static void inventoryOnMySql(DynamicPropertyRegistry registry) {
        registry.add("app.datasource.inventory.url", MYSQL::getJdbcUrl);
        registry.add("app.datasource.inventory.username", MYSQL::getUsername);
        registry.add("app.datasource.inventory.password", MYSQL::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderLineRepository orderLineRepository;

    @Autowired
    @Qualifier("inventory")
    private JdbcClient inventory;

    private TraceApiClient traces;

    @BeforeEach
    void connect() {
        traces = new TraceApiClient(port);
    }

    @Test
    void theOrdersPageQueriesBothDataSourcesUnderTheirOwnNames() {
        OrderFixtures.seedOrder(orderRepository, orderLineRepository, 1L, "WIDGET-1");

        JsonNode trace = traces.awaitTrace(traces.get("/orders"), TraceApiClient.ROOT_SPAN_EXPORTED);

        assertThat(TraceQueries.dataSourceNames(trace)).contains("orders-db", "inventory-db");
        assertThat(TraceQueries.statementsOn(trace, "orders-db"))
                .anySatisfy(sql -> assertThat(sql).contains("from customer_order"));
        assertThat(TraceQueries.statementsOn(trace, "inventory-db"))
                .anySatisfy(sql -> assertThat(sql.toLowerCase(Locale.ROOT)).contains("from product"));
    }

    @Test
    void placingAnOrderReservesStockOnTheInventoryAndWritesTheOrderOnTheOrders() {
        ResponseEntity<String> response = placeOrder(new NewOrder(1L, "GADGET-1", 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode trace =
                traces.awaitTrace(TraceApiClient.traceIdOf(response.getHeaders()), TraceApiClient.ROOT_SPAN_EXPORTED);
        assertThat(TraceQueries.statementsOn(trace, "inventory-db"))
                .anySatisfy(sql -> assertThat(sql.toLowerCase(Locale.ROOT)).startsWith("update stock"));
        assertThat(TraceQueries.statementsOn(trace, "orders-db"))
                .anySatisfy(sql -> assertThat(sql).startsWith("insert into customer_order"));
    }

    @Test
    void anOrderBeyondTheStockIsAConflictAndReservesNothing() {
        int before = stockOf("WIDGET-2");

        ResponseEntity<String> response = placeOrder(new NewOrder(1L, "WIDGET-2", before + 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stockOf("WIDGET-2")).isEqualTo(before);
    }

    @Test
    void anOrderForAnUnknownSkuIsABadRequest() {
        ResponseEntity<String> response = placeOrder(new NewOrder(1L, "NO-SUCH-SKU", 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** MySQL's DATETIME carries no zone; without connectionTimeZone the driver reads it as JVM-local, hours off. */
    @Test
    void liquibaseChangeSetsOnMySqlCarryTheTrueExecutionInstant() {
        List<Timestamp> executed = inventory
                .sql("SELECT DATEEXECUTED FROM DATABASECHANGELOG")
                .query(Timestamp.class)
                .list();

        assertThat(executed)
                .isNotEmpty()
                .allSatisfy(timestamp ->
                        assertThat(timestamp.toInstant()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES)));
    }

    private int stockOf(String sku) {
        return inventory
                .sql("SELECT quantity FROM stock WHERE sku = ?")
                .param(sku)
                .query(Integer.class)
                .single();
    }

    /** Hands back an error status instead of throwing, so a test can assert on 400 and 409. */
    private ResponseEntity<String> placeOrder(NewOrder order) {
        return traces.restClient()
                .post()
                .uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(order)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {})
                .toEntity(String.class);
    }
}

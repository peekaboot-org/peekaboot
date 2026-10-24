package org.peekaboot.testingapp.integration;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingAppTest;
import org.peekaboot.testingapp.entity.CustomerOrder;
import org.peekaboot.testingapp.order.NewOrder;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * The inventory on a real MySQL beside the orders on H2: a trace that touches both shows
 * queries from both, each labelled with its own pool name. Only the second DataSource gets a
 * container, since what is under test is that it stays apart from the primary.
 */
@StartsContainers
@TestingAppTest
class MultiDataSourceTraceIT {

    // No @ServiceConnection: that would hand MySQL to the primary DataSource's connection details.
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(TestImages.MYSQL);

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
        CustomerOrder order = OrderFixtures.seedOrder(orderRepository, orderLineRepository, 1L, "WIDGET-1");
        try {
            JsonNode trace = traces.awaitTrace(traces.get("/orders"), TraceApiClient.ROOT_SPAN_EXPORTED);

            assertThat(TraceQueries.dataSourceNames(trace)).contains("orders-db", "inventory-db");
            assertThat(TraceQueries.statementsOn(trace, "orders-db"))
                    .anySatisfy(sql -> assertThat(sql).contains("from customer_order"));
            assertThat(TraceQueries.statementsOn(trace, "inventory-db"))
                    .anySatisfy(sql -> assertThat(sql.toLowerCase(Locale.ROOT)).contains("from product"));
        } finally {
            OrderFixtures.deleteOrder(orderRepository, orderLineRepository, order);
        }
    }

    @Test
    void placingAnOrderReservesStockOnTheInventoryAndWritesTheOrderOnTheOrders() {
        ResponseEntity<String> response = OrderFixtures.placeOrder(traces.api(), "GADGET-1", 1);

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

        ResponseEntity<String> response =
                traces.api().postJson("/api/orders", new NewOrder(1L, "WIDGET-2", before + 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stockOf("WIDGET-2")).isEqualTo(before);
    }

    @Test
    void anOrderForAnUnknownSkuIsABadRequest() {
        ResponseEntity<String> response = traces.api().postJson("/api/orders", new NewOrder(1L, "NO-SUCH-SKU", 1));

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

    /** Liquibase stamps DATEEXECUTED with MySQL's NOW() and reads it back for the endpoint in the JVM's zone. */
    @Test
    void theLiquibaseTabShowsWhenMySqlRanEachChangeSetOnAJvmOutsideMySqlsZone() {
        Map<String, Instant> ranAt = inventory
                .sql("SELECT ID, UNIX_TIMESTAMP(DATEEXECUTED) FROM DATABASECHANGELOG")
                .query((row, rowNumber) -> Map.entry(row.getString(1), Instant.ofEpochSecond(row.getLong(2))))
                .list()
                .stream()
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));

        JsonNode changeSets;
        TimeZone jvmZone = TimeZone.getDefault();
        // The container's MySQL runs on UTC; a JVM on any other zone exposes a reading placed in the wrong one.
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.ofHours(2)));
        try {
            changeSets = traces.api()
                    .getJson("/peekaboot/api/actuator/all/insights")
                    .path("liquibase")
                    .path("changeSets");
        } finally {
            TimeZone.setDefault(jvmZone);
        }

        assertThat(ranAt).isNotEmpty();
        assertThat(changeSets
                        .valueStream()
                        .collect(toMap(
                                changeSet -> changeSet.path("id").asString(),
                                changeSet -> Instant.parse(
                                        changeSet.path("dateExecuted").asString()))))
                .isEqualTo(ranAt);
    }

    private int stockOf(String sku) {
        return inventory
                .sql("SELECT quantity FROM stock WHERE sku = ?")
                .param(sku)
                .query(Integer.class)
                .single();
    }
}

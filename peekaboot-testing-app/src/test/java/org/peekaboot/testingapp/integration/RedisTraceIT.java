package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.testingapp.TestingApp;
import org.peekaboot.testingapp.inventory.InventoryRepository;
import org.peekaboot.testingapp.inventory.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;

/**
 * A repeated product lookup is served by Redis. Lettuce's detached upkeep commands are pinned as
 * maintenance; the rest of the dashboard's handling of its spans is logged, not pinned.
 */
@StartsContainers
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.cache.type=redis", "management.health.redis.enabled=true"})
@ActiveProfiles("test")
class RedisTraceIT {

    private static final Logger log = LoggerFactory.getLogger(RedisTraceIT.class);

    private static final String EVERY_TYPE = "rootActionType=*";
    private static final String NO_TYPE_NAMED = "";

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>(TestImages.REDIS).withExposedPorts(6379);

    @LocalServerPort
    private int port;

    @Autowired
    private InventoryRepository inventory;

    @Autowired
    @Qualifier("inventory")
    private JdbcClient inventoryJdbcClient;

    private TraceApiClient traces;

    /**
     * Boot's reactive Redis health indicator runs on Reactor's shared boundedElastic threads, which
     * outlive this context and make Tomcat report a leak when it stops. JUnit runs this before the
     * Spring extension closes the context, and {@link StartsContainers} keeps every other class away.
     */
    @AfterAll
    static void stopReactorSchedulers() {
        Schedulers.shutdownNow();
        await().until(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.getName().startsWith("boundedElastic")));
    }

    @BeforeEach
    void connect() {
        traces = new TraceApiClient(port);
    }

    @Test
    void aRepeatedLookupIsServedFromRedisInsteadOfTheInventory() {
        OrderFixtures.placeOrder(traces.api(), "GADGET-2", 1);
        ResponseEntity<String> repeated = OrderFixtures.placeOrder(traces.api(), "GADGET-2", 1);

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

    @Test
    void aCacheMissWritesTheProductInsideTheRequestTrace() {
        ResponseEntity<String> first = OrderFixtures.placeOrder(traces.api(), "WIDGET-3", 1);

        JsonNode trace =
                traces.awaitTrace(TraceApiClient.traceIdOf(first.getHeaders()), TraceApiClient.ROOT_SPAN_EXPORTED);

        assertThat(SpanTree.names(trace))
                .as("the lookup's get and the write after the miss")
                .contains("get", "set");
    }

    /**
     * The dashboard reads the actuator's health in-process, and Boot's reactive Redis health
     * indicator sends INFO with no parent span, so it starts a one-span trace of its own. That
     * trace is MAINTENANCE: listed when a request names the type or asks for every type, kept
     * out of the default view.
     */
    @Test
    void aDashboardHealthPollLeavesItsRedisInfoOutOfTheDefaultView() {
        Set<String> infoTracesBeforePoll = traces.listedTraces(EVERY_TYPE).stream()
                .filter(listed -> isRedisRootOf(listed, "INFO"))
                .map(listed -> listed.path("traceId").asString(""))
                .collect(Collectors.toSet());

        traces.api()
                .restClient()
                .get()
                .uri("/peekaboot/api/actuator/all/insights")
                .retrieve()
                .toBodilessEntity();

        JsonNode trace = traces.awaitTraceOfType(
                RootActionType.MAINTENANCE,
                listed -> isRedisRootOf(listed, "INFO")
                        && !infoTracesBeforePoll.contains(listed.path("traceId").asString("")),
                TraceApiClient::rootOperation);

        String traceId = trace.path("traceId").asString("");
        assertThat(traces.listedTraceIds(EVERY_TYPE)).contains(traceId);
        assertThat(traces.listedTraces(NO_TYPE_NAMED)).noneMatch(listed -> isRedisRootOf(listed, "INFO"));
    }

    /** Lettuce's handshake on a new connection, HELLO and then CLIENT SETINFO, arrives without a parent too. */
    @Test
    void connectionSetupCommandsAreMaintenance() {
        // Opens the cache's connection if no earlier test in this class did.
        OrderFixtures.placeOrder(traces.api(), "GADGET-2", 1);

        traces.awaitTraceOfType(
                RootActionType.MAINTENANCE, listed -> isRedisRootOf(listed, "HELLO"), TraceApiClient::rootOperation);

        assertThat(traces.listedTraces(NO_TYPE_NAMED))
                .noneMatch(listed -> isRedisRootOf(listed, "HELLO") || isRedisRootOf(listed, "CLIENT"));
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

    private static boolean isRedisRootOf(JsonNode listed, String command) {
        JsonNode tags = listed.path("rootSpan").path("tags");
        return "redis".equals(tags.path("db.system").asString(""))
                && command.equals(tags.path("db.operation").asString(""));
    }
}

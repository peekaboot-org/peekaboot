package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.peekaboot.testingapp.entity.CustomerOrder;
import org.peekaboot.testingapp.order.OrderReconciler;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import tools.jackson.databind.JsonNode;

/**
 * The demo endpoints exist to make Peekaboot's trace view worth looking at. These tests
 * assert what Peekaboot <em>captured</em> from them, not what the endpoints returned - a
 * green run here is evidence the Traces tab, the buckets and the query counters work end
 * to end.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderTraceCaptureIT extends TestingAppITBase {

    private static final int SEEDED_ORDERS = 8;

    /** OrderService.listOrders' deliberate N+1: findByOrderId, countByOrderId, existsById. */
    private static final int QUERIES_PER_ORDER = 3;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderLineRepository orderLineRepository;

    @Autowired
    private OrderReconciler reconciler;

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    private final List<CustomerOrder> seededOrders = new ArrayList<>();

    /**
     * Seeded once: every save outside a request is a root trace of its own in the store
     * the UI classes read, and no test here changes the seeded rows.
     */
    @BeforeAll
    void seedOrders() {
        for (int i = 1; i <= SEEDED_ORDERS; i++) {
            seededOrders.add(OrderFixtures.seedOrder(
                    orderRepository, orderLineRepository, 1L, "PLACED", i, "PK-CAPTURE-", "WIDGET-" + i));
        }
    }

    /** Leaves the context's shared database as this class found it. */
    @AfterAll
    void deleteSeededOrders() {
        seededOrders.forEach(order -> OrderFixtures.deleteOrder(orderRepository, orderLineRepository, order));
    }

    @Test
    void ordersPageCountsEveryQueryOfItsNPlusOne() {
        String traceId = traces.get("/orders");

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        assertThat(trace.path("summary").path("queries").path("count").asInt())
                .as(
                        "the deliberate N+1 runs one query for the list plus %d per order, so the "
                                + "%d seeded orders must be counted, or the Traces tab's query stat "
                                + "under-reports the page",
                        QUERIES_PER_ORDER, SEEDED_ORDERS)
                .isGreaterThanOrEqualTo(SEEDED_ORDERS * QUERIES_PER_ORDER + 1);
    }

    /**
     * The orders page loads each order's lines one query at a time, outside a transaction. Each query
     * opens a connection of its own, and datasource-micrometer records the connection span beside the
     * query, both under the handler span the loop ran in. The issue lands on that span, once per
     * repeated statement.
     */
    @Test
    void theOrdersPagesNPlusOneIsARepeatedQuery() {
        String traceId = traces.get("/orders");

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        List<JsonNode> repeated = SpanTree.issuesOfType(trace, "REPEATED_QUERY");
        assertThat(repeated)
                .as("findByOrderId, countByOrderId and existsById each run once per seeded order")
                .hasSizeGreaterThanOrEqualTo(QUERIES_PER_ORDER);
        JsonNode lines = repeated.stream()
                .filter(issue -> issue.path("statement").asString("").contains("order_line"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no repeated order_line statement among " + repeated));
        assertThat(lines.path("count").asInt()).isGreaterThanOrEqualTo(SEEDED_ORDERS);
        assertThat(lines.path("spanIds")).hasSize(lines.path("count").asInt());
        assertThat(lines.path("statement").asString()).doesNotContainPattern("= \\d");
        assertThat(SpanTree.spanWithId(trace, lines.path("spanId").asString())
                        .path("name")
                        .asString())
                .isNotEqualTo("connection");
    }

    /**
     * The other assertions against {@code /orders} check {@code summary.queries.count},
     * which passes whether {@code QueryExtractor.findSql} reads {@code db.query.text} or
     * merely falls back to a SQL-shaped span name - both produce a non-zero count. This
     * test is the one that actually exercises the fix: it runs against
     * {@code peekaboot-testing-app}'s real JDBC instrumentation
     * (datasource-micrometer-opentelemetry over the H2 datasource, the same library
     * {@code db.query.text} priority in {@code QueryExtractor.findSql} targets - see
     * {@code docs/ARCHITECTURE.md}'s <em>Query Extraction</em> section), not a hand-built
     * {@link org.peekaboot.backend.tracing.event.SpanData}.
     *
     * <p>Hibernate's real generated statement is lower-case ({@code "select
     * co1_0.id,co1_0.customer_id,...,co1_0.status from customer_order co1_0"} for the
     * orders page's primary query, confirmed by printing the live response), with a
     * comma-separated column list and table alias. OpenTelemetry's span-name summary -
     * what {@code findSql} falls back to only when no {@code db.*}/{@code jdbc.query[N]}
     * tag is present - is upper-case and bare, e.g. {@code "SELECT customer_order"}: no
     * column list, no alias, no lower-case {@code "from"}. Asserting the lower-case
     * {@code "select "}/{@code " from "} shape below is what a summary like that would
     * fail, so a regression back to the fallback (or a stack that stops emitting
     * {@code db.query.text}) breaks this test rather than passing it silently.
     *
     * <p>Only the orders-db queries are Hibernate's; the inventory's are hand-written JdbcClient SQL.
     */
    @Test
    void ordersPageQueriesCarryRealSqlNotASpanNameSummary() {
        String traceId = traces.get("/orders");

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        List<String> sqlTexts = TraceQueries.statementsOn(trace, "orders-db");

        assertThat(sqlTexts)
                .as("QueryExtractor must find at least one query on a page that trips real "
                        + "Hibernate/JDBC traffic, or there is nothing here to assert against")
                .isNotEmpty();

        assertThat(sqlTexts)
                .as(
                        "every query on /orders must carry Hibernate's real, lower-case SQL text "
                                + "from db.query.text, not OpenTelemetry's upper-case span-name summary "
                                + "(e.g. \"SELECT customer_order\") - queries seen: %s",
                        sqlTexts)
                .allSatisfy(sql ->
                        assertThat(sql).as("sql: %s", sql).startsWith("select ").contains(" from "));

        String customerOrderQuery = sqlTexts.stream()
                .filter(sql -> sql.contains("from customer_order"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no query against customer_order among: " + sqlTexts));

        assertThat(customerOrderQuery)
                .as("the customer_order query must be the real generated statement, with its "
                        + "column list and alias, not the bare span-name summary")
                .isNotEqualTo("SELECT customer_order")
                .contains(",");
    }

    @Test
    void slowReportLandsInTheSlowBucket() {
        Long orderId = seededOrders.getFirst().getId();

        String traceId = traces.get("/api/orders/" + orderId + "/report");

        JsonNode trace = traces.awaitTraceInBucket("slow", traceId);

        assertThat(trace.path("durationMs").asLong())
                .as("the report endpoint must exceed the default "
                        + "peekaboot.tracing.slow-trace-threshold-ms of 1000, or the Slow bucket "
                        + "has nothing to show")
                .isGreaterThanOrEqualTo(1000L);

        assertThat(SpanTree.names(trace))
                .as(
                        "the report's three stages must each show up as their own span, or the "
                                + "Slow bucket trace is just one opaque span again - spans seen: %s",
                        SpanTree.names(trace))
                .contains("order.report.load-lines", "order.report.price-lines", "order.report.apply-discounts");
    }

    /**
     * The report's three stages sleep 400, 600 and 400 ms under one enclosing span. That span
     * waited for them, so its self time leaves their 1,400 ms out and it raises no VERY_SLOW for
     * them, while the 600 ms stage is VERY_SLOW on its own.
     */
    @Test
    void theSpanAroundTheReportStagesIsNotVerySlow() {
        CustomerOrder order = OrderFixtures.seedOrder(orderRepository, orderLineRepository, 1L, "WIDGET-SELF-TIME");
        try {
            String traceId = traces.get("/api/orders/" + order.getId() + "/report");
            JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

            JsonNode enclosing = SpanTree.parentOf(trace, "order.report.price-lines");
            assertThat(enclosing.path("selfMs").isIntegralNumber())
                    .as("the enclosing span %s carries selfMs", SpanTree.spanId(enclosing))
                    .isTrue();
            assertThat(enclosing.path("selfMs").asLong())
                    .isLessThanOrEqualTo(enclosing.path("durationMs").asLong() - 1400);
            assertThat(enclosing
                            .path("issues")
                            .valueStream()
                            .map(issue -> issue.path("type").asString()))
                    .doesNotContain("VERY_SLOW");
            JsonNode priceLines =
                    SpanTree.spansNamed(trace, "order.report.price-lines").getFirst();
            assertThat(priceLines
                            .path("issues")
                            .valueStream()
                            .map(issue -> issue.path("type").asString()))
                    .contains("VERY_SLOW");
            JsonNode veryslow = priceLines
                    .path("issues")
                    .valueStream()
                    .filter(issue -> "VERY_SLOW".equals(issue.path("type").asString()))
                    .findFirst()
                    .orElseThrow();
            assertThat(veryslow.path("spanId").asString()).isEqualTo(SpanTree.spanId(priceLines));
            assertThat(veryslow.path("value").asLong())
                    .isEqualTo(priceLines.path("selfMs").asLong());
            assertThat(veryslow.path("threshold").asLong()).isEqualTo(500);
            assertThat(veryslow.path("unit").asString()).isEqualTo("ms");
            assertThat(veryslow.path("message").asString()).endsWith("ms very-slow-span threshold.");
        } finally {
            OrderFixtures.deleteOrder(orderRepository, orderLineRepository, order);
        }
    }

    @Test
    void failingEndpointLandsInTheErrorsBucket() {
        String traceId = traces.get("/boom");

        JsonNode trace = traces.awaitTraceInBucket("errors", traceId);

        assertThat(trace.path("status").asString(""))
                .as("a trace in the Errors bucket must be classified as having errors, "
                        + "otherwise the bucket filter and the status badge disagree")
                .isEqualTo("HAS_ERRORS");
    }

    /**
     * Tomcat logs /boom's stack trace after the span ended, with no trace id in the MDC, so the
     * trace's only copy of it is the exception its spans recorded.
     */
    @Test
    void aFailingRequestsSpanCarriesTheExceptionWithItsStackTrace() {
        JsonNode trace = traces.awaitTrace(traces.get("/boom"), TraceApiClient.ROOT_SPAN_EXPORTED);

        JsonNode exception = SpanTree.exceptions(trace).stream()
                .filter(each -> "java.lang.IllegalStateException"
                        .equals(each.path("type").asString("")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no span recorded the IllegalStateException: " + trace));
        assertThat(exception.path("message").asString("")).isEqualTo("order reconciliation gateway is unreachable");
        String[] lines = exception.path("stackTrace").asString("").split("\n", -1);
        assertThat(lines[0]).isEqualTo("java.lang.IllegalStateException: order reconciliation gateway is unreachable");
        JsonNode applicationFrames = exception.path("applicationFrames");
        assertThat(applicationFrames)
                .as("the throwing frame is the application's own")
                .isNotEmpty();
        assertThat(lines[applicationFrames.get(0).path("start").asInt()]).contains("OrderController.boom");
    }

    /** The console hides this line (logback-test.xml), so Peekaboot capturing it is what keeps it asserted. */
    @Test
    void failingEndpointsLoggedErrorIsCapturedAgainstItsTrace() {
        traces.awaitTrace(
                traces.get("/boom"), TraceApiClient.errorLogged("order reconciliation gateway is unreachable"));
    }

    @Test
    void ordersPageTraceIncludesTheOutboundCustomerLookup() {
        String traceId = traces.get("/orders");

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        assertThat(SpanTree.names(trace))
                .as("the outbound customer lookup must appear as its own span, or the demo "
                        + "trace shows only in-process work and the span tree looks flat")
                .anySatisfy(name -> assertThat(name).contains("/api/person/"));
    }

    @Test
    void placingAnOrderIsCapturedAsItsOwnTrace() {
        ResponseEntity<String> response = OrderFixtures.placeOrder(api, "WIDGET-1", 2);

        JsonNode trace =
                traces.awaitTrace(TraceApiClient.traceIdOf(response.getHeaders()), TraceApiClient.ROOT_SPAN_EXPORTED);

        assertThat(trace.path("rootActionType").asString(""))
                .as("a POST handled by a controller must be classified as an HTTP request")
                .isEqualTo("HTTP_REQUEST");
        assertThat(SpanTree.names(trace))
                .as("the order-placed listener runs inside the request, so its span belongs to this trace")
                .contains("order.placed");
    }

    /**
     * The order and its line are written in one transaction, so the trace shows a single
     * orders-db connection for both writes rather than one per save.
     */
    @Test
    void placingAnOrderWritesTheOrderAndItsLineOverOneConnection() {
        ResponseEntity<String> response = OrderFixtures.placeOrder(api, "WIDGET-2", 1);

        JsonNode trace =
                traces.awaitTrace(TraceApiClient.traceIdOf(response.getHeaders()), TraceApiClient.ROOT_SPAN_EXPORTED);

        assertThat(SpanTree.tagValuesOfSpansNamed(trace, "connection", "jdbc.datasource.pool"))
                .as("orders-db connections of the POST trace; the inventory lookup and reservation use their own")
                .filteredOn("orders-db"::equals)
                .hasSize(1);
    }

    /**
     * Calls {@link OrderReconciler#reconcileOrders()} directly rather than going through
     * Spring's scheduler, so this method's own {@code @Observed} span (named
     * {@code order.reconcile.job}) is the trace root. That span carries only its own
     * {@code class}/{@code method} tags, not the {@code code.function}/
     * {@code code.namespace} pair Spring's {@code DefaultScheduledTaskObservationConvention}
     * sets - so, correctly, it does <em>not</em> classify SCHEDULED_JOB, as a classifier
     * keyed on the span's name containing "job" would.
     * {@link #reconciliationFiredByTheSchedulerIsCapturedAsAScheduledJobTrace()} covers
     * the shape that does classify SCHEDULED_JOB.
     */
    @Test
    void directReconciliationCallDoesNotClassifyAsScheduledJob() {
        JsonNode trace = traces.awaitTraceAppearing("order.reconcile.job", reconciler::reconcileOrders);

        assertThat(trace.path("rootActionType").asString(""))
                .as("a direct call carries none of Spring's scheduled-task tags, so its "
                        + "root span must not be misclassified as SCHEDULED_JOB just because "
                        + "its name contains \"job\"")
                .isEqualTo("UNKNOWN");
    }

    /**
     * Fires {@code reconcileOrders()} through the scheduler's own {@link Runnable} (see
     * {@link ScheduledJobs}), so the trace root is Spring's scheduled-task observation
     * with its {@code code.function}/{@code code.namespace} tags - the same tree a live
     * timer produces.
     */
    @Test
    void reconciliationFiredByTheSchedulerIsCapturedAsAScheduledJobTrace() {
        String traceId = ScheduledJobs.run(scheduledTaskHolder, OrderReconciler.class, "reconcileOrders");

        JsonNode trace = traces.awaitTraceInBucket("all", traceId);

        assertThat(trace.path("rootActionType").asString(""))
                .as("Spring's scheduled-task observation wraps the call and becomes the "
                        + "trace root when the scheduler fires it; that root span's "
                        + "code.function/code.namespace tags must still classify SCHEDULED_JOB")
                .isEqualTo("SCHEDULED_JOB");
    }
}

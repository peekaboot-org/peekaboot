package org.peekaboot.testingapp.integration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.peekaboot.testingapp.entity.CustomerOrder;
import org.peekaboot.testingapp.entity.OrderLine;
import org.peekaboot.testingapp.order.NewOrder;
import org.peekaboot.testingapp.order.OrderReconciler;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo endpoints exist to make Peekaboot's trace view worth looking at. These tests
 * assert what Peekaboot <em>captured</em> from them, not what the endpoints returned - a
 * green run here is evidence the Traces tab, the buckets and the query counters work end
 * to end.
 */
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OrderTraceCaptureIntegrationTest {

    private static final int SEEDED_ORDERS = 8;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderLineRepository orderLineRepository;

    @Autowired
    private OrderReconciler reconciler;

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    private TraceApiClient traces;

    @BeforeEach
    void seedOrders() {
        orderLineRepository.deleteAll();
        orderRepository.deleteAll();
        for (int i = 1; i <= SEEDED_ORDERS; i++) {
            CustomerOrder order = new CustomerOrder();
            order.setReference("PK-200" + i);
            order.setCustomerId(1L);
            order.setStatus("PLACED");
            order.setPlacedAt(Instant.parse("2026-08-20T08:00:00Z").plusSeconds(i * 60L));
            CustomerOrder saved = orderRepository.save(order);

            OrderLine line = new OrderLine();
            line.setOrderId(saved.getId());
            line.setSku("WIDGET-" + i);
            line.setQuantity(i);
            line.setUnitPrice(new BigDecimal("19.99"));
            orderLineRepository.save(line);
        }
        traces = new TraceApiClient(port, objectMapper);
    }

    @Test
    void ordersPageTripsTheHighTraceQueryCountThreshold() {
        String traceId = traces.triggerAndCaptureTraceId("/orders");

        JsonNode trace = traces.awaitTrace(traceId);

        assertThat(trace.path("summary").path("queries").path("count").asInt())
                .as("the deliberate N+1 on /orders must exceed the default "
                  + "peekaboot.ui.tracing.high-trace-query-count-threshold of 20, or the "
                  + "Traces tab has no high-query-count warning to show")
                .isGreaterThan(20);
    }

    @Test
    void slowReportLandsInTheSlowBucket() {
        Long orderId = orderRepository.findAll().getFirst().getId();

        traces.trigger("/api/orders/" + orderId + "/report");

        JsonNode trace = traces.awaitTraceInBucket("slow", "/api/orders/{id}/report");

        assertThat(trace.path("durationMs").asLong())
                .as("the report endpoint must exceed the default "
                  + "peekaboot.tracing.slow-trace-threshold-ms of 1000, or the Slow bucket "
                  + "has nothing to show")
                .isGreaterThanOrEqualTo(1000L);

        List<String> spanNames = new ArrayList<>();
        collectSpanNames(trace.path("rootSpan"), spanNames);

        assertThat(spanNames)
                .as("the report's three stages must each show up as their own span, or the "
                  + "Slow bucket trace is just one opaque span again - spans seen: %s", spanNames)
                .contains("order.report.load-lines", "order.report.price-lines", "order.report.apply-discounts");
    }

    @Test
    void failingEndpointLandsInTheErrorsBucket() {
        traces.trigger("/boom");

        JsonNode trace = traces.awaitTraceInBucket("errors", "/boom");

        assertThat(trace.path("status").asString(""))
                .as("a trace in the Errors bucket must be classified as having errors, "
                  + "otherwise the bucket filter and the status badge disagree")
                .isEqualTo("HAS_ERRORS");
    }

    @Test
    void ordersPageTraceIncludesTheOutboundCustomerLookup() {
        String traceId = traces.triggerAndCaptureTraceId("/orders");

        JsonNode trace = traces.awaitTrace(traceId);

        assertThat(spanNames(trace))
                .as("the outbound customer lookup must appear as its own span, or the demo "
                  + "trace shows only in-process work and the span tree looks flat")
                .anySatisfy(name -> assertThat(name).contains("/api/person/"));
    }

    @Test
    void placingAnOrderIsCapturedAsItsOwnTrace() {
        traces.restClient().post()
                .uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new NewOrder(1L, "WIDGET-NEW", 2))
                .retrieve()
                .toBodilessEntity();

        JsonNode trace = traces.awaitTraceInBucket("all", "http post /api/orders");

        assertThat(trace.path("rootActionType").asString(""))
                .as("a POST handled by a controller must be classified as an HTTP request")
                .isEqualTo("HTTP_REQUEST");
    }

    /**
     * Calls {@link OrderReconciler#reconcileOrders()} directly rather than going through
     * Spring's scheduler, so this method's own {@code @Observed} span (named
     * {@code order.reconcile.job}) is the trace root. That span carries only its own
     * {@code class}/{@code method} tags, not the {@code code.function}/
     * {@code code.namespace} pair Spring's {@code DefaultScheduledTaskObservationConvention}
     * sets - so, correctly, it does <em>not</em> classify SCHEDULED_JOB. Before this
     * defect was fixed it did, purely because the span's name happened to contain "job";
     * this test now guards against that false positive recurring.
     * {@link #reconciliationFiredByTheSchedulerIsCapturedAsAScheduledJobTrace()} covers
     * the shape that does classify SCHEDULED_JOB - the one the defect was actually about.
     */
    @Test
    void directReconciliationCallDoesNotClassifyAsScheduledJob() {
        reconciler.reconcileOrders();

        JsonNode trace = traces.awaitTraceInBucket("all", "order.reconcile.job");

        assertThat(trace.path("rootActionType").asString(""))
                .as("a direct call carries none of Spring's scheduled-task tags, so its "
                  + "root span must not be misclassified as SCHEDULED_JOB just because "
                  + "its name contains \"job\"")
                .isEqualTo("INTERNAL");
    }

    /**
     * Runs the exact {@link Runnable} Spring's {@code TaskScheduler} invokes every
     * {@code fixedDelay}, instead of waiting on the timer: {@code reconcileOrders()} is
     * scheduled every 2 minutes, far past what an integration test should block on. The
     * {@link ScheduledTaskHolder} bean (Spring's {@code ScheduledAnnotationBeanPostProcessor})
     * exposes the {@link ScheduledTask} registered for every {@code @Scheduled} method;
     * running its {@code Runnable} here is the real production code path, not a stand-in
     * for it - it builds the same {@code ScheduledTaskObservationContext}, sets the same
     * {@code code.function}/{@code code.namespace} tags, and names the root span
     * {@code task orderReconciler.reconcileOrders}, exactly as a live scheduler firing it
     * would.
     */
    @Test
    void reconciliationFiredByTheSchedulerIsCapturedAsAScheduledJobTrace() {
        runScheduledReconciliation();

        JsonNode trace = traces.awaitTraceInBucket("all", "task orderReconciler.reconcileOrders");

        assertThat(trace.path("rootActionType").asString(""))
                .as("Spring's scheduled-task observation wraps the call and becomes the "
                  + "trace root when the scheduler fires it; that root span's "
                  + "code.function/code.namespace tags must still classify SCHEDULED_JOB")
                .isEqualTo("SCHEDULED_JOB");
    }

    private void runScheduledReconciliation() {
        // Task#toString() delegates down to the underlying ScheduledMethodRunnable's
        // toString() ("<declaringClass>.<method>"); getTask() itself wraps the runnable
        // in an outcome-tracking decorator, so matching on the runnable's type directly
        // isn't an option.
        String taskDescription = OrderReconciler.class.getName() + ".reconcileOrders";
        scheduledTaskHolder.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(task -> taskDescription.equals(task.toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "OrderReconciler#reconcileOrders is not registered as a scheduled task"))
                .getRunnable()
                .run();
    }

    private static List<String> spanNames(JsonNode trace) {
        List<String> names = new ArrayList<>();
        collectSpanNames(trace.path("rootSpan"), names);
        return names;
    }

    private static void collectSpanNames(JsonNode span, List<String> out) {
        out.add(span.path("name").asString(""));
        for (JsonNode child : span.path("children")) {
            collectSpanNames(child, out);
        }
    }
}

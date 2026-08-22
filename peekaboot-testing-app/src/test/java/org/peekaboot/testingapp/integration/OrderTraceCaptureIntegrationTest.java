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
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Each JDBC call is captured twice (once directly, once nested under its own
        // sibling span - a known double-instrumentation artifact that SpanDeduplicator
        // collapses back down) before the trace store's default 100-span-per-trace cap
        // even applies. Left at the default, that cap truncates this deliberately heavy
        // trace long before all seeded orders' queries are captured, so raising
        // SEEDED_ORDERS alone cannot cross the query-count threshold. Raised here, scoped
        // to just this test, so the trace captures the endpoint's full N+1 shape.
        properties = "peekaboot.tracing.max-spans-per-trace=500")
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

    private static void collectSpanNames(JsonNode span, List<String> out) {
        out.add(span.path("name").asString(""));
        for (JsonNode child : span.path("children")) {
            collectSpanNames(child, out);
        }
    }
}

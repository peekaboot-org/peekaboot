package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.peekaboot.testingapp.entity.CustomerOrder;
import org.peekaboot.testingapp.entity.OrderLine;
import org.peekaboot.testingapp.order.CustomerClient;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/**
 * A customer id with nobody behind it is an answer both ends of the demo's outbound call
 * agree on: the person API says 404 and {@code CustomerClient} names the id instead of
 * logging a failure.
 *
 * <p>Every full build takes this path - the test profile runs with Flyway off, so the
 * person table is empty and each {@code /orders} load looks a customer up in vain. Without
 * these two tests, dropping the 404 branch would only show up as four WARN lines with a
 * stack trace in the suite's output.
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CustomerLookupIT {

    @LocalServerPort
    private int port;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderLineRepository orderLineRepository;

    private PeekabootApi api;

    @BeforeEach
    void connect() {
        api = new PeekabootApi(port);
    }

    @Test
    void anIdWithNobodyBehindItAnswersNotFound() {
        assertThat(api.statusOf("/api/person/999"))
                .as("ResponseEntity.of turns the empty Optional into a 404, not a 200 with a null body")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theOrdersPageNamesAnUnknownCustomerByIdWithoutWarning() {
        seedAnOrder();
        // the page names the first order's customer on every row, so that is the id it renders
        long customerId = orderRepository.findAll().getFirst().getCustomerId();

        String orders;
        try (LogCapture logs = LogCapture.attach(CustomerClient.class)) {
            orders = api.get("/orders");

            assertThat(logs.appender().list)
                    .as("a 404 from the person API is an answer, not a failure worth a stack trace")
                    .noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
        }

        assertThat(orders)
                .as("the order still renders, with the id standing in for the name")
                .contains("customer #" + customerId);
    }

    private void seedAnOrder() {
        CustomerOrder order = new CustomerOrder();
        order.setReference("PK-CUST-" + System.nanoTime());
        order.setCustomerId(999L);
        order.setStatus("SHIPPED");
        order.setPlacedAt(Instant.parse("2026-08-20T08:00:00Z"));
        CustomerOrder saved = orderRepository.save(order);

        OrderLine line = new OrderLine();
        line.setOrderId(saved.getId());
        line.setSku("WIDGET-CUST");
        line.setQuantity(1);
        line.setUnitPrice(new BigDecimal("19.99"));
        orderLineRepository.save(line);
    }
}

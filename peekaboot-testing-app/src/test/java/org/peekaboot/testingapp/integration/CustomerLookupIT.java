package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.entity.CustomerOrder;
import org.peekaboot.testingapp.order.CustomerClient;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * A customer id with nobody behind it is an answer both ends of the demo's outbound call
 * agree on: the person API says 404 and {@code CustomerClient} names the id instead of
 * logging a failure.
 *
 * <p>Most {@code /orders} loads in a full build take this path, since the test profile runs
 * with Flyway off and few orders have a person behind their customer id. Without these
 * tests, dropping the 404 branch would only show up as WARN lines with a stack trace in the
 * suite's output. The client is asked directly, about an id no fixture creates: the page
 * names whichever order comes first, in a database other classes write persons and orders to.
 */
class CustomerLookupIT extends TestingAppITBase {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderLineRepository orderLineRepository;

    @Autowired
    private CustomerClient customerClient;

    @Test
    void anIdWithNobodyBehindItAnswersNotFound() {
        assertThat(api.statusOf("/api/person/999"))
                .as("ResponseEntity.of turns the empty Optional into a 404, not a 200 with a null body")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anUnknownCustomerIsNamedByIdWithoutWarning() {
        try (LogCapture logs = LogCapture.attach(CustomerClient.class)) {
            assertThat(customerClient.lookupCustomerName(999L))
                    .as("the order still renders, with the id standing in for the name")
                    .isEqualTo("customer #999");

            assertThat(logs.appender().list)
                    .as("a 404 from the person API is an answer, not a failure worth a stack trace")
                    .noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
        }
    }

    /** A line can name a SKU the inventory never had, as a fixture's does; the page shows the SKU instead. */
    @Test
    void theOrdersPageNamesEachLinesProductAndFallsBackToTheSku() {
        CustomerOrder order =
                OrderFixtures.seedOrder(orderRepository, orderLineRepository, 999L, "WIDGET-1", "RETIRED-SKU");
        try {
            String orders = api.get("/orders");

            assertThat(orders).contains("Widget One").contains("RETIRED-SKU");
        } finally {
            OrderFixtures.deleteOrder(orderRepository, orderLineRepository, order);
        }
    }
}

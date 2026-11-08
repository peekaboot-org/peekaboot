package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.peekaboot.testingapp.entity.CustomerOrder;
import org.peekaboot.testingapp.entity.OrderLine;
import org.peekaboot.testingapp.order.NewOrder;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Orders for a test: saved straight through the repositories, for a test that needs one on the
 * orders page, or placed through the order API, for a test about what placing one does.
 */
public final class OrderFixtures {

    /** Above the default repeated-query threshold of 5, which the orders page's per-order queries have to reach. */
    private static final int REPEATING_ORDERS = 6;

    private OrderFixtures() {}

    /** A SHIPPED order for {@code customerId} with one line of quantity 1 per SKU, whether the inventory knows it or not. */
    public static CustomerOrder seedOrder(
            OrderRepository orderRepository, OrderLineRepository orderLineRepository, long customerId, String... skus) {
        return seedOrder(orderRepository, orderLineRepository, customerId, "SHIPPED", 1, "PK-SEED-", skus);
    }

    /**
     * An order for {@code customerId} in {@code status} with one line of {@code quantity} per
     * SKU, whether the inventory knows it or not. Its reference is {@code referencePrefix} and a
     * suffix unique to the call.
     */
    public static CustomerOrder seedOrder(
            OrderRepository orderRepository,
            OrderLineRepository orderLineRepository,
            long customerId,
            String status,
            int quantity,
            String referencePrefix,
            String... skus) {
        CustomerOrder order = new CustomerOrder();
        order.setReference(referencePrefix + System.nanoTime());
        order.setCustomerId(customerId);
        order.setStatus(status);
        order.setPlacedAt(Instant.parse("2026-08-20T08:00:00Z"));
        CustomerOrder saved = orderRepository.save(order);

        for (String sku : skus) {
            OrderLine line = new OrderLine();
            line.setOrderId(saved.getId());
            line.setSku(sku);
            line.setQuantity(quantity);
            line.setUnitPrice(new BigDecimal("19.99"));
            orderLineRepository.save(line);
        }
        return saved;
    }

    /** Places an order for customer 1 through {@code POST /api/orders}; anything but a 201 fails here. */
    static ResponseEntity<String> placeOrder(PeekabootApi api, String sku, int quantity) {
        return placeOrder(api, 1L, sku, quantity);
    }

    /** Places an order for {@code customerId} through {@code POST /api/orders}; anything but a 201 fails here. */
    static ResponseEntity<String> placeOrder(PeekabootApi api, long customerId, String sku, int quantity) {
        ResponseEntity<String> response = api.postJson("/api/orders", new NewOrder(customerId, sku, quantity));
        assertThat(response.getStatusCode())
                .as("POST /api/orders answered %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return response;
    }

    /**
     * Runs {@code test} with enough seeded orders that the orders page is an N+1, each with one line
     * whose SKU is {@code skuPrefix} and a number, and deletes them afterwards however {@code test} ends.
     */
    public static void withRepeatingOrders(
            OrderRepository orderRepository, OrderLineRepository orderLineRepository, String skuPrefix, Runnable test) {
        List<CustomerOrder> orders = IntStream.rangeClosed(1, REPEATING_ORDERS)
                .mapToObj(i -> seedOrder(orderRepository, orderLineRepository, 1L, skuPrefix + i))
                .toList();
        try {
            test.run();
        } finally {
            orders.forEach(order -> deleteOrder(orderRepository, orderLineRepository, order));
        }
    }

    /** Undoes {@link #seedOrder}, so a test leaves the context's shared database as it found it. */
    public static void deleteOrder(
            OrderRepository orderRepository, OrderLineRepository orderLineRepository, CustomerOrder order) {
        orderLineRepository.deleteAll(orderLineRepository.findByOrderId(order.getId()));
        orderRepository.delete(order);
    }
}

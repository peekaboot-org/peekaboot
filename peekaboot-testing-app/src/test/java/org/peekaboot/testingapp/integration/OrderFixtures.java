package org.peekaboot.testingapp.integration;

import java.math.BigDecimal;
import java.time.Instant;
import org.peekaboot.testingapp.entity.CustomerOrder;
import org.peekaboot.testingapp.entity.OrderLine;
import org.peekaboot.testingapp.order.NewOrder;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.springframework.http.ResponseEntity;

/**
 * Orders for a test: saved straight through the repositories, for a test that needs one on the
 * orders page, or placed through the order API, for a test about what placing one does.
 */
final class OrderFixtures {

    private OrderFixtures() {}

    /** An order for {@code customerId} with one line of quantity 1 per SKU, whether the inventory knows it or not. */
    static CustomerOrder seedOrder(
            OrderRepository orderRepository, OrderLineRepository orderLineRepository, long customerId, String... skus) {
        CustomerOrder order = new CustomerOrder();
        order.setReference("PK-SEED-" + System.nanoTime());
        order.setCustomerId(customerId);
        order.setStatus("SHIPPED");
        order.setPlacedAt(Instant.parse("2026-08-20T08:00:00Z"));
        CustomerOrder saved = orderRepository.save(order);

        for (String sku : skus) {
            OrderLine line = new OrderLine();
            line.setOrderId(saved.getId());
            line.setSku(sku);
            line.setQuantity(1);
            line.setUnitPrice(new BigDecimal("19.99"));
            orderLineRepository.save(line);
        }
        return saved;
    }

    /** Places an order for customer 1 through {@code POST /api/orders}, answered as served. */
    static ResponseEntity<String> placeOrder(PeekabootApi api, String sku, int quantity) {
        return api.postJson("/api/orders", new NewOrder(1L, sku, quantity));
    }

    /** Undoes {@link #seedOrder}, so a test leaves the context's shared database as it found it. */
    static void deleteOrder(
            OrderRepository orderRepository, OrderLineRepository orderLineRepository, CustomerOrder order) {
        orderLineRepository.deleteAll(orderLineRepository.findByOrderId(order.getId()));
        orderRepository.delete(order);
    }
}

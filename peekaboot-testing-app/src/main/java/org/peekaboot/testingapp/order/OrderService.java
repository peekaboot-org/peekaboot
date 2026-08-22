package org.peekaboot.testingapp.order;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.peekaboot.testingapp.entity.CustomerOrder;
import org.peekaboot.testingapp.entity.OrderLine;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The demo's business logic. Several methods here are deliberately inefficient: this
 * application exists to give Peekaboot's trace view something worth showing, and a
 * perfectly tuned service produces a boring two-span trace.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OrderLineRepository orderLineRepository;

    public OrderService(OrderRepository orderRepository, OrderLineRepository orderLineRepository) {

        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
    }


    /**
     * Deliberate N+1: one query for the orders, then three per order. Trips the
     * high-trace-query-count threshold so the Traces tab has a warning to render.
     */
    public List<OrderSummary> listOrders() {

        List<CustomerOrder> orders = orderRepository.findAll();
        log.info("loading summaries for {} orders", orders.size());

        List<OrderSummary> summaries = new ArrayList<>();
        for (CustomerOrder order : orders) {
            List<OrderLine> lines = orderLineRepository.findByOrderId(order.getId());
            long lineTotal = orderLineRepository.countByOrderId(order.getId());
            boolean known = orderRepository.existsById(order.getId());

            BigDecimal total = lines.stream()
                    .map(line -> line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            summaries.add(new OrderSummary(
                    order.getId(),
                    order.getReference(),
                    known ? order.getStatus() : "UNKNOWN",
                    order.getPlacedAt(),
                    (int) lineTotal,
                    total,
                    "customer #" + order.getCustomerId()));
        }
        return summaries;
    }
}

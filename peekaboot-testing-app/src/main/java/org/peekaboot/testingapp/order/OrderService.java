package org.peekaboot.testingapp.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import org.peekaboot.testingapp.entity.CustomerOrder;
import org.peekaboot.testingapp.entity.OrderLine;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
    private final OrderReportStages reportStages;
    private final CustomerClient customerClient;
    private final ApplicationEventPublisher eventPublisher;

    public OrderService(
            OrderRepository orderRepository,
            OrderLineRepository orderLineRepository,
            OrderReportStages reportStages,
            CustomerClient customerClient,
            ApplicationEventPublisher eventPublisher) {

        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.reportStages = reportStages;
        this.customerClient = customerClient;
        this.eventPublisher = eventPublisher;
    }


    /**
     * Deliberate N+1: one query for the orders, then three per order. Trips the
     * high-trace-query-count threshold so the Traces tab has a warning to render.
     */
    public List<OrderSummary> listOrders() {

        List<CustomerOrder> orders = orderRepository.findAll();
        log.info("loading summaries for {} orders", orders.size());

        String customerName = orders.isEmpty()
                ? ""
                : customerClient.lookupCustomerName(orders.getFirst().getCustomerId());

        List<OrderSummary> summaries = new ArrayList<>();
        for (CustomerOrder order : orders) {
            List<OrderLine> lines = orderLineRepository.findByOrderId(order.getId());
            long lineCount = orderLineRepository.countByOrderId(order.getId());
            boolean known = orderRepository.existsById(order.getId());

            BigDecimal total = lines.stream()
                    .map(line -> line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            summaries.add(new OrderSummary(
                    order.getId(),
                    order.getReference(),
                    known ? order.getStatus() : "UNKNOWN",
                    order.getPlacedAt(),
                    (int) lineCount,
                    total,
                    customerName));
        }
        return summaries;
    }


    /**
     * Deliberately slow, in three observed stages, so the Slow bucket holds a trace whose
     * span tree shows where the time actually went.
     */
    public OrderReport buildReport(long orderId) {

        long started = System.nanoTime();
        CustomerOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("no order " + orderId));

        List<OrderLine> lines = reportStages.loadLines(orderId);
        BigDecimal total = reportStages.priceLines(lines);
        total = reportStages.applyDiscounts(total);

        long computeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        log.info("report for {} computed in {}ms", order.getReference(), computeMillis);
        return new OrderReport(order.getReference(), lines.size(), total, computeMillis);
    }


    public CustomerOrder placeOrder(NewOrder request) {

        CustomerOrder order = new CustomerOrder();
        order.setReference("PK-" + System.currentTimeMillis());
        order.setCustomerId(request.customerId());
        order.setStatus("PLACED");
        order.setPlacedAt(Instant.now());
        CustomerOrder saved = orderRepository.save(order);

        OrderLine line = new OrderLine();
        line.setOrderId(saved.getId());
        line.setSku(request.sku());
        line.setQuantity(request.quantity());
        line.setUnitPrice(new BigDecimal("19.99"));
        orderLineRepository.save(line);

        log.info("placed order {} for customer {}", saved.getReference(), request.customerId());
        eventPublisher.publishEvent(new OrderPlacedEvent(saved.getId(), saved.getReference()));
        return saved;
    }
}

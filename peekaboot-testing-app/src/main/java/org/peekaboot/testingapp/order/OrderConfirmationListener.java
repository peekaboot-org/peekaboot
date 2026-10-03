package org.peekaboot.testingapp.order;

import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Confirms a placed order inside the request and its transaction; not {@code @Observed}, Peekaboot spans it. */
@Component
public class OrderConfirmationListener {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmationListener.class);

    private final OrderLineRepository orderLineRepository;

    public OrderConfirmationListener(OrderLineRepository orderLineRepository) {

        this.orderLineRepository = orderLineRepository;
    }

    @EventListener
    public void onOrderPlaced(OrderPlacedEvent event) {

        long lines = orderLineRepository.countByOrderId(event.orderId());
        log.info("confirming order {} with {} line(s)", event.reference(), lines);
    }
}

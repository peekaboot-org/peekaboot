package org.peekaboot.testingapp.order;

import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to a placed order inside the placing request, so the POST trace shows the
 * downstream work a real order pipeline hangs off the event: an observed span of its own
 * with a log line on it.
 */
@Component
public class OrderPlacedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacedListener.class);

    @Observed(name = "order.placed", contextualName = "order.placed")
    @EventListener
    public void onOrderPlaced(OrderPlacedEvent event) {
        log.info("order {} placed - notifying fulfilment", event.reference());
    }
}

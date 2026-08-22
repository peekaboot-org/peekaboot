package org.peekaboot.testingapp.order;

import io.micrometer.observation.annotation.Observed;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.peekaboot.testingapp.entity.CustomerOrder;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically re-checks placed orders. Logs a WARN per stale order on purpose, so the
 * Logs tab of a non-HTTP trace has content and the Scheduled Tasks tab has a job whose
 * runs are worth opening.
 *
 * <p>Both {@code name} and {@code contextualName} are set: Micrometer names the span
 * after the annotated method ({@code OrderReconciler#reconcileOrders}) unless
 * {@code contextualName} overrides it, and {@code detectRootActionType()} classifies a
 * trace as {@code SCHEDULED_JOB} from the root span's <em>name</em> containing "job" -
 * {@code name} alone only renames the metric.
 */
@Component
public class OrderReconciler {

    private static final Logger log = LoggerFactory.getLogger(OrderReconciler.class);

    private final OrderRepository orderRepository;


    public OrderReconciler(OrderRepository orderRepository) {

        this.orderRepository = orderRepository;
    }


    @Scheduled(fixedDelay = 2, timeUnit = TimeUnit.MINUTES)
    @Observed(name = "order.reconcile.job", contextualName = "order.reconcile.job")
    public void reconcileOrders() {

        List<CustomerOrder> orders = orderRepository.findAll();
        log.info("reconciling {} orders", orders.size());

        int stale = 0;
        for (CustomerOrder order : orders) {
            if ("PLACED".equals(order.getStatus())) {
                stale++;
                log.warn("order {} is still PLACED and has not been acknowledged", order.getReference());
            }
        }
        log.info("reconciliation finished, {} order(s) awaiting acknowledgement", stale);
    }
}

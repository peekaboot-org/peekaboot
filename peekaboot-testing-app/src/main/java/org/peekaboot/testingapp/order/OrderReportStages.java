package org.peekaboot.testingapp.order;

import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.util.List;
import org.peekaboot.testingapp.entity.OrderLine;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.springframework.stereotype.Component;

/**
 * The three stages behind {@link OrderService#buildReport(long)}, split into their own
 * Spring bean so {@code @Observed} - implemented by a Spring AOP aspect - actually
 * intercepts the calls. An aspect cannot advise self-invocation, so calling these as plain
 * methods on {@code OrderService} would produce no nested spans at all.
 */
@Component
public class OrderReportStages {

    private final OrderLineRepository orderLineRepository;

    public OrderReportStages(OrderLineRepository orderLineRepository) {

        this.orderLineRepository = orderLineRepository;
    }


    @Observed(name = "order.report.load-lines", contextualName = "order.report.load-lines")
    List<OrderLine> loadLines(long orderId) {

        pause(400);
        return orderLineRepository.findByOrderId(orderId);
    }


    @Observed(name = "order.report.price-lines", contextualName = "order.report.price-lines")
    BigDecimal priceLines(List<OrderLine> lines) {

        pause(600);
        return lines.stream()
                .map(line -> line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }


    @Observed(name = "order.report.apply-discounts", contextualName = "order.report.apply-discounts")
    BigDecimal applyDiscounts(BigDecimal total) {

        pause(400);
        return total.multiply(new BigDecimal("0.95"));
    }


    /** Stands in for work this demo does not actually do. */
    private void pause(long millis) {

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while simulating work", e);
        }
    }
}

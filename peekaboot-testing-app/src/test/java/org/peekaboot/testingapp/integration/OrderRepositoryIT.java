package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.peekaboot.testingapp.entity.CustomerOrder;
import org.peekaboot.testingapp.entity.OrderLine;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = TestingApp.class)
@ActiveProfiles("test")
class OrderRepositoryIT {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderLineRepository orderLineRepository;

    @Test
    void orderLinesAreFoundByTheirOrderId() {
        CustomerOrder order = new CustomerOrder();
        order.setReference("PK-9001");
        order.setCustomerId(1L);
        order.setStatus("PLACED");
        order.setPlacedAt(Instant.parse("2026-08-22T10:15:30Z"));
        CustomerOrder saved = orderRepository.save(order);

        OrderLine line = new OrderLine();
        line.setOrderId(saved.getId());
        line.setSku("WIDGET-1");
        line.setQuantity(3);
        line.setUnitPrice(new BigDecimal("19.99"));
        orderLineRepository.save(line);

        List<OrderLine> found = orderLineRepository.findByOrderId(saved.getId());

        assertThat(found).singleElement().satisfies(it -> {
            assertThat(it.getSku()).isEqualTo("WIDGET-1");
            assertThat(it.getQuantity()).isEqualTo(3);
            assertThat(it.getUnitPrice()).isEqualByComparingTo("19.99");
        });
    }
}

package org.peekaboot.testingapp.repository;

import java.util.List;
import org.peekaboot.testingapp.entity.OrderLine;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderLineRepository extends JpaRepository<OrderLine, Long> {

    List<OrderLine> findByOrderId(Long orderId);

    long countByOrderId(Long orderId);
}

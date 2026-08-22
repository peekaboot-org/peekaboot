package org.peekaboot.testingapp.repository;

import org.peekaboot.testingapp.entity.CustomerOrder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {
}

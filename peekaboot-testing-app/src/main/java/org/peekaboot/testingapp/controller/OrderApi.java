package org.peekaboot.testingapp.controller;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import org.peekaboot.testingapp.entity.CustomerOrder;
import org.peekaboot.testingapp.order.NewOrder;
import org.peekaboot.testingapp.order.OrderReport;
import org.peekaboot.testingapp.order.OrderService;
import org.peekaboot.testingapp.order.OrderSummary;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderApi {

    private final OrderService orderService;

    public OrderApi(OrderService orderService) {

        this.orderService = orderService;
    }

    @GetMapping("/api/orders/{id}/report")
    public OrderReport report(@PathVariable("id") long id) {

        return orderService.buildReport(id);
    }

    @PostMapping("/api/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderSummary place(@Valid @RequestBody NewOrder request) {

        CustomerOrder created = orderService.placeOrder(request);
        return new OrderSummary(
                created.getId(),
                created.getReference(),
                created.getStatus(),
                created.getPlacedAt(),
                1,
                new BigDecimal("19.99"),
                "customer #" + created.getCustomerId());
    }
}

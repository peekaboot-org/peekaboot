package org.peekaboot.testingapp.controller;

import org.peekaboot.testingapp.order.OrderReport;
import org.peekaboot.testingapp.order.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
}

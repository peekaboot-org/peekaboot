package org.peekaboot.testingapp.controller;

import org.peekaboot.testingapp.order.OrderService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;


@Controller
public class OrderController {

    private final OrderService orderService;


    public OrderController(OrderService orderService) {

        this.orderService = orderService;
    }


    @GetMapping("/orders")
    public String orders(Model model) {

        model.addAttribute("orders", orderService.listOrders());
        return "orders";
    }
}

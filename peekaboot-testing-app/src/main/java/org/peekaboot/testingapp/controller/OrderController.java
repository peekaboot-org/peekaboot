package org.peekaboot.testingapp.controller;

import org.peekaboot.testingapp.order.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;

    public OrderController(OrderService orderService) {

        this.orderService = orderService;
    }

    @GetMapping("/orders")
    public String orders(Model model) {

        model.addAttribute("orders", orderService.listOrders());
        return "orders";
    }

    /**
     * Always fails. Exists so the Errors bucket and the error badge have something real to
     * render. The throw is served by the error dispatch, which renders Peekaboot's error
     * page carrying the bar for this failed request.
     */
    @GetMapping("/boom")
    public String boom() {

        log.error("order reconciliation gateway is unreachable");
        throw new IllegalStateException("order reconciliation gateway is unreachable");
    }
}

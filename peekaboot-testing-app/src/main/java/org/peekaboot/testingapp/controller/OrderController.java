package org.peekaboot.testingapp.controller;

import org.peekaboot.testingapp.async.EnrichmentService;
import org.peekaboot.testingapp.order.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class OrderController {

    private static final String ENRICHED_REFERENCE = "PK-2001";

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;

    private final EnrichmentService enrichmentService;

    public OrderController(OrderService orderService, EnrichmentService enrichmentService) {

        this.orderService = orderService;
        this.enrichmentService = enrichmentService;
    }

    @GetMapping("/orders")
    public String orders(Model model) {

        model.addAttribute("orders", orderService.listOrders());
        return "orders";
    }

    /**
     * Hands enrichment to a task executor and answers straight away, so the request finishes
     * well before the background work does and the trace shows a hand-off rather than a wait.
     */
    @GetMapping("/orders/enrich")
    @ResponseBody
    public String enrich() {

        enrichmentService.enrich(ENRICHED_REFERENCE);
        return "enrichment of " + ENRICHED_REFERENCE + " dispatched";
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

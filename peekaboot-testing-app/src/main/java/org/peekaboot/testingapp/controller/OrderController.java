package org.peekaboot.testingapp.controller;

import jakarta.validation.Valid;
import org.peekaboot.testingapp.async.EnrichmentService;
import org.peekaboot.testingapp.order.NewOrder;
import org.peekaboot.testingapp.order.OrderService;
import org.peekaboot.testingapp.repository.OrderStatisticsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class OrderController {

    private static final String ENRICHED_REFERENCE = "PK-2001";

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;

    private final EnrichmentService enrichmentService;

    private final OrderStatisticsRepository orderStatistics;

    public OrderController(
            OrderService orderService, EnrichmentService enrichmentService, OrderStatisticsRepository orderStatistics) {

        this.orderService = orderService;
        this.enrichmentService = enrichmentService;
        this.orderStatistics = orderStatistics;
    }

    @GetMapping("/orders")
    public String orders(Model model) {

        model.addAttribute("orders", orderService.listOrders());
        return "orders";
    }

    /**
     * The nav's order forms: the JSON API's placement behind a post-redirect-get. An unknown SKU
     * (400) or too large a quantity (409) renders the error page instead.
     */
    @PostMapping("/orders")
    public String placeOrder(@Valid NewOrder order) {

        orderService.placeOrder(order);
        return "redirect:/orders";
    }

    /** One deliberately slow query, so the trace view has a SLOW_QUERY issue to point at. */
    @GetMapping("/orders/stats")
    public String statistics(Model model) {

        model.addAttribute("statusCounts", orderStatistics.countByStatus());
        return "order-stats";
    }

    /**
     * Hands enrichment to a task executor and answers straight away, so the request finishes
     * well before the background work does and the trace shows a hand-off rather than a wait.
     * {@code ?fail=true} makes the task throw once its work is done, so the trace lands in
     * Errors although the request answered 200. {@code ?notify=true} has the task hand a
     * customer notification to a task of its own, which outlives it.
     */
    @GetMapping("/orders/enrich")
    @ResponseBody
    public String enrich(
            @RequestParam(name = "fail", defaultValue = "false") boolean fail,
            @RequestParam(name = "notify", defaultValue = "false") boolean notify) {

        if (notify) {
            enrichmentService.enrichAndNotify(ENRICHED_REFERENCE);
        } else {
            enrichmentService.enrich(ENRICHED_REFERENCE, fail);
        }
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

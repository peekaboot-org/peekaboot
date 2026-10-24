package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.entity.CustomerOrder;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** What an order costs on each surface that prices one, and whom a placed order names. */
class OrderTotalsIT extends TestingAppITBase {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderLineRepository orderLineRepository;

    @Test
    void theOrdersPageTotalsEachLineAtItsQuantity() {
        CustomerOrder order = seedTwoLinesOfThree();
        try {
            assertThat(rowOf(api.get("/orders"), order.getReference()))
                    .as("two lines of 3 at 19.99")
                    .contains("<td>119.94</td>");
        } finally {
            OrderFixtures.deleteOrder(orderRepository, orderLineRepository, order);
        }
    }

    @Test
    void theReportDiscountsTheSumOfItsLines() {
        CustomerOrder order = seedTwoLinesOfThree();
        try {
            JsonNode report = api.getJson("/api/orders/{id}/report", order.getId());

            assertThat(report.path("total").decimalValue()).as("0.95 of 119.94").isEqualByComparingTo("113.943");
        } finally {
            OrderFixtures.deleteOrder(orderRepository, orderLineRepository, order);
        }
    }

    @Test
    void aPlacedOrderIsPricedFromTheInventoryAndNamesItsCustomerById() {
        ResponseEntity<String> response = OrderFixtures.placeOrder(api, 999L, "WIDGET-1", 3);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode summary = PeekabootApi.readJson(response.getBody());
        try {
            assertThat(summary.path("total").decimalValue())
                    .as("3 at the inventory's 19.99")
                    .isEqualByComparingTo("59.97");
            assertThat(summary.path("customerName").asString("")).isEqualTo("customer #999");
        } finally {
            OrderFixtures.deleteOrder(
                    orderRepository,
                    orderLineRepository,
                    orderRepository.findById(summary.path("id").asLong()).orElseThrow());
        }
    }

    private CustomerOrder seedTwoLinesOfThree() {
        return OrderFixtures.seedOrder(
                orderRepository, orderLineRepository, 999L, "SHIPPED", 3, "PK-TOTALS-", "WIDGET-1", "WIDGET-2");
    }

    /** The orders page's table row for {@code reference}, from its reference cell to the row's end. */
    private static String rowOf(String page, String reference) {
        int start = page.indexOf("<td>" + reference + "</td>");
        assertThat(start).as("a row for %s on the orders page", reference).isNotNegative();
        return page.substring(start, page.indexOf("</tr>", start));
    }
}

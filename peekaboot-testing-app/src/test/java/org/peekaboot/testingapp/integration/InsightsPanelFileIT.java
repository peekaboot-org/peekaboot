package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.peekaboot.testingapp.order.NewOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The app's own panel file, the one a local run reads from the classpath root. The test
 * profile's {@code config-location} replaces that lookup rather than merging with it, so this
 * class points the property back at the app's file.
 */
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "peekaboot.insights.config-location=classpath:peekaboot-insights.yml")
class InsightsPanelFileIT extends TestingAppITBase {

    @Test
    void theAppsPanelFileSwitchesOnEveryOptInPanelAndAddsItsOwn() {
        List<String> panelIds = new ArrayList<>();
        api.getJson("/peekaboot/api/insights/config")
                .path("panels")
                .forEach(panel -> panelIds.add(panel.path("id").asString("")));

        assertThat(panelIds)
                .contains(
                        "thread-states",
                        "hibernate-activity",
                        "executors",
                        "open-files",
                        "tomcat-sessions",
                        "allocation",
                        "orders-placed");
    }

    @Test
    void placingAnOrderCountsOnTheOrdersPlacedTile() {
        double before = ordersPlacedTile().path("value").asDouble();

        ResponseEntity<String> placed = api.restClient()
                .post()
                .uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new NewOrder(1L, "WIDGET-3", 1))
                .retrieve()
                .toEntity(String.class);

        assertThat(placed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ordersPlacedTile().path("value").asDouble()).isEqualTo(before + 1);
    }

    private JsonNode ordersPlacedTile() {
        for (JsonNode tile : api.getJson("/peekaboot/api/insights/config").path("tiles")) {
            if ("orders-placed".equals(tile.path("id").asString(""))) {
                assertThat(tile.path("value").isNumber())
                        .as("the counter exists from startup, so the tile resolves before the first order")
                        .isTrue();
                return tile;
            }
        }
        throw new AssertionError("no orders-placed tile among the configured tiles");
    }
}

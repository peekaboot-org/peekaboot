package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * The Overview stat tiles must carry values the moment the dashboard opens, not only
 * after the collector's first level-0 tick. The levels here are hours long precisely so
 * no tick can fire while this test runs: every tile value the config serves has to come
 * from the read itself (see TileTracker), the situation of a dashboard opened right
 * after startup under the 10s production default.
 */
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "peekaboot.insights.levels[0].interval=1h",
            "peekaboot.insights.levels[0].size=10",
            "peekaboot.insights.levels[1].interval=2h",
            "peekaboot.insights.levels[1].size=10",
            "peekaboot.insights.levels[2].interval=4h",
            "peekaboot.insights.levels[2].size=10"
        })
@ActiveProfiles("test")
class InsightsTilesIT {

    @LocalServerPort
    private int port;

    private PeekabootApi api;

    @BeforeEach
    void connect() {
        api = new PeekabootApi(port);
    }

    @Test
    void everyTileCarriesAValueBeforeTheFirstTick() {
        JsonNode tiles = api.getJson("/peekaboot/api/insights/config").get("tiles");

        for (String id : List.of("started-at", "startup-time", "ready-time", "uptime")) {
            JsonNode tile = tileById(tiles, id);
            assertThat(tile).as("tile %s is configured", id).isNotNull();
            assertThat(tile.get("value").isNumber())
                    .as("tile %s resolves without waiting for a tick, got %s", id, tile.get("value"))
                    .isTrue();
        }
    }

    private static JsonNode tileById(JsonNode tiles, String id) {
        for (JsonNode tile : tiles) {
            if (id.equals(tile.get("id").asText())) {
                return tile;
            }
        }
        return null;
    }
}

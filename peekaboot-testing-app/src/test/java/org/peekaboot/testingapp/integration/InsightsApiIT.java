package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Contract tests for the insights REST/SSE API against the real, auto-configured
 * app - config shape, tick data growing over the ring, the 400 on an invalid level,
 * and an actual "tick" SSE event on the wire. The test profile's insights levels
 * (see application-test.yml) tick every 250ms specifically so these assertions -
 * and the Playwright specs that reuse the same profile - don't need to wait out
 * the 10s production default.
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InsightsApiIT {

    @LocalServerPort
    private int port;

    private PeekabootApi api;
    private RestClient restClient;

    @BeforeEach
    void connect() {
        api = new PeekabootApi(port);
        restClient = api.restClient();
    }

    @Test
    void configServesPanelsAndLevels() {
        JsonNode config = api.getJson("/peekaboot/api/insights/config");

        assertThat(config.get("levels")).hasSize(3);

        List<String> panelIds = new ArrayList<>();
        config.get("panels").forEach(panel -> panelIds.add(panel.get("id").asText()));
        assertThat(panelIds).contains("cpu", "heap", "http-throughput");

        assertThat(config.get("tiles")).isNotEmpty();
    }

    @Test
    void dataReturnsGrowingTickSeries() {
        // level 0 ticks every 250ms in the test profile, so two samples are a moment away
        JsonNode data = await().atMost(Duration.ofSeconds(5))
                .until(
                        () -> api.getJson("/peekaboot/api/insights/data?level=0"),
                        d -> d.get("count").asInt() >= 2);

        // cpu.process (process.cpu.usage) resolves in a real JVM app
        assertThat(data.get("series").get("cpu.process").get("values")).isNotEmpty();
    }

    @Test
    void invalidLevelIsBadRequest() {
        HttpStatusCode status = restClient
                .get()
                .uri("/peekaboot/api/insights/data?level=9")
                .accept(MediaType.APPLICATION_JSON)
                .exchange((req, res) -> res.getStatusCode());

        assertThat(status.value()).isEqualTo(400);
    }

    @Test
    void streamDeliversTickEventWithinFiveSeconds() throws Exception {
        // Closed explicitly (not just the response stream) so the client-side socket
        // drops right away. The server only notices the disconnect on its next
        // heartbeat to this subscriber (InsightsSsePublisher, 15s); context shutdown
        // completes the emitter regardless, so Tomcat is not held at JVM exit.
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/peekaboot/api/insights/stream"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "text/event-stream")
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            assertThat(response.statusCode()).isEqualTo(200);

            // The JDK client has no read timeout, so a stream that stays open but silent
            // would block readLine() indefinitely; the read runs off-thread and the wait
            // is bounded here. Closing the body on the way out unblocks the reader.
            try (InputStream body = response.body()) {
                CompletableFuture<Boolean> sawTick = CompletableFuture.supplyAsync(() -> containsTickEvent(body));
                assertThat(sawTick.get(5, TimeUnit.SECONDS))
                        .as("received a tick SSE event")
                        .isTrue();
            } catch (TimeoutException silentStream) {
                fail("no tick SSE event within 5 seconds");
            }
        }
    }

    private static boolean containsTickEvent(InputStream body) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:") && line.contains("tick")) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

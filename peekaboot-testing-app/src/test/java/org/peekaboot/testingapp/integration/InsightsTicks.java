package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The insights stream's tick events, read off the wire as they arrive. The collector sends a tick
 * once its sample is in the rings, so a test that has seen one reads the data endpoint knowing the
 * sample is served. A subscriber is sent only the ticks taken after it connected.
 */
final class InsightsTicks implements AutoCloseable {

    private final HttpClient client;
    private final InputStream body;
    private final BlockingQueue<JsonNode> ticks = new LinkedBlockingQueue<>();

    private InsightsTicks(HttpClient client, InputStream body) {
        this.client = client;
        this.body = body;
        // the JDK client has no read timeout, so the read runs off the test thread and next() bounds the wait
        Thread.ofVirtual().name("insights-ticks").start(this::read);
    }

    /** Subscribes to the app on {@code port}. The answer's headers arrive once the subscriber is registered. */
    static InsightsTicks on(int port) {
        HttpClient client = HttpClient.newHttpClient();
        InsightsTicks ticks = null;
        try {
            HttpResponse<InputStream> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/peekaboot/api/insights/stream"))
                            .timeout(PeekabootApi.ASYNC_TIMEOUT)
                            .header("Accept", "text/event-stream")
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            ticks = new InsightsTicks(client, response.body());
            return ticks;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while subscribing to the insights stream", e);
        } finally {
            if (ticks == null) {
                client.shutdownNow();
            }
        }
    }

    /** The next tick's data: {@code {"epochMs":..., "values":{...}}}. */
    JsonNode next() {
        return nextMatching(any -> true);
    }

    /** The next tick {@code tick} accepts; the ones before it are skipped. One bound covers the whole wait. */
    JsonNode nextMatching(Predicate<JsonNode> tick) {
        String bound = PeekabootApi.ASYNC_TIMEOUT.toSeconds() + "s";
        long deadline = System.nanoTime() + PeekabootApi.ASYNC_TIMEOUT.toNanos();
        JsonNode skipped = null;
        while (true) {
            JsonNode next = takeUntil(deadline);
            if (next == null) {
                throw new AssertionError(
                        skipped == null
                                ? "no insights tick within " + bound
                                : "no insights tick matched within " + bound + "; the last one: " + skipped);
            }
            if (tick.test(next)) {
                return next;
            }
            skipped = next;
        }
    }

    /** Closes the body, which ends the read, and drops the socket now instead of when the client is collected. */
    @Override
    public void close() {
        try {
            body.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            client.shutdownNow();
        }
    }

    private @Nullable JsonNode takeUntil(long deadline) {
        try {
            return ticks.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for an insights tick", e);
        }
    }

    private void read() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String event = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    event = line.substring("event:".length()).strip();
                } else if (line.startsWith("data:") && "tick".equals(event)) {
                    ticks.add(PeekabootApi.readJson(line.substring("data:".length())));
                }
            }
        } catch (IOException closed) {
            // close() ends the read by closing the body under it; a stream that broke shows as a missing tick
        }
    }
}

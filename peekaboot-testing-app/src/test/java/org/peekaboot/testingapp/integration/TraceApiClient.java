package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Drives the testing app and reads back what Peekaboot captured, through the same public
 * API the dashboard uses.
 *
 * <p>Spans reach the {@code TraceStore} asynchronously via the OTel BatchSpanProcessor
 * (50ms in the test profile), so every read is a poll with a deadline rather than a
 * single fetch.
 */
class TraceApiClient {

    /** The toolbar embeds its payload as JSON in a {@code <script id="peekaboot-toolbar-data">} tag. */
    private static final Pattern TOOLBAR_TRACE_ID = Pattern.compile("\"traceId\"\\s*:\\s*\"([0-9a-fA-F]+)\"");

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final long POLL_INTERVAL_MS = 50;

    private final PeekabootApi api;
    private final RestClient restClient;

    TraceApiClient(int port) {
        this.api = new PeekabootApi(port);
        this.restClient = api.restClient();
    }

    RestClient restClient() {
        return restClient;
    }

    String triggerAndCaptureTraceId(String path) {
        String html = restClient
                .get()
                .uri(path)
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .body(String.class);

        Matcher matcher = TOOLBAR_TRACE_ID.matcher(html == null ? "" : html);
        assertThat(matcher.find())
                .as(
                        "dev toolbar must embed a trace id for %s - without one the request was "
                                + "never traced and any capture assertion would be meaningless",
                        path)
                .isTrue();
        return matcher.group(1);
    }

    void trigger(String path) {
        try {
            restClient.get().uri(path).accept(MediaType.ALL).retrieve().toBodilessEntity();
        } catch (RuntimeException expectedForErrorPaths) {
            // /boom answers 500 by design; the trace is what matters, not the response
        }
    }

    JsonNode awaitTrace(String traceId) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        JsonNode lastSeen = null;
        int previousSpanCount = -1;
        while (System.nanoTime() < deadline) {
            JsonNode trace = fetchOrNull("/peekaboot/api/traces/" + traceId + "/insights");
            if (trace != null) {
                lastSeen = trace;
                int spanCount =
                        trace.path("summary").path("spans").path("count").asInt();
                // A trace with an outbound call is exported in more than one BatchSpanProcessor
                // flush, so a single non-zero read can be a partial snapshot. Requiring the count
                // to hold steady across two consecutive polls confirms the flushes have caught up.
                //
                // This is a heuristic, not a completeness guarantee: the poll interval (50ms)
                // equals the BatchSpanProcessor schedule-delay in application-test.yml, so a
                // trace whose spans land in three or more separate flushes could look stable
                // across exactly two polls and still be a partial tree. Two polls is empirically
                // sufficient for today's trace shapes; if this ever flakes again, widen the
                // stability window rather than assume the current shape generalises.
                if (spanCount > 0 && spanCount == previousSpanCount) {
                    return trace;
                }
                previousSpanCount = spanCount;
            }
            sleepBriefly();
        }
        throw new AssertionError("trace " + traceId + " never had a stable exported span count within " + TIMEOUT
                + "; last response: " + lastSeen);
    }

    JsonNode awaitTraceInBucket(String bucket, String rootOperationFragment) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        List<String> seen = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            JsonNode response = api.getJson("/peekaboot/api/traces/insights?bucket=" + bucket);
            seen.clear();
            for (JsonNode trace : response.path("traces")) {
                String rootOperation = trace.path("rootOperation").asString("");
                seen.add(rootOperation);
                if (rootOperation.contains(rootOperationFragment)) {
                    return trace;
                }
            }
            sleepBriefly();
        }
        throw new AssertionError("no trace whose rootOperation contains '" + rootOperationFragment
                + "' reached the " + bucket + " bucket within " + TIMEOUT
                + "; the bucket held: " + seen);
    }

    private JsonNode fetchOrNull(String uri) {
        try {
            return api.getJson(uri);
        } catch (HttpClientErrorException.NotFound notYetAvailable) {
            // single-trace endpoint returns 404 until the first span for the trace is exported
            return null;
        }
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for trace export", e);
        }
    }
}

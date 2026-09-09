package org.peekaboot.testingapp.integration;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.awaitility.core.ConditionTimeoutException;
import org.peekaboot.backend.domain.trace.RootActionType;
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
 * single fetch, and a caller names the fact it is about to assert on as the condition.
 */
class TraceApiClient {

    /**
     * The request's own root span has reached the store. Only a SERVER-kind root classifies
     * HTTP_REQUEST, the root is the last span of a request to end, and the exporter hands
     * spans over in the order they ended, so every span that ended before it is there too.
     * Logs need no wait of their own: they are captured synchronously during the request.
     */
    static final Predicate<JsonNode> ROOT_SPAN_EXPORTED = trace -> RootActionType.HTTP_REQUEST
            .name()
            .equals(trace.path("rootActionType").asString(""));

    /** The toolbar embeds its payload as JSON in a {@code <script id="peekaboot-toolbar-data">} tag. */
    private static final Pattern TOOLBAR_TRACE_ID = Pattern.compile("\"traceId\"\\s*:\\s*\"([0-9a-fA-F]+)\"");

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

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
        if (!matcher.find()) {
            throw new AssertionError("dev toolbar must embed a trace id for " + path + " - without one the request "
                    + "was never traced and any capture assertion would be meaningless");
        }
        return matcher.group(1);
    }

    void trigger(String path) {
        try {
            restClient.get().uri(path).accept(MediaType.ALL).retrieve().toBodilessEntity();
        } catch (RuntimeException expectedForErrorPaths) {
            // /boom answers 500 by design; the trace is what matters, not the response
        }
    }

    /**
     * Polls the trace's own insights endpoint until {@code ready} holds and returns the trace
     * as served at that moment. A 404 counts as "not yet": the endpoint answers nothing until
     * the first span is exported. The predicate is the assertion's precondition
     * ({@link #ROOT_SPAN_EXPORTED} for most), so a test asserts on what it waited for rather
     * than on whatever had arrived.
     */
    JsonNode awaitTrace(String traceId, Predicate<JsonNode> ready) {
        String uri = "/peekaboot/api/traces/" + traceId + "/insights";
        AtomicReference<JsonNode> lastSeen = new AtomicReference<>();
        try {
            return await().atMost(TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .until(
                            () -> {
                                JsonNode trace = fetchOrNull(uri);
                                lastSeen.set(trace);
                                return trace;
                            },
                            trace -> trace != null && ready.test(trace));
        } catch (ConditionTimeoutException e) {
            throw new AssertionError(
                    "trace " + traceId + " never became ready within " + TIMEOUT + "; last response: " + lastSeen.get(),
                    e);
        }
    }

    JsonNode awaitTraceInBucket(String bucket, String rootOperationFragment) {
        return awaitListedTrace(
                "bucket=" + bucket,
                trace -> trace.path("rootOperation").asString("").contains(rootOperationFragment),
                "a trace whose rootOperation contains '" + rootOperationFragment + "' in the " + bucket + " bucket");
    }

    /**
     * The first listed {@code type} trace that {@code match} accepts. The predicate is not
     * optional decoration: the listing is shared with every other test exercising the same
     * application, so the type alone names a trace some other actor produced just as readily
     * as the one the caller is asserting about.
     */
    JsonNode awaitTraceOfType(RootActionType type, Predicate<JsonNode> match) {
        return awaitListedTrace("rootActionType=" + type.name(), match, "a " + type + " trace");
    }

    /**
     * Polls the listing endpoint with {@code query} until a listed trace satisfies
     * {@code match}. The listing leaves out a trace whose root span has not arrived, so a
     * listed match already carries its spans.
     */
    private JsonNode awaitListedTrace(String query, Predicate<JsonNode> match, String description) {
        String uri = "/peekaboot/api/traces/insights?" + query;
        List<String> seen = new ArrayList<>();
        try {
            return await().atMost(TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .until(
                            () -> {
                                seen.clear();
                                for (JsonNode trace : api.getJson(uri).path("traces")) {
                                    seen.add(trace.path("rootOperation").asString(""));
                                    if (match.test(trace)) {
                                        return trace;
                                    }
                                }
                                return null;
                            },
                            trace -> trace != null);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError(
                    description + " was not listed within " + TIMEOUT + "; the listing held: " + seen, e);
        }
    }

    private JsonNode fetchOrNull(String uri) {
        try {
            return api.getJson(uri);
        } catch (HttpClientErrorException.NotFound notYetAvailable) {
            // single-trace endpoint returns 404 until the first span for the trace is exported
            return null;
        }
    }
}

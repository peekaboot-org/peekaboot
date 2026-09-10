package org.peekaboot.testingapp.integration;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.awaitility.core.ConditionTimeoutException;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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

    /** The trace carries at least one captured log; see {@link #awaitTrace(Supplier, Predicate)}. */
    static final Predicate<JsonNode> LOG_CAPTURED = trace -> !trace.path("logs").isEmpty();

    private static final Logger log = LoggerFactory.getLogger(TraceApiClient.class);

    /** RequestCaptureFilter answers every captured request with its trace id in Server-Timing. */
    private static final Pattern SERVER_TIMING_TRACE_ID = Pattern.compile("trace;desc=\"00-([0-9a-f]+)-");

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
    private static final int RETRIGGER_ATTEMPTS = 5;

    private final PeekabootApi api;
    private final RestClient restClient;

    TraceApiClient(int port) {
        this.api = new PeekabootApi(port);
        this.restClient = api.restClient();
    }

    RestClient restClient() {
        return restClient;
    }

    /**
     * Requests {@code path} and returns the trace id its response named in Server-Timing,
     * for any content type and any status: /boom answers 500 by design and is captured all
     * the same. A response without the header was never captured, which no later read could
     * tell from a capture that lost everything, so that fails here.
     */
    String get(String path) {
        return traceIdOf(api.headersOf(path));
    }

    /** The trace id a captured response names in Server-Timing, for a request made some other way. */
    static String traceIdOf(HttpHeaders headers) {
        String serverTiming = headers.getFirst("Server-Timing");
        Matcher matcher = SERVER_TIMING_TRACE_ID.matcher(serverTiming == null ? "" : serverTiming);
        if (!matcher.find()) {
            throw new AssertionError(
                    "Server-Timing must carry the trace id, or the request was never captured: " + serverTiming);
        }
        return matcher.group(1);
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

    /**
     * Fires {@code request}, which answers with its trace id, waits for that trace's root
     * span, and fires again while the stored trace does not satisfy {@code captured}. For a
     * fact that is final once the root span is stored yet can be lost for good: a log written
     * while a concurrent context boot had detached the capture appender (see
     * {@code LogbackCaptureReinstaller}) is gone, and only a fresh request can produce one.
     */
    JsonNode awaitTrace(Supplier<String> request, Predicate<JsonNode> captured) {
        JsonNode trace = null;
        for (int attempt = 0; attempt < RETRIGGER_ATTEMPTS; attempt++) {
            String traceId = request.get();
            trace = awaitTrace(traceId, ROOT_SPAN_EXPORTED);
            if (captured.test(trace)) {
                return trace;
            }
            log.info("trace {} lacks what its request should have captured, requesting again", traceId);
        }
        throw new AssertionError(RETRIGGER_ATTEMPTS
                + " requests produced no trace carrying what they should have captured; last response: " + trace);
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
        // written on Awaitility's poll thread, read on the test thread once the wait gave up
        AtomicReference<List<String>> lastListing = new AtomicReference<>(List.of());
        try {
            return await().atMost(TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .until(
                            () -> {
                                List<String> listed = new ArrayList<>();
                                for (JsonNode trace : api.getJson(uri).path("traces")) {
                                    listed.add(trace.path("rootOperation").asString(""));
                                    if (match.test(trace)) {
                                        return trace;
                                    }
                                }
                                lastListing.set(listed);
                                return null;
                            },
                            trace -> trace != null);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError(
                    description + " was not listed within " + TIMEOUT + "; the listing held: " + lastListing.get(), e);
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

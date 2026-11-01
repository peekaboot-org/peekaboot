package org.peekaboot.testingapp.integration;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import org.awaitility.core.ConditionTimeoutException;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
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
            .equals(trace.path("summary").path("rootActionType").asString(""));

    /** The trace's root has arrived: {@code complete} is the API's own word for it, so nothing is inferred from the tree. */
    static final Predicate<JsonNode> COMPLETE =
            trace -> trace.path("summary").path("complete").asBoolean(false);

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private final PeekabootApi api;

    TraceApiClient(int port) {
        this.api = new PeekabootApi(port);
    }

    /** The plain API client underneath, for a request whose answer is not a trace. */
    PeekabootApi api() {
        return api;
    }

    /**
     * Requests {@code path} and returns the trace id its response named in Server-Timing,
     * for any content type and any status: /boom answers 500 by design and is captured all
     * the same.
     */
    String get(String path) {
        return traceIdOf(api.headersOf(path));
    }

    /** Requests {@code path} and waits for its trace to complete, for a test that reads the request or the spans. */
    String getAndAwaitComplete(String path) {
        String traceId = get(path);
        awaitTrace(traceId, COMPLETE);
        return traceId;
    }

    /** The trace carries an ERROR log whose message is exactly {@code message}. */
    static Predicate<JsonNode> errorLogged(String message) {
        return trace -> trace.path("data")
                .path("logs")
                .valueStream()
                .anyMatch(log -> "ERROR".equals(log.path("level").asString(""))
                        && message.equals(log.path("message").asString("")));
    }

    /** The trace id a captured response names in Server-Timing, for a request made some other way. */
    static String traceIdOf(HttpHeaders headers) {
        return ServerTiming.traceId(headers.getFirst("Server-Timing"));
    }

    /**
     * Polls the trace's own endpoint until {@code ready} holds and returns the trace
     * as served at that moment. A 404 counts as "not yet": the endpoint knows a trace only once
     * its first span, log or request is stored. The predicate is the assertion's precondition
     * ({@link #ROOT_SPAN_EXPORTED} for most), so a test asserts on what it waited for rather
     * than on whatever had arrived.
     */
    JsonNode awaitTrace(String traceId, Predicate<JsonNode> ready) {
        String uri = "/peekaboot/api/traces/" + traceId;
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
     * Waits for the caller's own trace to be listed in {@code bucket}. The bucket is the
     * assertion: a trace the backend classified elsewhere never arrives here. Pinned by id
     * rather than by a name fragment, since every other class fills the same listing.
     */
    JsonNode awaitTraceInBucket(String bucket, String traceId) {
        return awaitListedTrace(
                "bucket=" + bucket,
                trace -> traceId.equals(traceId(trace)),
                TraceApiClient::rootOperation,
                "trace " + traceId + " in the " + bucket + " bucket");
    }

    /**
     * Waits for a trace of {@code rootOperation} that was not listed when this call started.
     * The fallback for work that answers no request and runs through no scheduled-task
     * observation, so nothing hands the caller an id: a direct method call.
     */
    JsonNode awaitTraceAppearing(String rootOperation, Runnable trigger) {
        Predicate<JsonNode> named = trace -> rootOperation.equals(rootOperation(trace));
        Set<String> listedBefore = new HashSet<>();
        for (JsonNode trace :
                api.getJson("/peekaboot/api/traces?view=full&bucket=all").path("traces")) {
            if (named.test(trace)) {
                listedBefore.add(traceId(trace));
            }
        }
        trigger.run();
        return awaitListedTrace(
                "bucket=all",
                trace -> named.test(trace) && !listedBefore.contains(traceId(trace)),
                TraceApiClient::rootOperation,
                "a new '" + rootOperation + "' trace");
    }

    /**
     * The first listed {@code type} trace that {@code match} accepts. The predicate is not
     * optional decoration: the listing is shared with every other test exercising the same
     * application, so the type alone names a trace some other actor produced just as readily
     * as the one the caller is asserting about. {@code label} names each listed trace in the
     * failure, so a match that never comes shows what the listing held instead.
     */
    JsonNode awaitTraceOfType(RootActionType type, Predicate<JsonNode> match, Function<JsonNode, String> label) {
        return awaitListedTrace("rootActionType=" + type.name(), match, label, "a " + type + " trace");
    }

    /**
     * The traces the listing endpoint returns for {@code query} ({@code rootActionType=*},
     * {@code bucket=errors}), every one of them; an empty query is the dashboard's default listing.
     */
    List<JsonNode> listedTraces(String query) {
        String uri = "/peekaboot/api/traces?view=full&limit=10000" + (query.isEmpty() ? "" : "&" + query);
        List<JsonNode> listed = new ArrayList<>();
        api.getJson(uri).path("traces").forEach(listed::add);
        return listed;
    }

    List<String> listedTraceIds(String query) {
        return listedTraces(query).stream().map(TraceApiClient::traceId).toList();
    }

    /**
     * Polls the listing endpoint with {@code query} until a listed trace satisfies
     * {@code match}. A trace lists once its request or a log is stored, before any span, with
     * {@code complete} false. Spans that arrived without their root hold back only the trace's own
     * row; its async tasks' rows list sooner. A caller that asserts on spans waits for them with
     * {@link #awaitTrace} first.
     */
    private JsonNode awaitListedTrace(
            String query, Predicate<JsonNode> match, Function<JsonNode, String> label, String description) {
        String uri = "/peekaboot/api/traces?view=full&" + query;
        // written on Awaitility's poll thread, read on the test thread once the wait gave up
        AtomicReference<List<String>> lastListing = new AtomicReference<>(List.of());
        try {
            return await().atMost(TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .until(
                            () -> {
                                List<String> listed = new ArrayList<>();
                                for (JsonNode trace : api.getJson(uri).path("traces")) {
                                    listed.add(label.apply(trace));
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

    static String rootOperation(JsonNode trace) {
        return trace.path("summary").path("rootOperation").asString("");
    }

    static String traceId(JsonNode trace) {
        return trace.path("summary").path("traceId").asString("");
    }

    /** The row of {@code listing} for {@code traceId}; fails naming the listing when there is none. */
    static JsonNode rowOf(JsonNode listing, String traceId) {
        return listing.path("traces")
                .valueStream()
                .filter(row -> traceId.equals(traceId(row)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("trace " + traceId + " is not listed in " + listing));
    }

    private JsonNode fetchOrNull(String uri) {
        try {
            return api.getJson(uri);
        } catch (HttpClientErrorException.NotFound notYetAvailable) {
            // single-trace endpoint returns 404 until anything for the trace is stored
            return null;
        }
    }
}

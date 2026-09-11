package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * {@code /people} returns {@code forward:/persons}, so DispatcherServlet runs a second
 * dispatch inside the first one's view rendering and calls every interceptor callback
 * twice against the same request.
 *
 * <p>What that used to cost is invisible in the forwarding request's own response: the
 * outer view observation was overwritten by the inner dispatch and never stopped, so its
 * span was never exported and its scope stayed open on the request thread - which left the
 * trace context attached to a pooled thread, and every later request that thread served
 * was captured as part of this trace. Both symptoms are asserted here, on the shape of
 * what Peekaboot captured.
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NestedDispatchTraceCaptureIT {

    @LocalServerPort
    private int port;

    private TraceApiClient traces;

    @BeforeEach
    void connect() {
        traces = new TraceApiClient(port);
    }

    /**
     * Tomcat suspends a wrapped response once a forward returns, which drops everything
     * written afterwards - and the toolbar filter writes the page it has injected exactly
     * then. The symptom is an empty 200, so this asserts the page arrives at all before the
     * tests below assert what was captured from it.
     */
    @Test
    void forwardingRequestStillServesItsPage() {
        String html = traces.restClient()
                .get()
                .uri("/people")
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .body(String.class);

        assertThat(html).contains("</table>").contains("peekaboot-toolbar-data");
    }

    @Test
    void forwardingRequestCapturesBothDispatches() {
        JsonNode trace = traces.awaitTrace(traces.get("/people"), TraceApiClient.ROOT_SPAN_EXPORTED);

        assertThat(spanNames(trace, "spring.handler"))
                .as("the forwarding controller and the one it forwards to each get a span")
                .containsExactlyInAnyOrder("PersonController.people", "PersonController.persons");
        assertThat(spanNames(trace, "spring.view.render"))
                .as("an outer view span left unstopped is never exported - it would be missing here")
                .containsExactlyInAnyOrder("forward:/persons", "persons");
    }

    @Test
    void forwardingRequestKeepsTheForwardedWorkUnderTheForward() {
        JsonNode trace = traces.awaitTrace(traces.get("/people"), TraceApiClient.ROOT_SPAN_EXPORTED);

        JsonNode forwardSpan = findSpan(trace.path("rootSpan"), "spring.view.render", "forward:/persons");
        assertThat(forwardSpan)
                .as("the forward's view span must be in the tree, not dropped")
                .isNotNull();
        assertThat(findSpan(forwardSpan, "spring.handler", "PersonController.persons"))
                .as("the forwarded dispatch belongs under the forward that caused it, not "
                        + "re-parented to the root as an orphan")
                .isNotNull();
    }

    @Test
    void forwardingRequestDoesNotAbsorbTheNextRequestOnItsThread() {
        String forwardTraceId = traces.get("/people");

        // The leak pinned the context to one pooled thread, so the requests that inherited it
        // were whichever ones that thread happened to serve next. A single follow-up request is
        // not enough to be sure of hitting it; a handful on the same client is.
        List<String> followUpTraceIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            followUpTraceIds.add(traces.get("/persons"));
        }

        assertThat(followUpTraceIds)
                .as("every request is its own trace; one that reuses the forward's trace id "
                        + "inherited a context the forward left behind")
                .doesNotContain(forwardTraceId);

        // Named after /persons, not /people: the server span takes the mapping the request
        // ended up on, and the forward is what decides that.
        JsonNode trace = traces.awaitTrace(forwardTraceId, TraceApiClient.ROOT_SPAN_EXPORTED);
        assertThat(serverSpanNames(trace))
                .as("a trace holds the one request that started it")
                .containsExactly("http get /persons");
    }

    /** Every {@code view.name}/{@code handler.name} tag carried by a span of this name, tree-wide. */
    private static List<String> spanNames(JsonNode trace, String spanName) {
        return collect(
                        trace.path("rootSpan"),
                        span -> spanName.equals(span.path("name").asString("")))
                .stream()
                .map(NestedDispatchTraceCaptureIT::describedName)
                .toList();
    }

    /** The inbound requests the trace holds - more than one means two requests were glued together. */
    private static List<String> serverSpanNames(JsonNode trace) {
        return collect(
                        trace.path("rootSpan"),
                        span -> "SERVER".equals(span.path("kind").asString("")))
                .stream()
                .map(span -> span.path("name").asString(""))
                .toList();
    }

    private static List<JsonNode> collect(JsonNode span, Predicate<JsonNode> match) {
        List<JsonNode> found = new ArrayList<>();
        if (match.test(span)) {
            found.add(span);
        }
        for (JsonNode child : span.path("children")) {
            found.addAll(collect(child, match));
        }
        return found;
    }

    private static JsonNode findSpan(JsonNode span, String spanName, String describedName) {
        if (spanName.equals(span.path("name").asString("")) && describedName.equals(describedName(span))) {
            return span;
        }
        for (JsonNode child : span.path("children")) {
            JsonNode found = findSpan(child, spanName, describedName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * What the span is about: the handler or the view it was opened for. Both tags are
     * high-cardinality, so they sit on the span rather than in its name.
     */
    private static String describedName(JsonNode span) {
        JsonNode tags = span.path("tags");
        return tags.has("handler.name")
                ? tags.path("handler.name").asString("")
                : tags.path("view.name").asString("");
    }
}

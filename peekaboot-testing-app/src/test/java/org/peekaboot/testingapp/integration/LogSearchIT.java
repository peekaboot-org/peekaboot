package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.peekaboot.fixtures.QueryFixtureController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The log search over real HTTP. Every Spring context in this JVM captures every line, so the
 * untraced ring also holds other tests' lines and other contexts' startup lines. Each test writes
 * lines carrying a fresh tag, searches for that tag, and counts only those lines.
 */
class LogSearchIT extends TestingAppITBase {

    private static final Logger log = LoggerFactory.getLogger(LogSearchIT.class);

    private final String tag = UUID.randomUUID().toString();

    /** On the test's own thread, which no trace is running on. */
    private void logUntraced(String what) {
        log.info("log search {} {}", tag, what);
    }

    private String untracedMessage(String what) {
        return "log search " + tag + " " + what;
    }

    private String fixtureMessage(int status) {
        return "query fixture " + tag + " answered " + status;
    }

    /** Waits for the root, so the line's span id resolves against every span the trace will have. */
    private String request(int status) {
        return traces.getAndAwaitComplete(QueryFixtureController.path(tag, status));
    }

    /** {@code query} names the extra parameters with {@code {placeholders}} filled from {@code values}, in order. */
    private JsonNode search(String query, Object... values) {
        List<Object> variables = new ArrayList<>();
        variables.add(tag);
        variables.addAll(Arrays.asList(values));
        return api.getJson("/peekaboot/api/logs?q={q}" + query, variables.toArray());
    }

    private static List<String> messages(JsonNode page) {
        return page.path("logs")
                .valueStream()
                .map(line -> line.path("message").asString())
                .toList();
    }

    @Test
    void anUntracedLineIsFoundWithItsLoggerAndThread() {
        logUntraced("startup");

        JsonNode found = search("").path("logs");

        assertThat(found.size()).isEqualTo(1);
        JsonNode line = found.get(0);
        assertThat(line.path("message").asString()).isEqualTo(untracedMessage("startup"));
        assertThat(line.path("traceId").isNull())
                .as("traceId is null, not absent")
                .isTrue();
        assertThat(line.path("spanId").isNull())
                .as("spanId is null, not absent")
                .isTrue();
        assertThat(line.path("logger").asString()).isEqualTo(LogSearchIT.class.getName());
        assertThat(line.path("thread").asString())
                .isEqualTo(Thread.currentThread().getName());
        assertThat(line.path("level").asString()).isEqualTo("INFO");
    }

    @Test
    void aTracedLineNamesItsTraceAndSpan() {
        String traceId = request(200);
        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.COMPLETE);

        JsonNode found = search("").path("logs");

        assertThat(found.size()).isEqualTo(1);
        JsonNode line = found.get(0);
        assertThat(line.path("message").asString()).isEqualTo(fixtureMessage(200));
        assertThat(line.path("traceId").asString()).isEqualTo(traceId);
        assertThat(SpanTree.spanWithId(trace, line.path("spanId").asString()).isMissingNode())
                .as("the line's span is one of its trace's")
                .isFalse();
        assertThat(line.path("logger").asString()).isEqualTo(QueryFixtureController.class.getName());
        assertThat(line.path("thread").asString()).matches("http-nio-.+-exec-\\d+");
    }

    /** Traced and untraced lines interleave in the order they were written. */
    @Test
    void listsTracedAndUntracedLinesNewestFirst() {
        logUntraced("first");
        request(200);
        logUntraced("last");

        assertThat(messages(search("")))
                .containsExactly(untracedMessage("last"), fixtureMessage(200), untracedMessage("first"));
    }

    /** A line written between two pages lists above the cursor, so no page repeats a line or skips one. */
    @Test
    void pagesThroughEveryLineOnceWhileNewOnesArrive() {
        for (int i = 1; i <= 5; i++) {
            logUntraced("line" + i);
        }

        JsonNode first = search("&limit=2");
        logUntraced("line6");
        JsonNode second = search("&limit=2&cursor={cursor}", first.path("next").asString());
        JsonNode third = search("&limit=2&cursor={cursor}", second.path("next").asString());

        assertThat(messages(first)).containsExactly(untracedMessage("line5"), untracedMessage("line4"));
        assertThat(messages(second)).containsExactly(untracedMessage("line3"), untracedMessage("line2"));
        assertThat(messages(third)).containsExactly(untracedMessage("line1"));
        assertThat(third.path("next").isNull()).as("next is null, not absent").isTrue();
    }

    @Test
    void keepsTheLevelAndEveryOneAbove() {
        logUntraced("info");
        log.warn("log search {} {}", tag, "warn");
        log.error("log search {} {}", tag, "error");

        assertThat(messages(search("&level={level}", "warn")))
                .containsExactly(untracedMessage("error"), untracedMessage("warn"));
    }

    /** {@code q} reads the masked line, so the secret finds nothing and the mask finds the line. */
    @Test
    void neverFindsAMaskedSecret() {
        log.info("log search {} calling https://agent:hunter2secret@partner.example", tag);

        List<String> bySecret = messages(api.getJson("/peekaboot/api/logs?q={q}", "hunter2secret"));
        assertThat(bySecret).noneMatch(message -> message.contains(tag));
        assertThat(messages(search(""))).containsExactly(untracedMessage("calling https://******@partner.example"));
    }

    @Test
    void filtersByTheStartOfTheLoggersName() {
        logUntraced("test thread");
        request(200);

        assertThat(messages(search("&logger={logger}", "org.peekaboot.fixtures")))
                .containsExactly(fixtureMessage(200));
        assertThat(messages(search("&logger={logger}", "org.peekaboot.testingapp.integration.LogSearch")))
                .containsExactly(untracedMessage("test thread"));
    }

    @Test
    void searchesOneTrace() {
        String ok = request(200);
        request(404);
        logUntraced("between");

        assertThat(messages(search("&traceId={traceId}", ok))).containsExactly(fixtureMessage(200));
    }

    /** The line's own timestamp is inside a window that starts at it and outside one that ends at it. */
    @Test
    void filtersByTimestampHalfOpen() {
        logUntraced("stamped");
        String timestamp = search("").path("logs").get(0).path("timestamp").asString();

        assertThat(messages(search("&since={since}", timestamp))).containsExactly(untracedMessage("stamped"));
        assertThat(messages(search("&until={until}", timestamp))).isEmpty();
    }
}

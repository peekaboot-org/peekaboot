package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.tracing.Span;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.event.SpanDataEvent;
import org.peekaboot.backend.tracing.store.TraceData;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.peekaboot.testingapp.TestingApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * Traces survive a restart. One application writes them and closes; a second one on the same storage
 * directory serves them again, read through the HTTP API wherever it still runs. The applications are
 * this class's own, so their listings hold only its requests, and its log lines carry a tag no other
 * class writes.
 */
// Boots its own applications outside spring-test's context-load lock, like AutoSecuredDashboardIT.
@Isolated
class TracePersistenceIT {

    private static final Logger log = LoggerFactory.getLogger(TracePersistenceIT.class);
    private static final String HTTP_ROWS = "/peekaboot/api/traces?rootActionType=HTTP_REQUEST&limit=1";

    private final String tag = "restart " + UUID.randomUUID();
    private final String cutOffTraceId = UUID.randomUUID().toString().replace("-", "");
    // a trace with spans but no root never lists, so the one the listing shows has logged a line and nothing more
    private final String listedCutOffTraceId = UUID.randomUUID().toString().replace("-", "");

    @TempDir
    Path storageDir;

    private static ConfigurableApplicationContext boot(Path dir) {
        return new SpringApplicationBuilder(TestingApp.class)
                .profiles("test")
                .run("--server.port=0", "--peekaboot.storage.enabled=true", "--peekaboot.storage.dir=" + dir);
    }

    /**
     * Spans wait in the exporter's batch until the tracer provider is destroyed: no schedule, and a batch and
     * queue far above what one request makes, so none leaves early and none is dropped. {@code listener}
     * hears every event.
     */
    private static ConfigurableApplicationContext bootHoldingSpans(
            Path dir, ApplicationListener<ApplicationEvent> listener) {
        return new SpringApplicationBuilder(TestingApp.class)
                .profiles("test")
                .listeners(listener)
                .run(
                        "--server.port=0",
                        "--peekaboot.storage.enabled=true",
                        "--peekaboot.storage.dir=" + dir,
                        "--management.opentelemetry.tracing.export.schedule-delay=1h",
                        "--management.opentelemetry.tracing.export.max-batch-size=4096",
                        "--management.opentelemetry.tracing.export.max-queue-size=4096");
    }

    private static void collectSpanIds(JsonNode span, List<String> ids) {
        ids.add(span.path("spanId").asString(""));
        span.path("children").forEach(child -> collectSpanIds(child, ids));
    }

    private static TraceApiClient tracesOf(ConfigurableApplicationContext app) {
        return new TraceApiClient(
                ((WebServerApplicationContext) app).getWebServer().getPort());
    }

    private static String outcome(JsonNode trace) {
        return trace.path("summary").path("outcome").asString("");
    }

    private static String run(JsonNode trace) {
        return trace.path("summary").path("run").asString("");
    }

    private List<String> taggedMessages(PeekabootApi api) {
        List<String> messages = new ArrayList<>();
        api.getJson("/peekaboot/api/logs?q={q}", tag)
                .path("logs")
                .forEach(line -> messages.add(line.path("message").asString("") + "|"
                        + line.path("traceId").isNull()));
        return messages;
    }

    /** Follows {@code next} from the first page, one row a page, for at most ten pages. */
    private static List<String> pagedTraceIds(PeekabootApi api) {
        List<String> ids = new ArrayList<>();
        JsonNode page = api.getJson(HTTP_ROWS);
        for (int i = 0; i < 10; i++) {
            page.path("traces")
                    .forEach(row -> ids.add(row.path("summary").path("traceId").asString("")));
            if (page.path("next").isNull()) {
                return ids;
            }
            page = api.getJson(HTTP_ROWS + "&cursor={cursor}", page.path("next").asString());
        }
        throw new AssertionError("the listing never ran out of pages: " + ids);
    }

    private static JsonNode problemOf(PeekabootApi api, String path, String cursor) {
        return api.restClient()
                .get()
                .uri(path + "&cursor={cursor}", cursor)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) ->
                        PeekabootApi.readJson(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    /** The digest records a trace once its root is stored, and restores off the startup path, so the count is awaited. */
    private static JsonNode awaitBoomEntry(PeekabootApi api, int count) {
        return await().atMost(Duration.ofSeconds(15))
                .until(
                        () -> api.getJson("/peekaboot/api/errors?route=/boom")
                                .path("errors")
                                .path(0),
                        entry -> entry.path("count").asInt() == count);
    }

    /**
     * A request that ended just before a graceful close restores as it ended. Its spans still wait in the
     * exporter's batch at the close, which only the tracer provider's destruction exports, so the journal
     * must close after that. The first run's API is gone by then, so its store is read directly.
     */
    @Test
    void aRequestThatEndedJustBeforeTheCloseRestoresComplete() {
        AtomicBoolean closing = new AtomicBoolean();
        List<String> exportedBeforeClose = new CopyOnWriteArrayList<>();
        List<String> exportedAtClose = new CopyOnWriteArrayList<>();
        String id;
        TraceStore firstStore;
        Duration closeTook;
        try (ConfigurableApplicationContext first = bootHoldingSpans(storageDir, event -> {
            if (event instanceof ContextClosedEvent) {
                closing.set(true);
            } else if (event instanceof PayloadApplicationEvent<?> payload
                    && payload.getPayload() instanceof SpanDataEvent span) {
                (closing.get() ? exportedAtClose : exportedBeforeClose)
                        .add(span.spanData().traceId());
            }
        })) {
            firstStore = first.getBean(TraceStore.class);
            id = tracesOf(first).get("/persons");
            long closeStarted = System.nanoTime();
            first.close();
            closeTook = Duration.ofNanos(System.nanoTime() - closeStarted);
        }
        // the tracer provider waits 10 s for its last export, so a close near that means the export outlasted it
        assertThat(closeTook).as("the first application's close").isLessThan(Duration.ofSeconds(5));
        assertThat(exportedBeforeClose).as("traces exported before the close").doesNotContain(id);
        assertThat(exportedAtClose).as("traces exported at the close").contains(id);
        TraceData heldAtClose = firstStore.getTrace(id).orElseThrow().snapshot();
        assertThat(heldAtClose.complete()).as("the first run's trace").isTrue();

        try (ConfigurableApplicationContext second = boot(storageDir)) {
            JsonNode restored = tracesOf(second).awaitTrace(id, trace -> !"IN_PROGRESS".equals(outcome(trace)));

            assertThat(outcome(restored)).isEqualTo("OK");
            assertThat(restored.path("summary").path("complete").asBoolean(false))
                    .isTrue();
            List<String> restoredSpanIds = new ArrayList<>();
            collectSpanIds(restored.path("data").path("rootSpan"), restoredSpanIds);
            restored.path("data").path("orphans").forEach(orphan -> collectSpanIds(orphan, restoredSpanIds));
            assertThat(restoredSpanIds)
                    .containsExactlyInAnyOrderElementsOf(
                            heldAtClose.spans().stream().map(SpanData::spanId).toList());
        }
    }

    @Test
    void tracesLogsAndRunsSurviveARestart() {
        String okId;
        String failedId;
        JsonNode okBefore;
        JsonNode failedBefore;
        String rowCursorBefore;
        String logCursorBefore;

        try (ConfigurableApplicationContext first = boot(storageDir)) {
            TraceApiClient traces = tracesOf(first);
            okId = traces.getAndAwaitComplete("/persons");
            failedId = traces.getAndAwaitComplete("/boom");
            // the test thread runs outside any trace, so these are lines the untraced ring keeps
            log.info("{} one", tag);
            log.info("{} two", tag);
            first.publishEvent(new SpanDataEvent(TestSpans.span(cutOffTraceId, "query")
                    .parent("handler-never-exported")
                    .named("SELECT person")
                    .kind(Span.Kind.CLIENT)
                    .at(5, 2)
                    .build()));
            first.publishEvent(new LogCapturedEvent(
                    listedCutOffTraceId,
                    null,
                    Instant.now(),
                    "INFO",
                    TracePersistenceIT.class.getName(),
                    Thread.currentThread().getName(),
                    "looking up the person",
                    null));
            traces.awaitTrace(cutOffTraceId, trace -> "IN_PROGRESS".equals(outcome(trace)));
            traces.awaitTrace(listedCutOffTraceId, trace -> "IN_PROGRESS".equals(outcome(trace)));
            assertThat(traces.listedTraceIds("outcome=in_progress")).contains(listedCutOffTraceId);
            rowCursorBefore = traces.api().getJson(HTTP_ROWS).path("next").asString("");
            logCursorBefore = traces.api()
                    .getJson("/peekaboot/api/logs?q={q}&limit=1", tag)
                    .path("next")
                    .asString("");
            assertThat(rowCursorBefore).as("the trace listing's page token").isNotBlank();
            assertThat(logCursorBefore).as("the log search's page token").isNotBlank();
            okBefore = traces.awaitTrace(okId, TraceApiClient.COMPLETE);
            failedBefore = traces.awaitTrace(failedId, TraceApiClient.COMPLETE);
        }

        try (ConfigurableApplicationContext second = boot(storageDir)) {
            TraceApiClient traces = tracesOf(second);
            PeekabootApi api = traces.api();

            // the journal's thread restores, then ends the earlier run, so INTERRUPTED means the restore is done
            JsonNode cutOff = traces.awaitTrace(cutOffTraceId, trace -> "INTERRUPTED".equals(outcome(trace)));
            JsonNode okAfter = traces.awaitTrace(okId, TraceApiClient.COMPLETE);
            JsonNode failedAfter = traces.awaitTrace(failedId, TraceApiClient.COMPLETE);

            assertThat(okAfter)
                    .as("the /persons trace, as the first run served it")
                    .isEqualTo(okBefore);
            assertThat(failedAfter)
                    .as("the /boom trace, as the first run served it")
                    .isEqualTo(failedBefore);
            assertThat(cutOff.path("summary").path("complete").asBoolean(true)).isFalse();
            ApiContract.servedBy(api).assertOk("/peekaboot/api/traces/{traceId}", cutOff);
            assertThat(run(cutOff)).isEqualTo(run(okBefore)).isNotBlank();
            assertThat(traces.listedTraceIds("outcome=interrupted")).contains(listedCutOffTraceId);
            assertThat(traces.listedTraceIds("outcome=in_progress")).doesNotContain(listedCutOffTraceId);
            assertThat(taggedMessages(api)).containsExactly(tag + " two|true", tag + " one|true");

            List<JsonNode> runs = await().until(
                            () -> {
                                List<JsonNode> listed = new ArrayList<>();
                                api.getJson("/peekaboot/api/lifecycle/runs")
                                        .path("runs")
                                        .forEach(listed::add);
                                return listed;
                            },
                            listed -> listed.size() >= 2);
            assertThat(runs)
                    .filteredOn(row -> row.path("startedAt").asString("").equals(run(okBefore)))
                    .as("the first run among the lifecycle runs")
                    .singleElement()
                    .satisfies(row ->
                            assertThat(row.path("running").asBoolean(true)).isFalse());

            JsonNode rowRefusal = problemOf(api, HTTP_ROWS, rowCursorBefore);
            JsonNode logRefusal = problemOf(api, "/peekaboot/api/logs?limit=1", logCursorBefore);
            assertThat(rowRefusal.path("status").asInt()).isEqualTo(400);
            assertThat(rowRefusal.path("parameter").asString("")).isEqualTo("cursor");
            assertThat(rowRefusal.path("detail").asString("")).contains("before the application restarted");
            assertThat(logRefusal.path("status").asInt()).isEqualTo(400);
            assertThat(logRefusal.path("parameter").asString("")).isEqualTo("cursor");
            assertThat(logRefusal.path("detail").asString("")).contains("before the application restarted");

            String newId = traces.getAndAwaitComplete("/persons");
            log.info("{} three", tag);
            assertThat(pagedTraceIds(api)).containsExactly(newId, failedId, okId);
            assertThat(taggedMessages(api)).containsExactly(tag + " three|true", tag + " two|true", tag + " one|true");
            String newRun = run(traces.awaitTrace(newId, TraceApiClient.COMPLETE));
            assertThat(runs)
                    .filteredOn(row -> row.path("running").asBoolean(false))
                    .as("the running run among the lifecycle runs")
                    .singleElement()
                    .satisfies(row -> assertThat(newRun)
                            .as("the new trace's run")
                            .isEqualTo(row.path("startedAt").asString("")));
        }
    }

    /**
     * The error digest is kept beside the traces: the next run serves the count the first one reached,
     * and its own failure adds to it under a second run. These applications are this class's own, so
     * the /boom entry counts only this test's requests.
     */
    @Test
    void theErrorDigestSurvivesARestart() {
        String firstRun;
        try (ConfigurableApplicationContext first = boot(storageDir)) {
            TraceApiClient traces = tracesOf(first);
            traces.get("/boom");
            traces.get("/boom");
            firstRun = awaitBoomEntry(traces.api(), 2).path("runs").path(0).asString();
        }

        try (ConfigurableApplicationContext second = boot(storageDir)) {
            TraceApiClient traces = tracesOf(second);
            JsonNode restored = awaitBoomEntry(traces.api(), 2);
            assertThat(restored.path("runs").valueStream().map(JsonNode::asString))
                    .containsExactly(firstRun);

            String failed = traces.get("/boom");
            JsonNode counted = awaitBoomEntry(traces.api(), 3);
            assertThat(counted.path("lastTraceId").asString()).isEqualTo(failed);
            assertThat(counted.path("runs")).hasSize(2);
        }
    }
}

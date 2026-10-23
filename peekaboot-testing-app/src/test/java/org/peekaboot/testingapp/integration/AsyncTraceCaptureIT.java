package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.peekaboot.backend.domain.trace.AsyncTaskMarker;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.testingapp.Scheduler;
import org.peekaboot.testingapp.TestingApp;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import tools.jackson.databind.JsonNode;

/**
 * What Peekaboot captures from work that crosses a thread hand-off, against Spring's own
 * executors and scheduler rather than a hand-built span. The decorator makes two decisions the
 * store is the only place to see the effect of - it observes a task only when there is already
 * an observation to continue, and it stays off scheduled execution entirely - and this class
 * exists to keep both.
 *
 * <p>The testing app switches {@code spring.task.execution.propagate-context} on in its
 * {@code application.yml}. That is the consuming application's choice, not Peekaboot's, and it
 * is what makes the async span a child of the request that dispatched it rather than a trace of
 * its own.
 *
 * <p>The property below is read by nothing. It gives this class a test-context cache key, and so
 * a {@code TraceStore}, of its own: {@code TraceOverlayIT} also hits {@code /orders/enrich} and
 * could insert an async row inside this class's before/after snapshot window (see
 * {@link #anAsyncTaskDispatchedOutsideARequestIsNotObserved()}).
 */
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "isolated-trace-store=AsyncTraceCaptureIT")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AsyncTraceCaptureIT extends TestingAppITBase {

    private static final Duration TASK_TIMEOUT = Duration.ofSeconds(15);

    /**
     * The request's trace carries the span the decorator raised. Narrower than
     * {@link TraceApiClient#ROOT_SPAN_EXPORTED} and needed on top of it: the task outlives the
     * request, so its span reaches the store after the root span that already made the trace
     * readable.
     */
    private static final Predicate<JsonNode> ASYNC_SPAN_CAPTURED = TraceApiClient.ROOT_SPAN_EXPORTED.and(
            trace -> SpanTree.names(trace).contains(AsyncTaskMarker.CONTEXTUAL_NAME));

    /** Both tasks' spans are stored; the nested one ends last, so nothing of the trace is still to come. */
    private static final Predicate<JsonNode> NESTED_ASYNC_SPANS_CAPTURED = TraceApiClient.ROOT_SPAN_EXPORTED.and(
            trace -> SpanTree.spansNamed(trace, AsyncTaskMarker.CONTEXTUAL_NAME).size() == 2);

    @Autowired
    @Qualifier(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    private TaskExecutor applicationTaskExecutor;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    @Autowired
    private ThreadPoolTaskSchedulerBuilder schedulerBuilder;

    private ThreadPoolTaskScheduler scheduler;

    /**
     * Builds the scheduler Boot would have given a consuming application, Peekaboot's
     * {@code TaskDecorator} and all. The app's own test profile replaces the
     * {@code TaskScheduler} bean with one that only defers ({@code DeferredSchedulingConfig})
     * and decorates nothing, so a job fired through that bean would say nothing at all about
     * what the decorator does to scheduled work.
     */
    @BeforeAll
    void startTheSchedulerBootWouldHaveBuilt() {
        scheduler = schedulerBuilder.build();
        scheduler.initialize();
    }

    @AfterAll
    void stopTheScheduler() {
        scheduler.shutdown();
    }

    @Test
    void anAsyncTaskIsCapturedAsBackgroundWorkUnderTheRequestThatTriggeredIt() {
        String traceId = traces.get("/orders/enrich");

        JsonNode trace = traces.awaitTrace(traceId, ASYNC_SPAN_CAPTURED);

        assertThat(trace.path("rootActionType").asString(""))
                .as("the propagated context keeps the task inside the request's trace, so the "
                        + "request stays the root and the trace stays an HTTP request")
                .isEqualTo(RootActionType.HTTP_REQUEST.name());

        JsonNode asyncSpan = SpanTree.descendantNamed(trace, AsyncTaskMarker.CONTEXTUAL_NAME);

        assertThat(asyncSpan.path("tags").path(AsyncTaskMarker.TAG_KEY).asString(""))
                .as("classification and async-subtree resolution both read the marker tag, not "
                        + "the span's name; a span carrying only the name is invisible to them")
                .isEqualTo(AsyncTaskMarker.TAG_VALUE);

        assertThat(asyncSpan.path("tags").path(AsyncTaskMarker.THREAD_TAG_KEY).asString(""))
                .as("the thread tag is what tells a reader of the trace which thread ran the work")
                .isNotBlank();

        // The request's root span is the outermost span the caller waited for, so "the trace's
        // duration is that span's duration" is the same claim as "the background work is
        // excluded" - and unlike a fixed bound in milliseconds it stays true however long a
        // loaded machine takes to serve the request.
        assertThat(trace.path("durationMs").asLong())
                .as(
                        "the feature's headline claim, against real instrumentation: the request "
                                + "answered while the task was still running, so the trace reports "
                                + "the request's own span rather than the window that also holds "
                                + "the %dms the background work went on for",
                        asyncSpan.path("durationMs").asLong())
                .isEqualTo(trace.path("rootSpan").path("durationMs").asLong());
    }

    /**
     * Spring catches a void {@code @Async} method's exception inside its own task and hands it
     * to the uncaught-exception handler, yet the {@code async task} span still fails with it,
     * and that files the trace under Errors. The handler's ERROR is captured here, which also
     * keeps it from Peekaboot's appender, so the span alone has to do it.
     */
    @Test
    void aFailingAsyncTaskFailsItsSpanAndFilesTheRequestsTraceUnderErrors() {
        String traceId;
        JsonNode trace;
        try (LogCapture uncaught = LogCapture.attach(SimpleAsyncUncaughtExceptionHandler.class)) {
            traceId = traces.get("/orders/enrich?fail=true");
            // the handler logs inside the task, before the async span ends, so waiting for that span is enough
            trace = traces.awaitTrace(traceId, ASYNC_SPAN_CAPTURED);

            assertThat(uncaught.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getThrowableProxy().getMessage()).isEqualTo("enrichment of PK-2001 was rejected");
            });
        }

        JsonNode asyncSpan = SpanTree.descendantNamed(trace, AsyncTaskMarker.CONTEXTUAL_NAME);
        assertThat(asyncSpan.path("status").asString("")).isEqualTo("ERROR");
        assertThat(asyncSpan.path("errorMessage").asString("")).isEqualTo("enrichment of PK-2001 was rejected");
        traces.awaitTraceInBucket("errors", traceId);
        assertThat(asyncRowStatus(traceId))
                .as("the async row is summarised over its own subtree, which now holds the failed span")
                .isEqualTo("HAS_ERRORS");
    }

    /**
     * Background work that dispatches background work of its own: the notification task runs
     * inside the enrichment task's span and outlives it. A row times its whole subtree, so the
     * enrichment row reaches the notification's end rather than stopping at its own span's, and
     * the nested task gets no row of its own, while the request's duration covers neither.
     */
    @Test
    void aNestedAsyncTaskStaysInsideTheRowOfTheTaskThatDispatchedIt() {
        String traceId = traces.get("/orders/enrich?notify=true");

        JsonNode trace = traces.awaitTrace(traceId, NESTED_ASYNC_SPANS_CAPTURED);

        List<JsonNode> asyncSpans = SpanTree.spansNamed(trace, AsyncTaskMarker.CONTEXTUAL_NAME);
        JsonNode enrichment = asyncSpans.getFirst();
        JsonNode notification = asyncSpans.getLast();
        assertThat(enrichment.path("children").valueStream().map(SpanTree::spanId))
                .as("the notification task continues the enrichment task's observation")
                .contains(SpanTree.spanId(notification));
        assertThat(endMs(notification))
                .as("the fixture's nested task outlives its parent; otherwise the row's window and "
                        + "the parent span's own duration would be the same number")
                .isGreaterThan(endMs(enrichment));

        assertThat(trace.path("durationMs").asLong())
                .as("neither task lengthens the request that dispatched the first")
                .isEqualTo(trace.path("rootSpan").path("durationMs").asLong());

        assertThat(asyncRowsOf(traceId)).singleElement().satisfies(row -> {
            assertThat(SpanTree.spanId(row.path("rootSpan"))).isEqualTo(SpanTree.spanId(enrichment));
            assertThat(row.path("durationMs").asLong())
                    .as("the row spans its subtree's window, from the enrichment's start to the "
                            + "nested notification's end")
                    .isEqualTo(
                            endMs(notification) - enrichment.path("startTimeMs").asLong());
        });
    }

    @Test
    void aSucceedingAsyncTaskStaysOutOfErrors() {
        String traceId = traces.get("/orders/enrich");

        JsonNode trace = traces.awaitTrace(traceId, ASYNC_SPAN_CAPTURED);

        assertThat(SpanTree.descendantNamed(trace, AsyncTaskMarker.CONTEXTUAL_NAME)
                        .path("status")
                        .asString(""))
                .isEqualTo("OK");
        assertThat(listedTraceIds("bucket=errors")).doesNotContain(traceId);
    }

    /**
     * What this pins: a job a timer fires still reads as a scheduled job, with Peekaboot's
     * {@code TaskDecorator} in the scheduler's chain. One decorator bean reaches Spring's
     * scheduler builders as well as its executor builders, so every scheduled job in every
     * application using Peekaboot goes through it, and if it ever claimed one, that job's trace
     * would be classified as background work instead.
     *
     * <p>The decorator's type guard is not what holds this up - a timer fires on a thread
     * nothing observes, so the no-observation check gets there first.
     * {@link #aJobScheduledFromObservedWorkIsStillHandedToSpringUnwrapped()} is the test that
     * reaches the guard.
     */
    @Test
    void aScheduledTaskIsObservedExactlyOnceAndStaysAScheduledJob() {
        String traceId = fireFixedRateThroughSpringsScheduler();

        JsonNode trace = traces.awaitTraceInBucket("all", traceId);

        assertThat(trace.path("rootActionType").asString(""))
                .as("Spring's scheduled-task observation is the root of a job's trace and must "
                        + "stay what the trace is classified by")
                .isEqualTo(RootActionType.SCHEDULED_JOB.name());

        assertThat(SpanTree.names(trace))
                .as(
                        "the decorator saw this job's hand-off and had to leave it alone; a '%s' "
                                + "span here means it wrapped the scheduled future and now parents "
                                + "Spring's own observation",
                        AsyncTaskMarker.CONTEXTUAL_NAME)
                .doesNotContain(AsyncTaskMarker.CONTEXTUAL_NAME);

        assertThat(SpanTree.tagKeys(trace))
                .as("and no span of a scheduled job may carry the async marker under any name, "
                        + "since that is what the classifier reads")
                .doesNotContain(AsyncTaskMarker.TAG_KEY);
    }

    /**
     * The decorator's type guard, end to end. An application that schedules work from inside
     * observed code - a request handler holding the injected {@code TaskScheduler} - propagates
     * its observation onto the scheduler thread, and there the guard is the only thing standing
     * between Peekaboot's span and Spring's {@code tasks.scheduled.execution} observation:
     * {@code ThreadPoolTaskScheduler} hands the decorator the scheduled future, and scheduled
     * execution cannot be told apart from inside the wrapper. Take the guard out and the span
     * this test forbids appears, parenting the job and swallowing its timings.
     */
    @Test
    void aJobScheduledFromObservedWorkIsStillHandedToSpringUnwrapped() {
        String traceId = fireFixedRateFromWithinAnObservation();

        JsonNode trace = traces.awaitTraceInBucket("all", traceId);

        assertThat(SpanTree.names(trace))
                .as(
                        "the decorator was handed this job's scheduled future with an observation "
                                + "in scope and still had to leave it alone; a '%s' span here is "
                                + "Peekaboot claiming a scheduled job as background work",
                        AsyncTaskMarker.CONTEXTUAL_NAME)
                .doesNotContain(AsyncTaskMarker.CONTEXTUAL_NAME);
    }

    /**
     * The decorator raises its observation only when one is already in scope on the thread
     * doing the hand-off. A task handed over from a thread nothing observes has no trace to
     * continue, and observing it anyway would put a parentless ASYNC_TASK trace in the store
     * for every background task an application runs - Boot's own deferred bootstrap included.
     *
     * <p>Hands the task over directly rather than through {@code EnrichmentService}: the
     * executor {@code @Async} dispatches to is the seam the decorator sits on either way, and a
     * task this test can wait for makes "it ran" a fact rather than a timing guess.
     */
    @Test
    void anAsyncTaskDispatchedOutsideARequestIsNotObserved() throws InterruptedException {
        // The listing is shared with the rest of this class (and the suite) - an async
        // subtree row from another test's own trace carries that trace's id, not a
        // parentless ASYNC_TASK trace of its own, but listedTraceIds() cannot tell those
        // apart. Same before/after idiom as TraceApiClient.awaitTraceAppearing: only a row
        // that is new since this test's own hand-off can be this test's hand-off.
        List<String> asyncTaskIdsBeforeHandOff = listedTraceIds("rootActionType=" + RootActionType.ASYNC_TASK);

        CountDownLatch handedOver = new CountDownLatch(1);

        applicationTaskExecutor.execute(handedOver::countDown);

        assertThat(handedOver.await(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .as("the hand-off must have run, or this test asserts about nothing")
                .isTrue();

        // Spans reach the store in the order they ended, so a request that ended after the
        // hand-off above is readable only once that hand-off's span - had there been one - is.
        traces.awaitTrace(traces.get("/orders"), TraceApiClient.ROOT_SPAN_EXPORTED);

        List<String> newAsyncTaskIds = new ArrayList<>(listedTraceIds("rootActionType=" + RootActionType.ASYNC_TASK));
        newAsyncTaskIds.removeAll(asyncTaskIdsBeforeHandOff);

        assertThat(newAsyncTaskIds)
                .as("an unobserved hand-off must leave no span at all; an observed one carries no "
                        + "parent and would be listed here as a trace of its own")
                .isEmpty();
    }

    /**
     * Fires the sample app's {@code @Scheduled} job on the scheduler's own threads and answers
     * with the id of the trace that run produced. Handing the job to the scheduler as an
     * {@code Executor} reaches the same {@code ScheduledThreadPoolExecutor.decorateTask} hook a
     * firing timer does, which is the one place a {@code TaskDecorator} is applied to scheduled
     * work. A job that failed instead leaves this future uncompleted until the timeout, with
     * the scheduler's error handler having logged why.
     */
    private String fireFixedRateThroughSpringsScheduler() {
        return CompletableFuture.supplyAsync(
                        () -> ScheduledJobs.run(scheduledTaskHolder, Scheduler.class, "fixedRate"), scheduler)
                .orTimeout(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .join();
    }

    /**
     * The same hand-off, made from inside an observation, which is what an application does
     * when it schedules work from a request or any other observed call. The observation stands
     * in for that caller's own; what matters is that the context propagation the app switched
     * on carries it onto the scheduler thread.
     */
    private String fireFixedRateFromWithinAnObservation() {
        return Observation.createNotStarted("test.scheduling.caller", observationRegistry)
                .observe(this::fireFixedRateThroughSpringsScheduler);
    }

    private String asyncRowStatus(String traceId) {
        List<JsonNode> rows = asyncRowsOf(traceId);
        if (rows.isEmpty()) {
            throw new AssertionError("no async row listed for trace " + traceId);
        }
        return rows.getFirst().path("status").asString("");
    }

    private List<JsonNode> asyncRowsOf(String traceId) {
        JsonNode response =
                api.getJson("/peekaboot/api/traces/insights?limit=10000&rootActionType=" + RootActionType.ASYNC_TASK);
        return response.path("traces")
                .valueStream()
                .filter(listed -> traceId.equals(listed.path("traceId").asString("")))
                .toList();
    }

    private static long endMs(JsonNode span) {
        return span.path("startTimeMs").asLong() + span.path("durationMs").asLong();
    }

    /** The ids the listing endpoint answers with for {@code query}. */
    private List<String> listedTraceIds(String query) {
        JsonNode response = api.getJson("/peekaboot/api/traces/insights?limit=10000&" + query);
        List<String> ids = new ArrayList<>();
        for (JsonNode listed : response.path("traces")) {
            ids.add(listed.path("traceId").asString(""));
        }
        return ids;
    }
}

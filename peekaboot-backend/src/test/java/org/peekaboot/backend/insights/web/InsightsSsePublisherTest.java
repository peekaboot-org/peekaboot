package org.peekaboot.backend.insights.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.peekaboot.backend.insights.AggregateStats;
import org.peekaboot.backend.testsupport.FailingWriteResponse;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.context.request.async.StandardServletAsyncWebRequest;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitterReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

@Isolated("installs the JVM's default uncaught-exception handler")
class InsightsSsePublisherTest {

    /** Every publisher a test builds, stopped afterwards so no sender or dispatch thread outlives its test. */
    private final List<InsightsSsePublisher> publishers = new ArrayList<>();

    private final InsightsSsePublisher publisher = tracked(new InsightsSsePublisher(new ObjectMapper()));

    @AfterEach
    void stopPublishers() {
        publishers.forEach(InsightsSsePublisher::stop);
    }

    @Test
    void tracksSubscribers() {
        assertThat(publisher.subscriberCount()).isZero();
        var emitter = publisher.subscribe();
        assertThat(publisher.subscriberCount()).isEqualTo(1);
        emitter.complete();
        // SubscriberEmitter.complete() detaches the subscriber synchronously
        assertThat(publisher.subscriberCount()).isZero();
    }

    /**
     * An emitter that never times out holds its async request until the peer goes away;
     * a bounded one is reclaimed on its own, and EventSource reconnects transparently.
     */
    @Test
    void emittersTimeOutInsteadOfLivingForever() {
        SseEmitter emitter = publisher.subscribe();

        assertThat(emitter.getTimeout()).isNotNull().isPositive();
    }

    /**
     * Spring wraps the emitter in a DeferredResult without a timeout result, so unless the
     * timeout callback completes the emitter, the timeout interceptor raises an
     * AsyncRequestTimeoutException - a Spring WARN in the host's log per open dashboard,
     * every {@link InsightsSsePublisher#EMITTER_TIMEOUT}. The emitter is wired through
     * Spring's real return-value handler, so the container's notification runs that very
     * interceptor chain.
     */
    @Test
    void theContainerTimeoutCompletesTheStreamInsteadOfRaisingAsyncRequestTimeout() throws Exception {
        SseEmitter emitter = publisher.subscribe();
        DispatchedStream stream = new DispatchedStream(emitter, new MockHttpServletResponse());

        stream.containerTimesOut();

        assertThat(stream.result())
                .as("a completed stream, not an AsyncRequestTimeoutException")
                .isNull();
        assertThat(publisher.subscriberCount()).isZero();
    }

    /**
     * complete() takes the same lock as send(), and a sender wedged in a send holds it: the
     * timeout must not queue up behind that send on the container's thread. Such a peer is
     * detached and left to Spring's own timeout handling.
     */
    @Test
    void aTimeoutDuringAWedgedSendDetachesThePeerWithoutWaitingBehindTheSend() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        Semaphore releaseWrite = new Semaphore(0);
        SseEmitter emitter = publisher.subscribe();
        DispatchedStream stream = new DispatchedStream(emitter, wedgingOnTheFirstWrite(writeStarted, releaseWrite));
        try {
            publisher.onTick(1_000, Map.of("a", 1.0));
            assertThat(writeStarted.await(3, TimeUnit.SECONDS))
                    .as("the sender is wedged inside send()")
                    .isTrue();

            CompletableFuture.runAsync(stream::containerTimesOut).get(3, TimeUnit.SECONDS);

            assertThat(stream.result()).isInstanceOf(AsyncRequestTimeoutException.class);
            assertThat(publisher.subscriberCount()).isZero();
        } finally {
            releaseWrite.release();
        }
    }

    /**
     * The same wedge at shutdown: the interrupt cannot end a send already inside the
     * container's socket write, and complete() would wait behind it, so once the grace
     * has passed stop() must detach the peer instead of holding the context's shutdown
     * for a dead dashboard.
     */
    @Test
    void stopDoesNotWaitBehindAWedgedSend() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        Semaphore releaseWrite = new Semaphore(0);
        SseEmitter emitter = publisher.subscribe();
        new DispatchedStream(emitter, wedgingOnTheFirstWrite(writeStarted, releaseWrite));
        try {
            publisher.onTick(1_000, Map.of("a", 1.0));
            assertThat(writeStarted.await(3, TimeUnit.SECONDS))
                    .as("the sender is wedged inside send()")
                    .isTrue();

            CompletableFuture.runAsync(publisher::stop).get(3, TimeUnit.SECONDS);

            assertThat(publisher.subscriberCount()).isZero();
            assertThat(publisher.isRunning()).isFalse();
        } finally {
            releaseWrite.release();
        }
    }

    /**
     * A sender inside an ordinary socket write at the instant stop() runs is a healthy
     * peer, not a wedged one: refusing it would leave its async request open until the
     * container gives up. stop() waits a short grace for that write to finish and then
     * completes the stream. The grace here outlasts the test, so stop() can only return
     * because the write did.
     */
    @Test
    void stopCompletesAPeerWhoseWriteFinishesWithinTheGrace() throws Exception {
        InsightsSsePublisher publisher = tracked(new InsightsSsePublisher(new ObjectMapper(), Duration.ofSeconds(30)));
        CountDownLatch writeStarted = new CountDownLatch(1);
        Semaphore releaseWrite = new Semaphore(0);
        SseEmitter emitter = publisher.subscribe();
        DispatchedStream stream = new DispatchedStream(emitter, wedgingOnTheFirstWrite(writeStarted, releaseWrite));
        try {
            publisher.onTick(1_000, Map.of("a", 1.0));
            assertThat(writeStarted.await(3, TimeUnit.SECONDS))
                    .as("the sender is inside send()")
                    .isTrue();

            Thread stopping = Thread.ofPlatform().name("stop-under-test").start(publisher::stop);
            await().atMost(Duration.ofSeconds(3))
                    .alias("stop() waits for the write instead of giving the peer up")
                    .until(() -> stopping.getState() == Thread.State.TIMED_WAITING);
            releaseWrite.release();
            stopping.join(3_000);

            assertThat(stopping.isAlive())
                    .as("stop() returned once the write finished")
                    .isFalse();
            assertThat(stream.result()).as("a completed stream").isNull();
            assertThat(publisher.subscriberCount()).isZero();
        } finally {
            releaseWrite.release();
        }
    }

    /**
     * Tomcat's blocking write consumes the interrupt of stop() as it does a drop's. The
     * sender returns from that write detached, to a lane nobody fills again, and must end
     * there: one left waiting holds the context's classes after a restart.
     */
    @Test
    void aSenderWhoseWriteConsumedTheInterruptOfStopEndsOnceTheWriteReturns() throws Exception {
        InsightsSsePublisher publisher = tracked(new InsightsSsePublisher(new ObjectMapper(), Duration.ZERO));
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch interruptConsumed = new CountDownLatch(1);
        Semaphore releaseWrite = new Semaphore(0);
        AtomicReference<Thread> sender = new AtomicReference<>();
        Runnable wedge = consumingTheInterrupt(interruptConsumed, releaseWrite);
        MockHttpServletResponse response = wedgingOnTheFirstWrite(writeStarted, () -> {
            sender.set(Thread.currentThread());
            wedge.run();
        });
        new DispatchedStream(publisher.subscribe(), response);
        try {
            publisher.heartbeat();
            assertThat(writeStarted.await(3, TimeUnit.SECONDS))
                    .as("the sender is wedged inside send()")
                    .isTrue();
            publisher.heartbeat();

            publisher.stop();
            assertThat(interruptConsumed.await(3, TimeUnit.SECONDS))
                    .as("the wedged write consumed the interrupt of stop()")
                    .isTrue();
            releaseWrite.release();

            await().atMost(Duration.ofSeconds(3))
                    .alias("the sender ends once its write returns")
                    .until(() -> !sender.get().isAlive());
            assertThat(response.getContentAsString())
                    .as("the heartbeat that was in flight, and not the one in the lane")
                    .isEqualTo(":hb\n\n");
        } finally {
            releaseWrite.release();
        }
    }

    /**
     * stop() can run with the interrupt flag already set: the dispatch thread's join
     * re-asserts it after an interrupted wait, and the flag survives into the subscriber
     * loop. A timed lock attempt throws on entry for an interrupted caller, so unless the
     * untimed one goes first, every idle peer is detached instead of completed.
     */
    @Test
    void stopRunningInterruptedStillCompletesIdlePeers() throws Exception {
        SseEmitter emitter = publisher.subscribe();
        DispatchedStream stream = new DispatchedStream(emitter, new MockHttpServletResponse());

        Thread.currentThread().interrupt();
        try {
            publisher.stop();
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the flag survives stop()")
                    .isTrue();
        } finally {
            Thread.interrupted();
        }

        assertThat(stream.result()).as("a completed stream").isNull();
        assertThat(publisher.subscriberCount()).isZero();
    }

    @Test
    void refusesSubscribersBeyondTheCapWithServiceUnavailable() {
        for (int i = 0; i < InsightsSsePublisher.MAX_SUBSCRIBERS; i++) {
            publisher.subscribe();
        }

        assertThatThrownBy(publisher::subscribe)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(publisher.subscriberCount()).isEqualTo(InsightsSsePublisher.MAX_SUBSCRIBERS);
    }

    @Test
    void aSeatFreedByADepartingSubscriberIsHandedOutAgain() {
        SseEmitter first = publisher.subscribe();
        for (int i = 1; i < InsightsSsePublisher.MAX_SUBSCRIBERS; i++) {
            publisher.subscribe();
        }

        first.complete();

        assertThatCode(publisher::subscribe).doesNotThrowAnyException();
        assertThat(publisher.subscriberCount()).isEqualTo(InsightsSsePublisher.MAX_SUBSCRIBERS);
    }

    /**
     * The peer's write is held until the test ends, so an onTick that sent on the caller's
     * thread would never return; that it does, and that the write then starts on another
     * thread, is the whole proof.
     */
    @Test
    void onTickReturnsWhileDeliveryHappensOnTheSenderThread() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        Semaphore releaseWrite = new Semaphore(0);
        new DispatchedStream(publisher.subscribe(), wedgingOnTheFirstWrite(writeStarted, releaseWrite));
        try {
            publisher.onTick(1_000, Map.of("a", 1.0));

            assertThat(writeStarted.await(3, TimeUnit.SECONDS))
                    .as("the queued event reaches the peer's write off the caller's thread")
                    .isTrue();
        } finally {
            releaseWrite.release();
        }
    }

    @Test
    void dispatchThreadKeepsDrainingAfterRapidDisconnectAndResubscribe() throws Exception {
        // Proxy for the exit/restart race: a subscriber disconnects (draining the
        // subscriber list to empty, which the dispatch thread's exit check may or may
        // not have observed yet) and a new one immediately replaces it - the
        // scenario a browser tab refresh (EventSource reconnect) produces. This
        // can't force the exact nanosecond interleaving deterministically, but it
        // exercises the real disconnect -> resubscribe -> deliver path end to end;
        // correctness under the race itself is argued by the exit-under-lock
        // reasoning on InsightsSsePublisher.dispatcher.
        MockHttpServletResponse response = new MockHttpServletResponse();
        var firstEmitter = publisher.subscribe();
        firstEmitter.complete();
        new DispatchedStream(publisher.subscribe(), response);

        publisher.onTick(1_000, Map.of("a", 1.0));

        await().atMost(Duration.ofSeconds(3))
                .alias("dispatch thread keeps draining after a rapid disconnect/resubscribe")
                .untilAsserted(() -> assertThat(response.getContentAsString()).contains("event:tick"));
    }

    /**
     * More events than the queue holds: with nobody watching, none of them may be queued
     * at all - otherwise a dashboard-less app fills the queue, logs the "queue full"
     * warning, and buries the first viewer under stale events.
     */
    @Test
    void nothingIsQueuedWhileNobodyIsSubscribed() throws Exception {
        try (LogCapture logs = LogCapture.attach(InsightsSsePublisher.class)) {
            // more events than the queue holds, so anything queued for nobody would overflow it
            for (int i = 0; i < 300; i++) {
                publisher.onTick(i, Map.of("a", 1.0));
            }
            assertThat(overflowWarnings(logs)).as("nothing queued for nobody").isZero();
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        new DispatchedStream(publisher.subscribe(), response);
        publisher.onTick(9_000, Map.of("a", 1.0));

        await().atMost(Duration.ofSeconds(3))
                .alias("fresh events still flow")
                .untilAsserted(() -> assertThat(response.getContentAsString()).contains("\"epochMs\":9000"));
        assertThat(response.getContentAsString())
                .as("no stale burst on the first subscribe")
                .doesNotContain("\"epochMs\":1,");
    }

    /**
     * Wedging the dispatch thread inside a render is what makes the leftover state
     * reachable: an event queued for a subscriber that disconnects before it is drained.
     */
    @Test
    void firstSubscriberStartsFromAnEmptyQueue() throws Exception {
        GatedRender render = new GatedRender();
        InsightsSsePublisher publisher = tracked(new InsightsSsePublisher(render));
        SseEmitter first = publisher.subscribe();
        publisher.onTick(1_000, Map.of("a", 1.0));
        render.awaitParked();

        publisher.onTick(2_000, Map.of("a", 2.0)); // queues up behind the wedge
        first.complete(); // ... and its subscriber leaves
        MockHttpServletResponse response = new MockHttpServletResponse();
        new DispatchedStream(publisher.subscribe(), response); // 0 -> 1: must start from an empty queue

        render.open();
        publisher.onTick(3_000, Map.of("a", 3.0));
        await().atMost(Duration.ofSeconds(3))
                .alias("fresh events still flow")
                .untilAsserted(() -> assertThat(response.getContentAsString()).contains("\"epochMs\":3000"));
        assertThat(response.getContentAsString())
                .as("the event queued for the departed subscriber is dropped")
                .doesNotContain("\"epochMs\":2000");
    }

    @Test
    void theDispatchThreadSurvivesAFailingRender() throws Exception {
        AtomicBoolean failNext = new AtomicBoolean(true);
        InsightsSsePublisher publisher = tracked(new InsightsSsePublisher(new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) {
                if (failNext.getAndSet(false)) {
                    throw new IllegalStateException("expected failure from theDispatchThreadSurvivesAFailingRender");
                }
                return super.writeValueAsString(value);
            }
        }));
        MockHttpServletResponse response = new MockHttpServletResponse();
        new DispatchedStream(publisher.subscribe(), response);

        try (LogCapture logs = LogCapture.attach(InsightsSsePublisher.class)) {
            publisher.onTick(1_000, Map.of("a", 1.0));
            publisher.onTick(2_000, Map.of("a", 2.0));

            await().atMost(Duration.ofSeconds(3))
                    .alias("the thread keeps running after a step threw")
                    .untilAsserted(
                            () -> assertThat(response.getContentAsString()).contains("\"epochMs\":2000"));
            assertThat(logs.events()).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo("Insights SSE dispatch step failed; continuing");
            });
        }
    }

    /**
     * Parking the dispatch thread inside a render is what makes an overflow episode
     * reproducible: while it is parked nothing drains. Flooding only starts once the
     * dispatcher is provably parked - otherwise a poll landing mid-flood frees a slot, that
     * offer succeeds, and the episode legitimately splits in two. The drain between the
     * episodes is a single render: one freed slot is all an offer needs to succeed again,
     * and a full drain would race the subscriber's own lane.
     */
    @Test
    void queueOverflowWarnsOncePerEpisode() throws Exception {
        GatedRender render = new GatedRender();
        InsightsSsePublisher publisher = tracked(new InsightsSsePublisher(render));
        new DispatchedStream(publisher.subscribe(), new MockHttpServletResponse());

        try (LogCapture logs = LogCapture.attach(InsightsSsePublisher.class)) {
            publisher.onTick(0, Map.of("a", 0.0));
            render.awaitParked();
            flood(publisher);
            assertThat(overflowWarnings(logs))
                    .as("the first overflow episode warns")
                    .isEqualTo(1);

            render.allow(1); // one event off the full queue, so the next offer has a slot
            render.awaitParked();
            publisher.onTick(1_000, Map.of("a", 1.0)); // fits, which ends the episode
            flood(publisher);
            assertThat(overflowWarnings(logs))
                    .as("a second episode is not silent")
                    .isEqualTo(2);
        } finally {
            render.open();
        }
    }

    /**
     * One peer that has stopped reading must not stall delivery to the others: each
     * subscriber's events go through a bounded lane of its own, drained by its own sender
     * thread, so a send wedged on one peer blocks only that peer's lane.
     */
    @Test
    void aWedgedPeerDoesNotStallDeliveryToOtherSubscribers() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        Semaphore releaseWrite = new Semaphore(0);
        MockHttpServletResponse healthy = new MockHttpServletResponse();
        new DispatchedStream(publisher.subscribe(), wedgingOnTheFirstWrite(writeStarted, releaseWrite));
        new DispatchedStream(publisher.subscribe(), healthy);
        try {
            publisher.onTick(1_000, Map.of("a", 1.0));

            assertThat(writeStarted.await(3, TimeUnit.SECONDS))
                    .as("the first peer is wedged inside its write")
                    .isTrue();
            await().atMost(Duration.ofSeconds(3))
                    .alias("the healthy subscriber receives while the other peer's send is wedged")
                    .untilAsserted(
                            () -> assertThat(healthy.getContentAsString()).contains("event:tick"));
        } finally {
            releaseWrite.release();
        }
    }

    /**
     * The backed-up lane is how a peer that stops reading is detected: the wedged send
     * blocks its sender, the lane fills, and the overflow drops the subscriber - as
     * routine as a failed send, so a one-line DEBUG message, not a warning.
     */
    @Test
    void aPeerThatStopsReadingIsDroppedOnceItsLaneOverflows() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        Semaphore releaseWrite = new Semaphore(0);
        new DispatchedStream(publisher.subscribe(), wedgingOnTheFirstWrite(writeStarted, releaseWrite));
        try (LogCapture logs = LogCapture.attach(Subscriber.class, Level.DEBUG)) {
            // the sender holds at most one event in flight, so this overflows the lane
            for (int i = 0; i < Subscriber.LANE_CAPACITY + 2; i++) {
                publisher.onTick(i, Map.of("a", 1.0));
            }

            await().atMost(Duration.ofSeconds(3))
                    .alias("the wedged peer is dropped on lane overflow")
                    .until(() -> publisher.subscriberCount() == 0);
            assertThat(logs.events()).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage()).contains("stopped reading");
            });
        } finally {
            releaseWrite.release();
        }
    }

    /**
     * A dropped peer whose stream stays open shows a frozen chart until the emitter times
     * out. The drop cannot end the stream while the wedged send holds the write lock, so
     * the sender ends it once that send returns, and the browser reconnects.
     */
    @Test
    void aPeerDroppedOnLaneOverflowHasItsStreamCompletedOnceItsWriteReturns() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        Semaphore releaseWrite = new Semaphore(0);
        DispatchedStream stream =
                new DispatchedStream(publisher.subscribe(), wedgingOnTheFirstWrite(writeStarted, releaseWrite));
        try (LogCapture logs = LogCapture.attach(Subscriber.class, Level.DEBUG)) {
            publisher.onTick(0, Map.of("a", 1.0));
            assertThat(writeStarted.await(3, TimeUnit.SECONDS))
                    .as("the sender is wedged inside send()")
                    .isTrue();
            // the wedged send holds one event, so the rest of the LANE_CAPACITY + 2 overflow the lane
            for (int i = 1; i < Subscriber.LANE_CAPACITY + 2; i++) {
                publisher.onTick(i, Map.of("a", 1.0));
            }
            await().atMost(Duration.ofSeconds(3))
                    .alias("the wedged peer is dropped on lane overflow")
                    .until(() -> publisher.subscriberCount() == 0);

            releaseWrite.release();

            await().atMost(Duration.ofSeconds(3))
                    .alias("the stream is completed once the write returns")
                    .until(() -> stream.result() == null);
            assertThat(logs.events())
                    .as("the drop, and nothing about ending the stream")
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                        assertThat(event.getFormattedMessage()).contains("stopped reading");
                    });
        } finally {
            releaseWrite.release();
        }
    }

    /**
     * Tomcat's blocking write consumes an interrupt and waits on, so the drop's interrupt is
     * gone when the wedged send returns. The sender still learns that it was dropped, ends
     * the stream, and sends none of the stale events its lane holds.
     */
    @Test
    void aDroppedPeerWhoseWriteConsumedTheInterruptHasItsStreamCompletedAndIsSentNothingMore() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch interruptConsumed = new CountDownLatch(1);
        Semaphore releaseWrite = new Semaphore(0);
        MockHttpServletResponse response =
                wedgingOnTheFirstWrite(writeStarted, consumingTheInterrupt(interruptConsumed, releaseWrite));
        DispatchedStream stream = new DispatchedStream(publisher.subscribe(), response);
        try (LogCapture logs = LogCapture.attach(Subscriber.class, Level.DEBUG)) {
            publisher.heartbeat();
            assertThat(writeStarted.await(3, TimeUnit.SECONDS))
                    .as("the sender is wedged inside send()")
                    .isTrue();
            // Fanned out on this thread, so the drop has run in full, its own attempt at the
            // completion included, and only the sender is left to end the stream.
            for (int i = 0; i <= Subscriber.LANE_CAPACITY; i++) {
                publisher.heartbeat();
            }
            assertThat(publisher.subscriberCount())
                    .as("the wedged peer is dropped on lane overflow")
                    .isZero();
            assertThat(interruptConsumed.await(3, TimeUnit.SECONDS))
                    .as("the wedged write consumed the drop's interrupt")
                    .isTrue();

            releaseWrite.release();

            await().atMost(Duration.ofSeconds(3))
                    .alias("the stream is completed once the write returns")
                    .until(() -> stream.result() == null);
            assertThat(response.getContentAsString())
                    .as("the heartbeat that was in flight, and none of the lane's")
                    .isEqualTo(":hb\n\n");
            assertThat(logs.events())
                    .as("the drop, and nothing about ending the stream")
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                        assertThat(event.getFormattedMessage()).contains("stopped reading");
                    });
        } finally {
            releaseWrite.release();
        }
    }

    /**
     * A write that an interrupt cannot end can return with the drop's interrupt still
     * pending. The sender holds it back while it ends the stream, so the completion's
     * flush does not run on an interrupted thread.
     */
    @Test
    void aDroppedPeersStreamIsCompletedWithThePendingInterruptHeldBack() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        Semaphore releaseWrite = new Semaphore(0);
        DispatchedStream stream =
                new DispatchedStream(publisher.subscribe(), wedgingOnTheFirstWrite(writeStarted, releaseWrite));
        CompletableFuture<Boolean> interruptedWhileCompleting = new CompletableFuture<>();
        stream.onCompletionDispatch(
                () -> interruptedWhileCompleting.complete(Thread.currentThread().isInterrupted()));
        try (LogCapture logs = LogCapture.attach(Subscriber.class, Level.DEBUG)) {
            publisher.heartbeat();
            assertThat(writeStarted.await(3, TimeUnit.SECONDS))
                    .as("the sender is wedged inside send()")
                    .isTrue();
            // Fanned out on this thread, so the drop has interrupted the sender before the write is released.
            for (int i = 0; i <= Subscriber.LANE_CAPACITY; i++) {
                publisher.heartbeat();
            }
            assertThat(publisher.subscriberCount())
                    .as("the wedged peer is dropped on lane overflow")
                    .isZero();

            releaseWrite.release();

            assertThat(interruptedWhileCompleting.get(3, TimeUnit.SECONDS))
                    .as("the sender's interrupt flag while it completes the stream")
                    .isFalse();
            assertThat(logs.events())
                    .as("the drop, and nothing about ending the stream")
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                        assertThat(event.getFormattedMessage()).contains("stopped reading");
                    });
        } finally {
            releaseWrite.release();
        }
    }

    /**
     * The sender ends a dropped peer's stream from its own thread, where a container that
     * has already let the peer go refuses the completion. That is as routine there as after
     * a failed send, and must not come out of the thread as an uncaught exception.
     */
    @Test
    void aDroppedPeerWhoseCompletionTheContainerRefusesGoesQuietly() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        Semaphore releaseWrite = new Semaphore(0);
        DispatchedStream stream =
                new DispatchedStream(publisher.subscribe(), wedgingOnTheFirstWrite(writeStarted, releaseWrite));
        stream.refusesDispatchWith(new IllegalStateException(
                "expected refusal from aDroppedPeerWhoseCompletionTheContainerRefusesGoesQuietly"));

        try (UncaughtExceptions uncaught = new UncaughtExceptions();
                LogCapture logs = LogCapture.attach(Subscriber.class, Level.DEBUG)) {
            publisher.heartbeat();
            assertThat(writeStarted.await(3, TimeUnit.SECONDS))
                    .as("the sender is wedged inside send()")
                    .isTrue();
            // Fanned out on this thread, so the drop has run in full, its own attempt at the
            // completion included, before the write is released.
            for (int i = 0; i <= Subscriber.LANE_CAPACITY; i++) {
                publisher.heartbeat();
            }
            assertThat(publisher.subscriberCount())
                    .as("the wedged peer is dropped on lane overflow")
                    .isZero();

            releaseWrite.release();

            await().dontCatchUncaughtExceptions()
                    .atMost(Duration.ofSeconds(3))
                    .alias("the sender thread finished")
                    .until(() -> !uncaught.fromTheSenderThread().isEmpty()
                            || !alreadyGone(logs).isEmpty());
            assertThat(uncaught.fromTheSenderThread())
                    .as("nothing escapes the sender thread")
                    .isEmpty();
            assertThat(logs.events())
                    .filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                    .as("a refused completion is no louder than the drop that led to it")
                    .isEmpty();
            assertThat(alreadyGone(logs)).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getThrowableProxy())
                        .as("one line, not the container's stack trace")
                        .isNull();
            });
        } finally {
            releaseWrite.release();
        }
    }

    /**
     * A lane can overflow between two sends of a peer that is only slow. No send holds the
     * write lock then, so the drop ends the stream itself and at once.
     */
    @Test
    void aPeerDroppedWhileNoSendIsInFlightHasItsStreamCompletedByTheDrop() throws Exception {
        InsightsSsePublisher.SubscriberEmitter emitter = publisher.new SubscriberEmitter();
        DispatchedStream stream = new DispatchedStream(emitter, new MockHttpServletResponse());
        Subscriber subscriber = withoutASendInFlight(emitter);

        try (LogCapture logs = LogCapture.attach(Subscriber.class, Level.DEBUG)) {
            overflowTheLaneOf(subscriber);

            assertThat(stream.result()).as("a completed stream").isNull();
            assertThat(logs.events())
                    .as("the drop, and nothing about ending the stream")
                    .singleElement()
                    .satisfies(event -> assertThat(event.getFormattedMessage()).contains("stopped reading"));
        }
    }

    /**
     * The drop runs inside the fan-out, on the dispatch thread. A refused completion that
     * left it as an exception would cost every subscriber after this one the event.
     */
    @Test
    void aCompletionTheContainerRefusesOnADropStaysOutOfTheFanOut() throws Exception {
        InsightsSsePublisher.SubscriberEmitter emitter = publisher.new SubscriberEmitter();
        DispatchedStream stream = new DispatchedStream(emitter, new MockHttpServletResponse());
        stream.refusesDispatchWith(new IllegalStateException(
                "expected refusal from aCompletionTheContainerRefusesOnADropStaysOutOfTheFanOut"));
        Subscriber subscriber = withoutASendInFlight(emitter);

        try (LogCapture logs = LogCapture.attach(Subscriber.class, Level.DEBUG)) {
            assertThatCode(() -> overflowTheLaneOf(subscriber)).doesNotThrowAnyException();

            assertThat(logs.events())
                    .filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                    .as("a refused completion is no louder than the drop that led to it")
                    .isEmpty();
            assertThat(alreadyGone(logs)).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getThrowableProxy())
                        .as("one line, not the container's stack trace")
                        .isNull();
            });
        }
    }

    /**
     * A peer that went away is routine, not an incident: the reason is logged at DEBUG as
     * one line, without the stack trace of the servlet container's broken pipe.
     */
    @Test
    void aSubscriberWhoseSendFailsIsDroppedWithAOneLineDebugMessage() throws Exception {
        new DispatchedStream(
                publisher.subscribe(), FailingWriteResponse.failingEveryWrite(new IOException("Broken pipe")));

        try (LogCapture logs = LogCapture.attach(Subscriber.class, Level.DEBUG)) {
            publisher.onTick(1_000, Map.of("a", 1.0));

            // the send - and with it the failure - happens on the subscriber's sender thread
            await().atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat(logs.events()).hasSize(1));
            assertThat(logs.events()).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage())
                        .isEqualTo(
                                "Dropping insights SSE subscriber after send failure: java.io.IOException: Broken pipe");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(publisher.subscriberCount()).isZero();
        }
    }

    /**
     * Ending the stream is itself a call the container can refuse: Tomcat's AsyncContext
     * rejects a dispatch from a non-container thread once its onError has returned, and that
     * throw comes straight out of completeWithError() on the sender thread. It must not kill
     * the sender, and the peer must be detached anyway.
     */
    @Test
    void aCompletionTheContainerRefusesStillDropsThePeerQuietly() throws Exception {
        DispatchedStream stream = new DispatchedStream(
                publisher.subscribe(), FailingWriteResponse.failingEveryWrite(new IOException("Broken pipe")));
        stream.refusesDispatchWith(
                new IllegalStateException(
                        "A non-container (application) thread attempted to use "
                                + "the AsyncContext after an error had occurred and the call to AsyncListener.onError() had returned."));

        try (UncaughtExceptions uncaught = new UncaughtExceptions();
                LogCapture logs = LogCapture.attach(Subscriber.class, Level.DEBUG)) {
            publisher.onTick(1_000, Map.of("a", 1.0));

            // Awaitility's own uncaught-exception catching would take the handler over mid-wait
            await().dontCatchUncaughtExceptions()
                    .atMost(Duration.ofSeconds(3))
                    .alias("the sender thread finished")
                    .until(() -> !uncaught.fromTheSenderThread().isEmpty() || publisher.subscriberCount() == 0);
            assertThat(uncaught.fromTheSenderThread())
                    .as("nothing escapes the sender thread")
                    .isEmpty();
            assertThat(logs.events())
                    .filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                    .as("a refused completion is no louder than the send failure that led to it")
                    .isEmpty();
            assertThat(alreadyGone(logs)).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getThrowableProxy())
                        .as("one line, not the container's stack trace")
                        .isNull();
            });
        }
        assertThat(publisher.subscriberCount())
                .as("the peer is detached even though its stream could not be ended")
                .isZero();
    }

    /**
     * A completion refused for any other reason is not a peer going away, so it is reported
     * once, with the send failure behind it and its own stack trace - and the subscriber still
     * goes.
     */
    @Test
    void aCompletionFailingForAnotherReasonIsReportedOnce() throws Exception {
        DispatchedStream stream = new DispatchedStream(
                publisher.subscribe(), FailingWriteResponse.failingEveryWrite(new IOException("Broken pipe")));
        stream.refusesDispatchWith(
                new IllegalArgumentException("expected failure from aCompletionFailingForAnotherReasonIsReportedOnce"));

        try (UncaughtExceptions uncaught = new UncaughtExceptions();
                LogCapture logs = LogCapture.attach(Subscriber.class, Level.DEBUG)) {
            publisher.onTick(1_000, Map.of("a", 1.0));

            await().dontCatchUncaughtExceptions()
                    .atMost(Duration.ofSeconds(3))
                    .alias("the peer is detached")
                    .until(() -> !uncaught.fromTheSenderThread().isEmpty() || publisher.subscriberCount() == 0);
            assertThat(uncaught.fromTheSenderThread())
                    .as("nothing escapes the sender thread")
                    .isEmpty();
            assertThat(logs.events())
                    .filteredOn(event -> event.getLevel() == Level.WARN)
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.getFormattedMessage())
                                .as("why a completion was attempted, which otherwise sits in a DEBUG line above")
                                .contains("java.io.IOException: Broken pipe");
                        assertThat(event.getThrowableProxy()).isNotNull();
                    });
        }
        assertThat(publisher.subscriberCount())
                .as("the subscriber goes even though ending its stream failed")
                .isZero();
    }

    /** A keep-alive is an SSE comment, so an idle connection carries traffic without a data event. */
    @Test
    void aHeartbeatReachesAHealthySubscriberAsAComment() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new DispatchedStream(publisher.subscribe(), response);

        publisher.heartbeat();

        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(response.getContentAsString()).contains(":hb"));
    }

    /**
     * Heartbeats travel the subscriber's lane like events, so an idle dashboard that has
     * stopped reading backs its lane up on heartbeats alone and is dropped the same way.
     */
    @Test
    void heartbeatsAloneDropAWedgedIdlePeer() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        Semaphore releaseWrite = new Semaphore(0);
        new DispatchedStream(publisher.subscribe(), wedgingOnTheFirstWrite(writeStarted, releaseWrite));
        try (LogCapture logs = LogCapture.attach(Subscriber.class, Level.DEBUG)) {
            // the sender holds at most one heartbeat in flight, so this overflows the lane
            for (int i = 0; i < Subscriber.LANE_CAPACITY + 2; i++) {
                publisher.heartbeat();
            }

            assertThat(publisher.subscriberCount())
                    .as("the wedged idle peer is dropped on lane overflow")
                    .isZero();
            assertThat(logs.events()).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage()).contains("stopped reading");
            });
        } finally {
            releaseWrite.release();
        }
    }

    @Test
    void aRollUpReachesSubscribersAsARollupEvent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new DispatchedStream(publisher.subscribe(), response);

        publisher.onRollUp(1, 60_000, Map.of("g", AggregateStats.of(new double[] {1.0, 3.0})));

        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(response.getContentAsString())
                        .contains("event:rollup")
                        .contains("\"level\":1")
                        .contains("\"epochMs\":60000")
                        .contains("\"avg\":2.0"));
    }

    @Test
    void stopCompletesSubscribers() {
        publisher.subscribe();
        assertThat(publisher.subscriberCount()).isEqualTo(1);

        publisher.stop();

        assertThat(publisher.subscriberCount())
                .as("open emitters must not outlive the context")
                .isZero();
        assertThat(publisher.isRunning()).isFalse();
    }

    @Test
    void subscribingAfterStopHandsBackAClosedStream() {
        publisher.stop();

        // The connector outlives this lifecycle phase, so a reconnecting dashboard
        // can still reach us - it must not get an emitter that holds shutdown open.
        publisher.subscribe();

        assertThat(publisher.subscriberCount()).isZero();
    }

    private InsightsSsePublisher tracked(InsightsSsePublisher publisher) {
        publishers.add(publisher);
        return publisher;
    }

    /** A subscriber whose sender never starts: nothing drains its lane and no send ever holds the write lock. */
    private static Subscriber withoutASendInFlight(InsightsSsePublisher.SubscriberEmitter emitter) {
        return new Subscriber(emitter, () -> {});
    }

    /** One event more than the lane holds, offered the way a fan-out offers them. */
    private static void overflowTheLaneOf(Subscriber subscriber) {
        for (int i = 0; i <= Subscriber.LANE_CAPACITY; i++) {
            subscriber.offerOrDrop(Subscriber.OutboundEvent.heartbeat());
        }
    }

    private static List<ILoggingEvent> alreadyGone(LogCapture logs) {
        return logs.events().stream()
                .filter(event -> event.getFormattedMessage().contains("had already gone"))
                .toList();
    }

    /**
     * Collects what the JVM's default handler would otherwise print for a sender thread that
     * dies on an exception. The handler is JVM-global, so every thread in the module's JVM
     * reaches it and only the sender's exceptions are kept. The class is {@code @Isolated} for
     * it; {@code TestSourceConventionsTest} holds every such writer to that. close() puts the
     * previous handler back.
     */
    private static final class UncaughtExceptions implements AutoCloseable {

        private final List<Throwable> fromTheSenderThread = new CopyOnWriteArrayList<>();
        private final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();

        UncaughtExceptions() {
            Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
                if (Subscriber.SENDER_THREAD.equals(thread.getName())) {
                    fromTheSenderThread.add(e);
                }
            });
        }

        List<Throwable> fromTheSenderThread() {
            return fromTheSenderThread;
        }

        @Override
        public void close() {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    /**
     * A mapper whose renders park until the test lets them through, wedging the dispatch
     * thread inside a render. {@link #awaitParked()} is the rendezvous: the dispatcher has
     * taken an event off the queue and is provably going nowhere until the next permit.
     */
    private static final class GatedRender extends ObjectMapper {

        private final Semaphore permits = new Semaphore(0);
        private volatile CountDownLatch parked = new CountDownLatch(1);

        @Override
        public String writeValueAsString(Object value) {
            if (!permits.tryAcquire()) {
                parked.countDown();
                try {
                    permits.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "{}";
                }
            }
            return super.writeValueAsString(value);
        }

        void awaitParked() throws InterruptedException {
            assertThat(parked.await(5, TimeUnit.SECONDS))
                    .as("dispatcher parked inside a render")
                    .isTrue();
        }

        /** Lets {@code count} renders through; the one after them parks again, on a fresh rendezvous. */
        void allow(int count) {
            parked = new CountDownLatch(1);
            permits.release(count);
        }

        /** Lets every render through from now on. */
        void open() {
            permits.release(Integer.MAX_VALUE / 2);
        }
    }

    /**
     * A response whose first write blocks until released - a peer that has stopped reading.
     * An interrupt does not end this write and stays pending when it returns.
     */
    private static MockHttpServletResponse wedgingOnTheFirstWrite(CountDownLatch writeStarted, Semaphore releaseWrite) {
        return wedgingOnTheFirstWrite(writeStarted, releaseWrite::acquireUninterruptibly);
    }

    /**
     * Blocks until released the way Tomcat's blocking write does (NioSocketWrapper.doWrite):
     * an interrupt does not end the wait, and the flag is clear once the write returns.
     */
    private static Runnable consumingTheInterrupt(CountDownLatch interruptConsumed, Semaphore releaseWrite) {
        return () -> {
            try {
                releaseWrite.acquire();
            } catch (InterruptedException e) {
                interruptConsumed.countDown();
                releaseWrite.acquireUninterruptibly();
            }
        };
    }

    /**
     * A response whose first write runs {@code wedge} before anything is written.
     * One stream per response: Spring fetches the output stream once per converter write,
     * and a send spans several, so a fresh stream each time would wedge the same send again.
     */
    private static MockHttpServletResponse wedgingOnTheFirstWrite(CountDownLatch writeStarted, Runnable wedge) {
        return new MockHttpServletResponse() {
            private final ServletOutputStream written = super.getOutputStream();
            private final ServletOutputStream stream = new ServletOutputStream() {
                private boolean wedged;

                @Override
                public void write(int b) throws IOException {
                    if (!wedged) {
                        wedged = true;
                        writeStarted.countDown();
                        wedge.run();
                    }
                    written.write(b);
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {}
            };

            @Override
            public ServletOutputStream getOutputStream() {
                return stream;
            }
        };
    }

    /**
     * An emitter wired the way a request dispatch wires it: through Spring's real
     * return-value handler and async manager, so a container timeout notification runs
     * the interceptor chain that decides between "completed" and AsyncRequestTimeoutException.
     */
    private static final class DispatchedStream {

        private final MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/peekaboot/api/insights/stream");
        private final StandardServletAsyncWebRequest asyncRequest;

        DispatchedStream(SseEmitter emitter, MockHttpServletResponse response) throws Exception {
            request.setAsyncSupported(true);
            asyncRequest = new StandardServletAsyncWebRequest(request, response);
            WebAsyncUtils.getAsyncManager(request).setAsyncWebRequest(asyncRequest);
            new ResponseBodyEmitterReturnValueHandler(List.of(new StringHttpMessageConverter()))
                    .handleReturnValue(
                            emitter,
                            new MethodParameter(InsightsController.class.getMethod("stream"), -1),
                            new ModelAndViewContainer(),
                            new ServletWebRequest(request, response));
        }

        void containerTimesOut() {
            try {
                asyncRequest.onTimeout(new AsyncEvent(request.getAsyncContext()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** Makes the container refuse the completion dispatch, as one that has already errored does. */
        void refusesDispatchWith(RuntimeException refusal) {
            onCompletionDispatch(() -> {
                throw refusal;
            });
        }

        /** Runs {@code handler} on the thread that completes the stream, inside the completion. */
        void onCompletionDispatch(Runnable handler) {
            MockAsyncContext asyncContext = (MockAsyncContext) Objects.requireNonNull(request.getAsyncContext());
            asyncContext.addDispatchHandler(handler);
        }

        /** What the async dispatch would hand back: null for a completed stream, or the exception. */
        Object result() {
            return WebAsyncUtils.getAsyncManager(request).getConcurrentResult();
        }
    }

    /** More events than the dispatch queue holds, so a wedged dispatcher makes it overflow. */
    private static void flood(InsightsSsePublisher publisher) {
        for (int i = 0; i < 400; i++) {
            publisher.onTick(i, Map.of("a", 1.0));
        }
    }

    private static long overflowWarnings(LogCapture logs) {
        return logs.events().stream()
                .filter(event -> event.getFormattedMessage().contains("dispatch queue full"))
                .count();
    }
}

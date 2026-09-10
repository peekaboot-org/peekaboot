package org.peekaboot.backend.insights.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.AggregateStats;
import org.peekaboot.backend.testsupport.FailingWriteResponse;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.StringHttpMessageConverter;
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

        assertThat(emitter.getTimeout()).isEqualTo(InsightsSsePublisher.EMITTER_TIMEOUT.toMillis());
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
     * completes the stream.
     */
    @Test
    void stopCompletesAPeerWhoseWriteFinishesWithinTheGrace() throws Exception {
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
            // polled tightly so the release lands well inside the grace, load or no load
            await().atMost(Duration.ofSeconds(3))
                    .pollDelay(Duration.ZERO)
                    .pollInterval(Duration.ofMillis(5))
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
            assertThat(logs.appender().list).singleElement().satisfies(event -> {
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

    /** More events than the dispatch queue holds, so a wedged dispatcher makes it overflow. */
    private static void flood(InsightsSsePublisher publisher) {
        for (int i = 0; i < 400; i++) {
            publisher.onTick(i, Map.of("a", 1.0));
        }
    }

    private static long overflowWarnings(LogCapture logs) {
        return logs.appender().list.stream()
                .filter(event -> event.getFormattedMessage().contains("dispatch queue full"))
                .count();
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
            assertThat(logs.appender().list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage()).contains("stopped reading");
            });
        } finally {
            releaseWrite.release();
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
                    .untilAsserted(() -> assertThat(logs.appender().list).hasSize(1));
            assertThat(logs.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage())
                        .isEqualTo(
                                "Dropping insights SSE subscriber after send failure: java.io.IOException: Broken pipe");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(publisher.subscriberCount()).isZero();
        }
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
            assertThat(logs.appender().list).anySatisfy(event -> {
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
     * One stream per response: Spring fetches the output stream once per converter write,
     * and a send spans several, so a fresh stream each time would wedge the same send again.
     */
    private static MockHttpServletResponse wedgingOnTheFirstWrite(CountDownLatch writeStarted, Semaphore releaseWrite) {
        return new MockHttpServletResponse() {
            private final ServletOutputStream stream = new ServletOutputStream() {
                private boolean wedged;

                @Override
                public void write(int b) {
                    if (!wedged) {
                        wedged = true;
                        writeStarted.countDown();
                        // a container's socket write is not interruptible either
                        releaseWrite.acquireUninterruptibly();
                    }
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

        /** What the async dispatch would hand back: null for a completed stream, or the exception. */
        Object result() {
            return WebAsyncUtils.getAsyncManager(request).getConcurrentResult();
        }
    }
}

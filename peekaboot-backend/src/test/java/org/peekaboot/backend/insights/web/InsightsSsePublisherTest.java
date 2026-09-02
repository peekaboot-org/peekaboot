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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.AggregateStats;
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

    /** Every publisher a test builds, stopped afterwards so no sender or heartbeat thread outlives its test. */
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
        // complete() is overridden in newEmitter() to detach the subscriber synchronously
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
            publisher.broadcast("tick", "{}");
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

    @Test
    void onTickReturnsImmediatelyWhileDeliveryHappensOnTheSenderThread() throws Exception {
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        CountDownLatch sendCompleted = new CountDownLatch(1);

        // A publisher whose emitter blocks on send() until released, standing in
        // for a slow/wedged client. If onTick sent synchronously, calling it
        // below would block on this same latch and the test would time out.
        InsightsSsePublisher slowPublisher = tracked(new InsightsSsePublisher(new ObjectMapper()) {
            @Override
            SseEmitter newEmitter() {
                return new SseEmitter(0L) {
                    @Override
                    public void send(SseEventBuilder builder) throws IOException {
                        sendStarted.countDown();
                        try {
                            releaseSend.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        sendCompleted.countDown();
                    }
                };
            }
        });
        slowPublisher.subscribe();

        long start = System.nanoTime();
        slowPublisher.onTick(1_000, Map.of("a", 1.0));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).as("onTick must not block on the emitter send").isLessThan(500);

        assertThat(sendStarted.await(2, TimeUnit.SECONDS))
                .as("the queued event reaches the emitter's send")
                .isTrue();
        releaseSend.countDown();
        assertThat(sendCompleted.await(2, TimeUnit.SECONDS))
                .as("send eventually completes off the caller's thread")
                .isTrue();
    }

    @Test
    void dispatchThreadKeepsDrainingAfterRapidDisconnectAndResubscribe() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);

        // Proxy for the exit/restart race: a subscriber disconnects (draining the
        // emitter list to empty, which the dispatch loop's exit check may or may
        // not have observed yet) and a new one immediately replaces it - the
        // scenario a browser tab refresh (EventSource reconnect) produces. This
        // can't force the exact nanosecond interleaving deterministically, but it
        // exercises the real disconnect -> resubscribe -> deliver path end to end;
        // correctness under the race itself is argued by ManagedLoop's atomic
        // exit-under-synchronized reasoning (see its class-level Javadoc).
        InsightsSsePublisher publisher = tracked(new InsightsSsePublisher(new ObjectMapper()) {
            @Override
            void onDelivered(String eventName) {
                delivered.countDown();
            }
        });

        var firstEmitter = publisher.subscribe();
        firstEmitter.complete();
        publisher.subscribe();

        publisher.onTick(1_000, Map.of("a", 1.0));

        assertThat(delivered.await(3, TimeUnit.SECONDS))
                .as("dispatch thread keeps draining after a rapid disconnect/resubscribe")
                .isTrue();
    }

    @Test
    void nothingIsQueuedWhileNobodyIsSubscribed() throws Exception {
        BlockingQueue<String> broadcasts = new LinkedBlockingQueue<>();
        InsightsSsePublisher publisher = tracked(new InsightsSsePublisher(new ObjectMapper()) {
            @Override
            void broadcast(String eventName, String json) {
                broadcasts.add(eventName);
            }
        });

        // More events than the queue holds: with nobody watching, none of them may
        // be queued at all - otherwise a dashboard-less app fills the queue, logs
        // the "queue full" warning, and buries the first viewer under stale events.
        for (int i = 0; i < 300; i++) {
            publisher.onTick(i, Map.of("a", 1.0));
        }
        publisher.subscribe();

        assertThat(broadcasts.poll(500, TimeUnit.MILLISECONDS))
                .as("no stale burst on the first subscribe")
                .isNull();
        publisher.onTick(9_000, Map.of("a", 1.0));
        assertThat(broadcasts.poll(3, TimeUnit.SECONDS))
                .as("fresh events still flow")
                .isEqualTo("tick");
    }

    @Test
    void firstSubscriberStartsFromAnEmptyQueue() throws Exception {
        CountDownLatch broadcastStarted = new CountDownLatch(1);
        CountDownLatch releaseBroadcast = new CountDownLatch(1);
        BlockingQueue<String> broadcasts = new LinkedBlockingQueue<>();

        // Wedging the dispatch loop inside broadcast() is what makes the leftover
        // state reachable: events queued for a subscriber that disconnects before
        // they are drained.
        InsightsSsePublisher publisher = tracked(new InsightsSsePublisher(new ObjectMapper()) {
            @Override
            void broadcast(String eventName, String json) {
                broadcasts.add(eventName);
                if (broadcastStarted.getCount() > 0) {
                    broadcastStarted.countDown();
                    try {
                        releaseBroadcast.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });

        SseEmitter first = publisher.subscribe();
        publisher.onTick(1_000, Map.of("a", 1.0));
        assertThat(broadcastStarted.await(3, TimeUnit.SECONDS))
                .as("dispatch wedged in broadcast")
                .isTrue();
        assertThat(broadcasts.take()).isEqualTo("tick");

        publisher.onTick(2_000, Map.of("a", 2.0)); // queues up behind the wedge
        first.complete(); // ... and its subscriber leaves
        publisher.subscribe(); // 0 -> 1: must start from an empty queue
        releaseBroadcast.countDown();

        assertThat(broadcasts.poll(1, TimeUnit.SECONDS))
                .as("the event queued for the departed subscriber is dropped")
                .isNull();
        publisher.onTick(3_000, Map.of("a", 3.0));
        assertThat(broadcasts.poll(3, TimeUnit.SECONDS))
                .as("fresh events still flow")
                .isEqualTo("tick");
    }

    @Test
    void dispatchLoopSurvivesAFailingBroadcast() throws Exception {
        BlockingQueue<String> broadcasts = new LinkedBlockingQueue<>();
        AtomicBoolean failNext = new AtomicBoolean(true);
        InsightsSsePublisher publisher = tracked(new InsightsSsePublisher(new ObjectMapper()) {
            @Override
            void broadcast(String eventName, String json) {
                if (failNext.getAndSet(false)) {
                    throw new IllegalStateException("expected failure from dispatchLoopSurvivesAFailingBroadcast");
                }
                broadcasts.add(eventName);
            }
        });

        publisher.subscribe();
        try (LogCapture logs = LogCapture.attach(InsightsSsePublisher.class)) {
            publisher.onTick(1_000, Map.of("a", 1.0));
            publisher.onTick(2_000, Map.of("a", 2.0));

            assertThat(broadcasts.poll(3, TimeUnit.SECONDS))
                    .as("the loop keeps running after a step threw")
                    .isEqualTo("tick");
            assertThat(logs.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Insights SSE loop peekaboot-insights-sse-dispatch step failed; continuing");
            });
        }
    }

    @Test
    void queueOverflowWarnsOncePerEpisode() throws Exception {
        BlockingQueue<String> broadcasts = new LinkedBlockingQueue<>();
        AtomicReference<CountDownLatch> gate = new AtomicReference<>(new CountDownLatch(1));
        AtomicReference<CountDownLatch> wedged = new AtomicReference<>(new CountDownLatch(1));

        // Wedging the dispatch loop at a gate we open and close is what makes an
        // overflow episode reproducible: while the gate is shut nothing drains. The
        // `wedged` latch is the rendezvous that makes it DETERMINISTIC: flooding may
        // only start once the dispatcher is provably parked at the gate - otherwise a
        // poll landing mid-flood frees a slot, that offer succeeds, and the episode
        // legitimately splits in two. The wedge signal reads the same gate instance it
        // then awaits, so a broadcast slipping through a just-opened gate can never
        // count down a fresh latch.
        InsightsSsePublisher publisher = tracked(new InsightsSsePublisher(new ObjectMapper()) {
            @Override
            void broadcast(String eventName, String json) {
                try {
                    CountDownLatch currentGate = gate.get();
                    if (currentGate.getCount() > 0) {
                        wedged.get().countDown();
                    }
                    currentGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                broadcasts.add(eventName);
            }
        });
        publisher.subscribe();

        try (LogCapture logs = LogCapture.attach(InsightsSsePublisher.class)) {
            publisher.onTick(0, Map.of("a", 0.0));
            assertThat(wedged.get().await(5, TimeUnit.SECONDS))
                    .as("dispatcher parked at the gate before the flood")
                    .isTrue();
            flood(publisher);
            assertThat(overflowWarnings(logs))
                    .as("the first overflow episode warns")
                    .isEqualTo(1);

            gate.get().countDown();
            // taking well over half the queue's capacity proves the backlog drained,
            // so the offers of the next flood start out succeeding again
            for (int i = 0; i < 200; i++) {
                assertThat(broadcasts.poll(3, TimeUnit.SECONDS))
                        .as("backlog drains")
                        .isEqualTo("tick");
            }

            // fresh wedge latch BEFORE the gate closes: any broadcast reading the new
            // (closed) gate signals the new latch; one reading the old (open) gate
            // sails through without touching it - no hang, no false rendezvous. The
            // tick guarantees something arrives to park at the closed gate even when
            // the dispatcher has already drained the whole backlog.
            wedged.set(new CountDownLatch(1));
            gate.set(new CountDownLatch(1));
            publisher.onTick(1_000, Map.of("a", 1.0));
            assertThat(wedged.get().await(5, TimeUnit.SECONDS))
                    .as("dispatcher parked again before the second flood")
                    .isTrue();
            flood(publisher);
            assertThat(overflowWarnings(logs))
                    .as("a second episode is not silent")
                    .isEqualTo(2);
        }
    }

    /** More events than the dispatch queue holds, so a wedged loop makes it overflow. */
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
        CountDownLatch wedgeReleased = new CountDownLatch(1);
        BlockingQueue<String> healthyDeliveries = new LinkedBlockingQueue<>();
        AtomicBoolean firstEmitter = new AtomicBoolean(true);
        InsightsSsePublisher publisher = tracked(new InsightsSsePublisher(new ObjectMapper()) {
            @Override
            SseEmitter newEmitter() {
                if (firstEmitter.getAndSet(false)) {
                    return new SseEmitter(0L) {
                        @Override
                        public void send(SseEventBuilder builder) throws IOException {
                            try {
                                wedgeReleased.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    };
                }
                return new SseEmitter(0L) {
                    @Override
                    public void send(SseEventBuilder builder) throws IOException {
                        healthyDeliveries.add("sent");
                    }
                };
            }
        });
        try {
            publisher.subscribe(); // the peer that stops reading
            publisher.subscribe(); // the healthy dashboard behind it

            publisher.onTick(1_000, Map.of("a", 1.0));

            assertThat(healthyDeliveries.poll(3, TimeUnit.SECONDS))
                    .as("the healthy subscriber receives while the other peer's send is wedged")
                    .isNotNull();
        } finally {
            wedgeReleased.countDown();
        }
    }

    /**
     * The backed-up lane is how a peer that stops reading is detected: the wedged send
     * blocks its sender, the lane fills, and the overflow drops the subscriber - as
     * routine as a failed send, so a one-line DEBUG message, not a warning.
     */
    @Test
    void aPeerThatStopsReadingIsDroppedOnceItsLaneOverflows() {
        CountDownLatch wedgeReleased = new CountDownLatch(1);
        InsightsSsePublisher publisher = tracked(new InsightsSsePublisher(new ObjectMapper()) {
            @Override
            SseEmitter newEmitter() {
                return new SseEmitter(0L) {
                    @Override
                    public void send(SseEventBuilder builder) throws IOException {
                        try {
                            wedgeReleased.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                };
            }
        });
        try {
            publisher.subscribe();

            try (LogCapture logs = LogCapture.attach(InsightsSsePublisher.class, Level.DEBUG)) {
                // the sender holds at most one event in flight, so this overflows the lane
                for (int i = 0; i < InsightsSsePublisher.SUBSCRIBER_QUEUE_CAPACITY + 2; i++) {
                    publisher.broadcast("tick", "{}");
                }

                assertThat(publisher.subscriberCount())
                        .as("the wedged peer is dropped on lane overflow")
                        .isZero();
                assertThat(logs.appender().list).anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                    assertThat(event.getFormattedMessage()).contains("stopped reading");
                });
            }
        } finally {
            wedgeReleased.countDown();
        }
    }

    /**
     * A peer that went away is routine, not an incident: the reason is logged at DEBUG as
     * one line, without the stack trace of the servlet container's broken pipe.
     */
    @Test
    void aSubscriberWhoseSendFailsIsDroppedWithAOneLineDebugMessage() {
        InsightsSsePublisher publisher = tracked(new InsightsSsePublisher(new ObjectMapper()) {
            @Override
            SseEmitter newEmitter() {
                return new SseEmitter(0L) {
                    @Override
                    public void send(SseEventBuilder builder) throws IOException {
                        throw new IOException("Broken pipe");
                    }
                };
            }
        });
        publisher.subscribe();

        try (LogCapture logs = LogCapture.attach(InsightsSsePublisher.class, Level.DEBUG)) {
            publisher.broadcast("tick", "{}");

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
        }
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

    @Test
    void tickPayloadMapsNaNToNull() {
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("a", 1.5);
        values.put("b", Double.NaN);
        String json = publisher.tickJson(7_000, values);
        assertThat(json).isEqualTo("{\"epochMs\":7000,\"values\":{\"a\":1.5,\"b\":null}}");
    }

    private InsightsSsePublisher tracked(InsightsSsePublisher publisher) {
        publishers.add(publisher);
        return publisher;
    }

    /** A response whose first write blocks until released - a peer that has stopped reading. */
    private static MockHttpServletResponse wedgingOnTheFirstWrite(CountDownLatch writeStarted, Semaphore releaseWrite) {
        return new MockHttpServletResponse() {
            @Override
            public ServletOutputStream getOutputStream() {
                return new ServletOutputStream() {
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

    /** The seven statistics the dashboard charts, by name; the sample count stays server-side. */
    @Test
    void rollupPayloadCarriesTheSevenStatsAndNotTheSampleCount() {
        var entry = AggregateStats.of(new double[] {2.0});
        String json = publisher.rollupJson(1, 60_000, Map.of("a", entry));
        assertThat(json)
                .isEqualTo("{\"level\":1,\"epochMs\":60000,\"entries\":{\"a\":"
                        + "{\"min\":2.0,\"max\":2.0,\"avg\":2.0,\"median\":2.0,\"p90\":2.0,\"p95\":2.0,\"p99\":2.0}}}");
    }
}

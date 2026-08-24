package org.peekaboot.backend.insights.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.testsupport.LogCapture;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

class InsightsSsePublisherTest {

    private final InsightsSsePublisher publisher = new InsightsSsePublisher(new ObjectMapper());

    @Test
    void tracksSubscribers() {
        assertThat(publisher.subscriberCount()).isZero();
        var emitter = publisher.subscribe();
        assertThat(publisher.subscriberCount()).isEqualTo(1);
        emitter.complete();
        // completion callback runs synchronously for SseEmitter.complete()
        assertThat(publisher.subscriberCount()).isZero();
    }

    @Test
    void onTickReturnsImmediatelyWhileDeliveryHappensOnDispatchThread() throws Exception {
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        CountDownLatch sendCompleted = new CountDownLatch(1);

        // A publisher whose emitter blocks on send() until released, standing in
        // for a slow/wedged client. If onTick sent synchronously, calling it
        // below would block on this same latch and the test would time out.
        InsightsSsePublisher slowPublisher = new InsightsSsePublisher(new ObjectMapper()) {
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
        };
        slowPublisher.subscribe();

        long start = System.nanoTime();
        slowPublisher.onTick(1_000, Map.of("a", 1.0), Map.of());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).as("onTick must not block on the emitter send").isLessThan(500);

        assertThat(sendStarted.await(2, TimeUnit.SECONDS))
                .as("dispatch thread picks up the queued event")
                .isTrue();
        releaseSend.countDown();
        assertThat(sendCompleted.await(2, TimeUnit.SECONDS))
                .as("send eventually completes on the dispatch thread")
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
        InsightsSsePublisher publisher = new InsightsSsePublisher(new ObjectMapper()) {
            @Override
            void onDelivered(String eventName) {
                delivered.countDown();
            }
        };

        var firstEmitter = publisher.subscribe();
        firstEmitter.complete();
        publisher.subscribe();

        publisher.onTick(1_000, Map.of("a", 1.0), Map.of());

        assertThat(delivered.await(3, TimeUnit.SECONDS))
                .as("dispatch thread keeps draining after a rapid disconnect/resubscribe")
                .isTrue();
    }

    @Test
    void nothingIsQueuedWhileNobodyIsSubscribed() throws Exception {
        BlockingQueue<String> broadcasts = new LinkedBlockingQueue<>();
        InsightsSsePublisher publisher = new InsightsSsePublisher(new ObjectMapper()) {
            @Override
            void broadcast(String eventName, String json) {
                broadcasts.add(eventName);
            }
        };

        // More events than the queue holds: with nobody watching, none of them may
        // be queued at all - otherwise a dashboard-less app fills the queue, logs
        // the "queue full" warning, and buries the first viewer under stale events.
        for (int i = 0; i < 300; i++) {
            publisher.onTick(i, Map.of("a", 1.0), Map.of());
        }
        publisher.subscribe();

        assertThat(broadcasts.poll(500, TimeUnit.MILLISECONDS))
                .as("no stale burst on the first subscribe")
                .isNull();
        publisher.onTick(9_000, Map.of("a", 1.0), Map.of());
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
        InsightsSsePublisher publisher = new InsightsSsePublisher(new ObjectMapper()) {
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
        };

        SseEmitter first = publisher.subscribe();
        publisher.onTick(1_000, Map.of("a", 1.0), Map.of());
        assertThat(broadcastStarted.await(3, TimeUnit.SECONDS))
                .as("dispatch wedged in broadcast")
                .isTrue();
        assertThat(broadcasts.take()).isEqualTo("tick");

        publisher.onTick(2_000, Map.of("a", 2.0), Map.of()); // queues up behind the wedge
        first.complete(); // ... and its subscriber leaves
        publisher.subscribe(); // 0 -> 1: must start from an empty queue
        releaseBroadcast.countDown();

        assertThat(broadcasts.poll(1, TimeUnit.SECONDS))
                .as("the event queued for the departed subscriber is dropped")
                .isNull();
        publisher.onTick(3_000, Map.of("a", 3.0), Map.of());
        assertThat(broadcasts.poll(3, TimeUnit.SECONDS))
                .as("fresh events still flow")
                .isEqualTo("tick");
    }

    @Test
    void dispatchLoopSurvivesAFailingBroadcast() throws Exception {
        BlockingQueue<String> broadcasts = new LinkedBlockingQueue<>();
        AtomicBoolean failNext = new AtomicBoolean(true);
        InsightsSsePublisher publisher = new InsightsSsePublisher(new ObjectMapper()) {
            @Override
            void broadcast(String eventName, String json) {
                if (failNext.getAndSet(false)) {
                    throw new IllegalStateException("expected failure from dispatchLoopSurvivesAFailingBroadcast");
                }
                broadcasts.add(eventName);
            }
        };

        publisher.subscribe();
        publisher.onTick(1_000, Map.of("a", 1.0), Map.of());
        publisher.onTick(2_000, Map.of("a", 2.0), Map.of());

        assertThat(broadcasts.poll(3, TimeUnit.SECONDS))
                .as("the loop keeps running after a step threw")
                .isEqualTo("tick");
    }

    @Test
    void queueOverflowWarnsOncePerEpisode() throws Exception {
        BlockingQueue<String> broadcasts = new LinkedBlockingQueue<>();
        AtomicReference<CountDownLatch> gate = new AtomicReference<>(new CountDownLatch(1));

        // Wedging the dispatch loop at a gate we open and close is what makes an
        // overflow episode reproducible: while the gate is shut nothing drains.
        InsightsSsePublisher publisher = new InsightsSsePublisher(new ObjectMapper()) {
            @Override
            void broadcast(String eventName, String json) {
                try {
                    gate.get().await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                broadcasts.add(eventName);
            }
        };
        publisher.subscribe();

        try (LogCapture logs = LogCapture.attach(InsightsSsePublisher.class)) {
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

            gate.set(new CountDownLatch(1));
            flood(publisher);
            assertThat(overflowWarnings(logs))
                    .as("a second episode is not silent")
                    .isEqualTo(2);
        }
    }

    /** More events than the dispatch queue holds, so a wedged loop makes it overflow. */
    private static void flood(InsightsSsePublisher publisher) {
        for (int i = 0; i < 400; i++) {
            publisher.onTick(i, Map.of("a", 1.0), Map.of());
        }
    }

    private static long overflowWarnings(LogCapture logs) {
        return logs.appender().list.stream()
                .filter(event -> event.getFormattedMessage().contains("dispatch queue full"))
                .count();
    }

    @Test
    void stopCompletesSubscribers() {
        publisher.start();
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
        publisher.start();
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
        String json = publisher.tickJson(7_000, values, Map.of());
        assertThat(json).isEqualTo("{\"epochMs\":7000,\"values\":{\"a\":1.5,\"b\":null},\"tiles\":{}}");
    }

    @Test
    void rollupPayloadCarriesAllStats() {
        var entry = org.peekaboot.backend.insights.AggregateStats.of(new double[] {2.0});
        String json = publisher.rollupJson(1, 60_000, Map.of("a", entry));
        assertThat(json).contains("\"level\":1").contains("\"avg\":2.0").contains("\"p99\":2.0");
    }
}

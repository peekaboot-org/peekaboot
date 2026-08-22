package org.peekaboot.backend.insights.web;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
                .as("dispatch thread picks up the queued event").isTrue();
        releaseSend.countDown();
        assertThat(sendCompleted.await(2, TimeUnit.SECONDS))
                .as("send eventually completes on the dispatch thread").isTrue();
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
                .as("dispatch thread keeps draining after a rapid disconnect/resubscribe").isTrue();
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
        var entry = org.peekaboot.backend.insights.AggregateStats.of(new double[]{2.0});
        String json = publisher.rollupJson(1, 60_000, Map.of("a", entry));
        assertThat(json).contains("\"level\":1").contains("\"avg\":2.0").contains("\"p99\":2.0");
    }
}

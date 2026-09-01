package org.peekaboot.backend.insights.web;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.peekaboot.backend.insights.AggregateStats;
import org.peekaboot.backend.insights.InsightsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * Fans collector events out to all connected dashboard SSE clients.
 *
 * <p>A {@link SmartLifecycle} so that context shutdown completes every open
 * emitter: an emitter left open holds its async servlet request, and the
 * container's graceful shutdown then waits for it (measured: 30s at JVM exit).
 */
public class InsightsSsePublisher implements InsightsCollector.Listener, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InsightsSsePublisher.class);

    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private static final Duration DISPATCH_POLL_TIMEOUT = Duration.ofSeconds(1);
    private static final int QUEUE_CAPACITY = 256;

    private final ObjectMapper objectMapper;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final BlockingQueue<SseEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    /**
     * The single monitor for subscriber-list, queue, and loop lifecycle state. A
     * dedicated private object rather than {@code this} so nothing outside this
     * class can ever contend on (or deadlock against) the internal locking.
     */
    private final Object lock = new Object();
    /**
     * Guarded by the publisher's {@code lock}, like every other access to the queue:
     * set when an episode of overflow starts warning, cleared as soon as an offer
     * succeeds again, so a later episode is never swallowed as a duplicate.
     */
    private boolean queueOverflowWarned;

    private final ManagedLoop heartbeatLoop = new ManagedLoop("peekaboot-insights-sse-heartbeat", this::heartbeatStep);
    private final ManagedLoop dispatchLoop = new ManagedLoop("peekaboot-insights-sse-dispatch", this::dispatchStep);
    /**
     * True from construction on: the publisher serves subscribers as soon as it
     * exists, so start() has nothing to do. The flag is here to make stop() final -
     * the connector outlives this lifecycle phase, so a client reconnecting during
     * shutdown must not be handed an emitter that would hold the shutdown open.
     */
    private volatile boolean running = true;

    public InsightsSsePublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void start() {
        running = true;
    }

    /**
     * Stops the loops and completes every open emitter, so nothing outlives the context.
     *
     * <p>The loops go first: a loop thread wedged in a blocking send() holds that
     * emitter's write lock, and complete() would then queue up behind it while the
     * loop kept feeding further events into the same wedge. Emitters are snapshotted
     * and cleared under the monitor before either step, so a broadcast racing this
     * has nothing left to iterate.
     */
    @Override
    public void stop() {
        List<SseEmitter> open;
        synchronized (lock) {
            running = false;
            open = List.copyOf(emitters);
            emitters.clear();
            queue.clear();
        }
        dispatchLoop.stop();
        heartbeatLoop.stop();
        for (SseEmitter emitter : open) {
            try {
                emitter.complete();
            } catch (RuntimeException e) {
                log.debug("Failed to complete an insights SSE subscriber on shutdown", e);
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = newEmitter();
        boolean accepted;
        synchronized (lock) {
            accepted = running;
            if (accepted) {
                // Anything still queued belongs to a subscriber that has since left;
                // delivering it would burst stale history into this fresh client.
                if (emitters.isEmpty()) {
                    queue.clear();
                    queueOverflowWarned = false;
                }
                emitters.add(emitter);
            }
        }
        if (!accepted) {
            emitter.complete();
            return emitter;
        }
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        heartbeatLoop.startIfNeeded();
        dispatchLoop.startIfNeeded();
        return emitter;
    }

    /**
     * complete()/completeWithError() are overridden because Spring's container-driven
     * onCompletion/onError callbacks fire only inside a real request dispatch; when the
     * dispatch loop here completes an emitter itself (or a unit test does), no Handler is
     * registered to invoke them and the emitter would stay in {@code emitters}.
     * Package-visible (rather than inlined into subscribe()) so tests can override the
     * emitter's send behavior.
     */
    SseEmitter newEmitter() {
        return new SseEmitter(0L) {
            @Override
            public void complete() {
                super.complete();
                emitters.remove(this);
            }

            @Override
            public void completeWithError(Throwable ex) {
                super.completeWithError(ex);
                emitters.remove(this);
            }
        };
    }

    public int subscriberCount() {
        return emitters.size();
    }

    @Override
    public void onTick(long epochMs, Map<String, Double> values, Map<String, Double> tiles) {
        enqueue("tick", () -> tickJson(epochMs, values, tiles));
    }

    @Override
    public void onRollUp(int level, long epochMs, Map<String, AggregateStats> entries) {
        enqueue("rollup", () -> rollupJson(level, epochMs, entries));
    }

    String tickJson(long epochMs, Map<String, Double> values, Map<String, Double> tiles) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("epochMs", epochMs);
        payload.put("values", nullSafeMap(values));
        payload.put("tiles", nullSafeMap(tiles));
        return objectMapper.writeValueAsString(payload);
    }

    String rollupJson(int level, long epochMs, Map<String, AggregateStats> entries) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("level", level);
        payload.put("epochMs", epochMs);

        Map<String, Object> entryPayloads = new LinkedHashMap<>();
        for (Map.Entry<String, AggregateStats> entry : entries.entrySet()) {
            AggregateStats stats = entry.getValue();
            Map<String, Object> statsPayload = new LinkedHashMap<>();
            statsPayload.put("min", nullSafe(stats.min()));
            statsPayload.put("max", nullSafe(stats.max()));
            statsPayload.put("avg", nullSafe(stats.avg()));
            statsPayload.put("median", nullSafe(stats.median()));
            statsPayload.put("p90", nullSafe(stats.p90()));
            statsPayload.put("p95", nullSafe(stats.p95()));
            statsPayload.put("p99", nullSafe(stats.p99()));
            entryPayloads.put(entry.getKey(), statsPayload);
        }
        payload.put("entries", entryPayloads);

        return objectMapper.writeValueAsString(payload);
    }

    private static Map<String, Object> nullSafeMap(Map<String, Double> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            result.put(entry.getKey(), nullSafe(entry.getValue()));
        }
        return result;
    }

    /**
     * Queues an event for the dispatch thread instead of sending on the caller's
     * thread: onTick/onRollUp run on the collector's tick/roll-up virtual threads,
     * and a blocking synchronous send to a slow client must never delay sampling.
     * The payload is only rendered to JSON when the dispatch thread picks the event
     * up, keeping serialization off the collector threads as well - safe because
     * the collector hands out a fresh, unshared map per event.
     *
     * <p>Nothing is queued while no one is subscribed: an unwatched app would
     * otherwise fill the queue within the hour and log a "queue full" warning
     * during entirely normal operation. Bounded; on overflow the oldest queued
     * event is dropped and a warning logged once per overflow episode - a stream
     * that recovers and later floods again has to say so a second time.
     */
    private void enqueue(String eventName, Supplier<String> json) {
        SseEvent event = new SseEvent(eventName, json);
        synchronized (lock) {
            if (emitters.isEmpty()) {
                return;
            }
            if (queue.offer(event)) {
                queueOverflowWarned = false;
                return;
            }
            queue.poll();
            queue.offer(event);
            if (!queueOverflowWarned) {
                queueOverflowWarned = true;
                log.warn("Insights SSE dispatch queue full ({}); dropping oldest events", QUEUE_CAPACITY);
            }
        }
    }

    /**
     * Sends one event to every subscriber, in order, on the single dispatch thread.
     * A peer that has stopped reading therefore holds up the subscribers behind it
     * until the servlet container's write timeout fires. The heartbeat is no help
     * there: its send() to that same emitter takes the emitter's write lock, which
     * the stuck send already holds, so it blocks rather than detecting the dead peer.
     * Accepted for a dev tool, where a handful of dashboards watch a single app.
     *
     * <p>Package-visible so tests can stand in for (or wedge) the delivery step.
     */
    void broadcast(String eventName, String json) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(json));
                onDelivered(eventName);
            } catch (IOException | IllegalStateException e) {
                log.debug("Dropping insights SSE subscriber after send failure", e);
                emitter.completeWithError(e);
            }
        }
    }

    /** Test seam: invoked after each event successfully handed to an emitter's send(). No-op in production. */
    void onDelivered(String eventName) {
        // production no-op; tests override to observe delivery
    }

    private void heartbeatStep() throws InterruptedException {
        Thread.sleep(HEARTBEAT_INTERVAL);
        heartbeat();
    }

    void heartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("hb"));
            } catch (IOException | IllegalStateException e) {
                log.debug("Dropping insights SSE subscriber after heartbeat failure", e);
                emitter.completeWithError(e);
            }
        }
    }

    /**
     * Polls with a timeout (rather than a blocking take()) purely so the loop
     * can periodically re-check whether emitters have drained to zero; a real
     * queued event never waits longer than this poll timeout, since offer()
     * wakes a blocked poll() immediately.
     */
    private void dispatchStep() throws InterruptedException {
        SseEvent event = queue.poll(DISPATCH_POLL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (event != null) {
            broadcast(event.name(), event.json().get());
        }
    }

    static Double nullSafe(double value) {
        return Double.isNaN(value) ? null : value;
    }

    private record SseEvent(String name, Supplier<String> json) {}

    @FunctionalInterface
    private interface LoopStep {
        void run() throws InterruptedException;
    }

    /**
     * Lazily starts a named virtual thread that repeats {@code step} while
     * subscribers remain, and stops itself once they don't. Starting and the
     * exit decision both happen under the publisher's private {@code lock},
     * so a subscribe() racing the loop's own "should I stop?" check can never
     * observe a stale non-null thread handle for a loop that has already
     * committed to exiting without rechecking - the two are mutually exclusive
     * on the same monitor, closing that class of race at the source (shared by
     * both the heartbeat and dispatch loops).
     */
    private final class ManagedLoop {
        private final String name;
        private final LoopStep step;
        private Thread thread;

        ManagedLoop(String name, LoopStep step) {
            this.name = name;
            this.step = step;
        }

        void startIfNeeded() {
            synchronized (lock) {
                if (thread != null) {
                    return;
                }
                thread = Thread.ofVirtual().name(name).unstarted(this::run);
                thread.start();
            }
        }

        /**
         * Interrupts and joins the loop thread. The handle is taken (and cleared)
         * under the monitor but the join happens outside it - the loop acquires
         * the same monitor on every iteration, and an interrupt does not free a
         * thread blocked on monitor entry.
         */
        void stop() {
            Thread loopThread;
            synchronized (lock) {
                loopThread = thread;
                thread = null;
            }
            if (loopThread == null) {
                return;
            }
            loopThread.interrupt();
            try {
                loopThread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void run() {
            while (true) {
                synchronized (lock) {
                    if (emitters.isEmpty()) {
                        thread = null;
                        return;
                    }
                }
                try {
                    step.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    synchronized (lock) {
                        thread = null;
                    }
                    return;
                } catch (RuntimeException e) {
                    // A failed step must not take the loop with it: the thread
                    // handle would stay set and no subscribe() would ever restart it.
                    log.warn("Insights SSE loop {} step failed; continuing", name, e);
                }
            }
        }
    }
}

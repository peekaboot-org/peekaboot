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
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.insights.AggregateStats;
import org.peekaboot.backend.insights.InsightsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * Fans collector events out to all connected dashboard SSE clients: the single dispatch
 * thread renders each event once and offers it to a bounded per-subscriber lane, whose
 * own sender thread performs the blocking send - no peer can stall another.
 *
 * <p>A {@link SmartLifecycle} so that context shutdown completes every open
 * emitter: an emitter left open holds its async servlet request, and the
 * container's graceful shutdown then waits for it (measured: 30s at JVM exit).
 */
public class InsightsSsePublisher implements InsightsCollector.Listener, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InsightsSsePublisher.class);

    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private static final Duration DISPATCH_POLL_TIMEOUT = Duration.ofSeconds(1);
    /**
     * Events awaiting the dispatch thread. A dispatch step is one JSON render plus
     * non-blocking lane offers, so a backlog this deep (over half an hour of ticks and
     * roll-ups) only ever means the dispatch thread itself is stuck.
     */
    private static final int QUEUE_CAPACITY = 256;
    /**
     * Each subscriber's own send lane. A healthy peer drains it as fast as the dispatch
     * thread fills it and a burst spans a handful of events, so a backlog this deep only
     * ever means a peer that has stopped reading.
     */
    static final int SUBSCRIBER_QUEUE_CAPACITY = 32;
    /**
     * Each emitter pins one of the container's async requests, so the number a single
     * client can open is bounded; a handful of dashboards on one app is the use case.
     */
    static final int MAX_SUBSCRIBERS = 32;
    /**
     * A stream that outlives this is completed server-side and the browser's
     * EventSource reconnects on its own, so no emitter is held open indefinitely on
     * behalf of a peer that has silently gone away.
     */
    static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(5);

    private final ObjectMapper objectMapper;
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
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
     * True from construction; only stop() clears it, so a client reconnecting during
     * shutdown is not handed an emitter that would hold the shutdown open.
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
     * <p>The loops go first, and each sender is interrupted before its emitter is
     * completed: a sender wedged in a blocking send() holds that emitter's write lock,
     * and complete() would then queue up behind it while events kept feeding the same
     * wedge. Subscribers are snapshotted and cleared under the monitor before either
     * step, so a broadcast racing this has nothing left to iterate.
     */
    @Override
    public void stop() {
        List<Subscriber> open;
        synchronized (lock) {
            running = false;
            open = List.copyOf(subscribers);
            subscribers.clear();
            queue.clear();
        }
        dispatchLoop.stop();
        heartbeatLoop.stop();
        for (Subscriber subscriber : open) {
            subscriber.sender.interrupt();
            try {
                subscriber.emitter.complete();
            } catch (RuntimeException e) {
                log.debug("Failed to complete an insights SSE subscriber on shutdown", e);
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Hands out a stream, or a 503 once {@link #MAX_SUBSCRIBERS} are open - a client
     * that gets it simply retries later; nothing is lost since there is no replay anyway.
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = newEmitter();
        Subscriber subscriber = new Subscriber(emitter);
        boolean accepted;
        synchronized (lock) {
            accepted = running;
            if (accepted && subscribers.size() >= MAX_SUBSCRIBERS) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "Insights stream subscriber limit reached");
            }
            if (accepted) {
                // Anything still queued belongs to a subscriber that has since left;
                // delivering it would burst stale history into this fresh client.
                if (subscribers.isEmpty()) {
                    queue.clear();
                    queueOverflowWarned = false;
                }
                subscribers.add(subscriber);
                // Started under the same lock that stop() and a lane overflow interrupt
                // it from, so the sender is never interrupted before it has been started.
                subscriber.sender.start();
            }
        }
        if (!accepted) {
            emitter.complete();
            return emitter;
        }
        emitter.onCompletion(() -> removeSubscriber(emitter));
        emitter.onError(e -> removeSubscriber(emitter));
        heartbeatLoop.startIfNeeded();
        dispatchLoop.startIfNeeded();
        return emitter;
    }

    /** Package-visible (rather than inlined into subscribe()) so tests can override the emitter's send behavior. */
    SseEmitter newEmitter() {
        return new SubscriberEmitter();
    }

    int subscriberCount() {
        return subscribers.size();
    }

    /**
     * Detaches the subscriber whose emitter this is and interrupts its sender; a no-op for
     * an unknown emitter. Compared by identity (PMD CompareObjectsWithEquals): the very
     * emitter being detached, not one that happens to compare equal.
     */
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private void removeSubscriber(SseEmitter emitter) {
        synchronized (lock) {
            for (Subscriber subscriber : subscribers) {
                if (subscriber.emitter == emitter) {
                    subscribers.remove(subscriber);
                    subscriber.sender.interrupt();
                }
            }
        }
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
            entryPayloads.put(entry.getKey(), nullSafeMap(entry.getValue().byName()));
        }
        payload.put("entries", entryPayloads);

        return objectMapper.writeValueAsString(payload);
    }

    private static Map<String, Object> nullSafeMap(Map<String, Double> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            result.put(entry.getKey(), PeekabootJson.nanToNull(entry.getValue()));
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
     * <p>Nothing is queued while no one is subscribed, so an unwatched app never logs
     * "queue full". Bounded: on overflow the oldest event is dropped and a warning
     * logged once per episode.
     */
    private void enqueue(String eventName, Supplier<String> json) {
        SseEvent event = new SseEvent(eventName, json);
        synchronized (lock) {
            if (subscribers.isEmpty()) {
                return;
            }
            if (queue.offer(event)) {
                queueOverflowWarned = false;
                return;
            }
            queue.poll();
            queue.add(event);
            if (!queueOverflowWarned) {
                queueOverflowWarned = true;
                log.warn("Insights SSE dispatch queue full ({}); dropping oldest events", QUEUE_CAPACITY);
            }
        }
    }

    /**
     * Fans one event out to every subscriber's lane, on the single dispatch thread. The
     * offers never block, so a peer that has stopped reading wedges only its own sender;
     * once its lane overflows the peer is dropped (see {@link Subscriber}).
     *
     * <p>Package-visible so tests can stand in for (or wedge) the fan-out step.
     */
    void broadcast(String eventName, String json) {
        OutboundEvent event = new OutboundEvent(eventName, json);
        for (Subscriber subscriber : subscribers) {
            subscriber.offerOrDrop(event);
        }
    }

    /** Test seam: invoked after each event successfully handed to an emitter's send(). No-op in production. */
    void onDelivered(String eventName) {}

    private void heartbeatStep() throws InterruptedException {
        Thread.sleep(HEARTBEAT_INTERVAL);
        heartbeat();
    }

    /**
     * Heartbeats travel the same lanes as events: a healthy peer gets the keep-alive
     * comment, and at a peer whose send is stuck they accumulate like any other event,
     * so even an idle stream's wedge is eventually detected by lane overflow.
     */
    void heartbeat() {
        OutboundEvent heartbeat = OutboundEvent.heartbeat();
        for (Subscriber subscriber : subscribers) {
            subscriber.offerOrDrop(heartbeat);
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

    /**
     * The stream handed to one dashboard.
     *
     * <p>complete()/completeWithError() detach the subscriber themselves: Spring's
     * container-driven onCompletion/onError callbacks fire only inside a real request
     * dispatch, so when a sender completes an emitter (or a unit test does) nobody else
     * would.
     *
     * <p>The container's timeout completes the emitter, which is what keeps Spring's
     * timeout interceptor from raising an AsyncRequestTimeoutException - a WARN in the
     * host's log for every open dashboard, every {@link #EMITTER_TIMEOUT}. It does so only
     * while the write lock is free: a sender wedged in send() holds it, and waiting behind
     * that send on the container's thread is what the per-subscriber lanes exist to
     * prevent. Such a peer is only detached, and Spring's own timeout handling ends it.
     */
    private final class SubscriberEmitter extends SseEmitter {

        SubscriberEmitter() {
            super(EMITTER_TIMEOUT.toMillis());
            onTimeout(this::completeUnlessSending);
        }

        @Override
        public void complete() {
            super.complete();
            removeSubscriber(this);
        }

        @Override
        public void completeWithError(Throwable ex) {
            super.completeWithError(ex);
            removeSubscriber(this);
        }

        private void completeUnlessSending() {
            if (!writeLock.tryLock()) {
                removeSubscriber(this);
                return;
            }
            try {
                complete();
            } finally {
                writeLock.unlock();
            }
        }
    }

    /** One rendered event on its way to the lanes; {@code name == null} is the heartbeat comment. */
    private record OutboundEvent(String name, String json) {

        static OutboundEvent heartbeat() {
            return new OutboundEvent(null, null);
        }

        boolean isHeartbeat() {
            return name == null;
        }
    }

    /**
     * One connected dashboard: its emitter plus the bounded lane and sender thread that
     * decouple it from every other subscriber. The sender performs the blocking send()
     * calls, so a peer that stops reading wedges only itself; its lane then fills and the
     * overflow drops the subscriber, mirroring the drop on a failed send. Completing the
     * emitter on that path would block behind the very send that is stuck (complete()
     * takes the same write lock), so a dropped emitter is left to its timeout instead.
     */
    private final class Subscriber {

        private final SseEmitter emitter;
        private final BlockingQueue<OutboundEvent> lane = new ArrayBlockingQueue<>(SUBSCRIBER_QUEUE_CAPACITY);
        private final Thread sender;

        Subscriber(SseEmitter emitter) {
            this.emitter = emitter;
            this.sender = Thread.ofVirtual().name("peekaboot-insights-sse-send").unstarted(this::drainLane);
        }

        void offerOrDrop(OutboundEvent event) {
            if (lane.offer(event)) {
                return;
            }
            log.debug(
                    "Dropping insights SSE subscriber that stopped reading (send lane of {} full)",
                    SUBSCRIBER_QUEUE_CAPACITY);
            removeSubscriber(emitter);
        }

        private void drainLane() {
            try {
                while (true) {
                    OutboundEvent event = lane.take();
                    if (event.isHeartbeat()) {
                        emitter.send(SseEmitter.event().comment("hb"));
                    } else {
                        emitter.send(SseEmitter.event().name(event.name()).data(event.json()));
                        onDelivered(event.name());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | IllegalStateException e) {
                log.debug("Dropping insights SSE subscriber after send failure: {}", e.toString());
                emitter.completeWithError(e);
            }
        }
    }

    private record SseEvent(String name, Supplier<String> json) {}

    @FunctionalInterface
    private interface LoopStep {
        void run() throws InterruptedException;
    }

    /**
     * A named virtual thread that repeats {@code step} while subscribers remain and exits
     * once they don't. Start and the exit check both run under the publisher's
     * {@code lock}, so a subscribe() racing the exit can never see a stale thread handle.
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
                    if (subscribers.isEmpty()) {
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

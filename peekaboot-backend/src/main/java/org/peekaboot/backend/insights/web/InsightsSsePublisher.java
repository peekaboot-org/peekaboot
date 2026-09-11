package org.peekaboot.backend.insights.web;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.peekaboot.backend.insights.AggregateStats;
import org.peekaboot.backend.insights.InsightsCollector;
import org.peekaboot.backend.insights.web.Subscriber.OutboundEvent;
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
 * own sender thread performs the blocking send - no peer can stall another. The same
 * thread sends the keep-alive: a poll that comes back empty after
 * {@link #HEARTBEAT_INTERVAL} means the stream has been idle that long, and a busy
 * stream needs no heartbeat at all.
 *
 * <p>A {@link SmartLifecycle} so that context shutdown completes every open
 * emitter: an emitter left open holds its async servlet request, and the
 * container's graceful shutdown then waits for it (measured: 30s at JVM exit).
 * The one exception is a peer still inside a send past {@link #STOP_GRACE}; that
 * one is detached instead, since its socket write cannot be ended from here.
 */
public final class InsightsSsePublisher implements InsightsCollector.Listener, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InsightsSsePublisher.class);

    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private static final String DISPATCH_THREAD = "peekaboot-insights-sse-dispatch";
    /**
     * Events awaiting the dispatch thread. A dispatch step is one JSON render plus
     * non-blocking lane offers, so a backlog this deep (over half an hour of ticks and
     * roll-ups) only ever means the dispatch thread itself is stuck.
     */
    private static final int QUEUE_CAPACITY = 256;
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
    static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);
    /**
     * How long stop() waits for a send in flight before detaching the peer instead. A
     * healthy peer's socket write takes milliseconds; one that has stopped reading holds
     * its write far longer than this. The waits run one subscriber after another, so
     * shutdown is held for at most this times {@link #MAX_SUBSCRIBERS} (6.4s), inside
     * Spring's default {@code spring.lifecycle.timeout-per-shutdown-phase} of 30s.
     */
    static final Duration STOP_GRACE = Duration.ofMillis(200);

    private final InsightsEventJson eventJson;
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final BlockingQueue<SseEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    /**
     * The single monitor for subscriber-list, queue, and dispatch thread state. A
     * dedicated private object rather than {@code this} so nothing outside this
     * class can ever contend on (or deadlock against) the internal locking.
     */
    private final Object lock = new Object();
    /**
     * Guarded by the publisher's {@code lock}, together with the drop-then-add pair in
     * enqueue() that it describes: set when an episode of overflow starts warning,
     * cleared as soon as an offer succeeds again, so a later episode is never swallowed
     * as a duplicate. The queue itself is thread-safe; the dispatch thread polls it
     * without the lock, and taking the lock there would hold enqueue() off for a whole
     * heartbeat interval at a time, stalling the collector threads that call it.
     */
    private boolean queueOverflowWarned;

    /**
     * The dispatch thread while one is running, guarded by {@code lock}. Start and the
     * loop's exit check both run under it, so a subscribe() racing the exit can never
     * see a stale handle.
     */
    private Thread dispatcher;
    /**
     * True from construction; only stop() clears it, so a client reconnecting during
     * shutdown is not handed an emitter that would hold the shutdown open.
     */
    private volatile boolean running = true;

    public InsightsSsePublisher(ObjectMapper objectMapper) {
        this.eventJson = new InsightsEventJson(objectMapper);
    }

    @Override
    public void start() {
        running = true;
    }

    /**
     * Stops the dispatch thread and completes every open emitter, so nothing outlives the
     * context.
     *
     * <p>The dispatch thread goes first and each sender is interrupted before its emitter
     * is completed, so no further event feeds a wedged send once shutdown has begun. The
     * interrupt cannot end a send already inside the container's socket write, and
     * complete() would wait behind it on that emitter's write lock: a healthy peer's
     * write finishes within {@link #STOP_GRACE} and its stream is then completed, one
     * still writing after that is only detached, as on timeout. Subscribers are
     * snapshotted and cleared under the monitor before either step, so a fan-out
     * racing this has nothing left to iterate.
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
        stopDispatcher();
        for (Subscriber subscriber : open) {
            subscriber.interruptSender();
            try {
                subscriber.emitter().completeUnlessSendingWithin(STOP_GRACE);
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
        SubscriberEmitter emitter = new SubscriberEmitter();
        Subscriber subscriber = new Subscriber(emitter, () -> removeSubscriber(emitter));
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
                subscriber.startSender();
            }
        }
        if (!accepted) {
            emitter.complete();
            return emitter;
        }
        emitter.onCompletion(() -> removeSubscriber(emitter));
        emitter.onError(e -> removeSubscriber(emitter));
        startDispatcherIfNeeded();
        return emitter;
    }

    int subscriberCount() {
        return subscribers.size();
    }

    /**
     * Detaches the subscriber whose emitter this is and interrupts its sender; a no-op for
     * an unknown emitter. Compared by identity: the very emitter being detached, not one
     * that happens to compare equal. Removing while iterating is safe on the
     * copy-on-write list, whose iterator walks a snapshot.
     */
    @SuppressWarnings({"PMD.CompareObjectsWithEquals", "ReferenceEquality", "ModifyCollectionInEnhancedForLoop"})
    private void removeSubscriber(SseEmitter emitter) {
        synchronized (lock) {
            for (Subscriber subscriber : subscribers) {
                if (subscriber.emitter() == emitter) {
                    subscribers.remove(subscriber);
                    subscriber.interruptSender();
                }
            }
        }
    }

    @Override
    public void onTick(long epochMs, Map<String, Double> values) {
        enqueue("tick", () -> eventJson.tick(epochMs, values));
    }

    @Override
    public void onRollUp(int level, long epochMs, Map<String, AggregateStats> entries) {
        enqueue("rollup", () -> eventJson.rollUp(level, epochMs, entries));
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

    private void startDispatcherIfNeeded() {
        synchronized (lock) {
            if (dispatcher != null) {
                return;
            }
            dispatcher = Thread.ofVirtual().name(DISPATCH_THREAD).unstarted(this::dispatch);
            dispatcher.start();
        }
    }

    /**
     * Interrupts and joins the dispatch thread. The handle is taken (and cleared)
     * under the monitor but the join happens outside it - the loop acquires
     * the same monitor on every iteration, and an interrupt does not free a
     * thread blocked on monitor entry.
     */
    private void stopDispatcher() {
        Thread thread;
        synchronized (lock) {
            thread = dispatcher;
            dispatcher = null;
        }
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Repeats {@link #dispatchStep()} while subscribers remain and exits once they don't. */
    private void dispatch() {
        while (true) {
            synchronized (lock) {
                if (subscribers.isEmpty()) {
                    dispatcher = null;
                    return;
                }
            }
            try {
                dispatchStep();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                synchronized (lock) {
                    dispatcher = null;
                }
                return;
            } catch (RuntimeException e) {
                // A failed step must not take the loop with it: the thread
                // handle would stay set and no subscribe() would ever restart it.
                log.warn("Insights SSE dispatch step failed; continuing", e);
            }
        }
    }

    /**
     * One event, or the heartbeat once none has arrived for a whole
     * {@link #HEARTBEAT_INTERVAL}. A queued event never waits behind the poll, since
     * offer() wakes a blocked poll() immediately.
     */
    private void dispatchStep() throws InterruptedException {
        SseEvent event = queue.poll(HEARTBEAT_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        if (event == null) {
            heartbeat();
            return;
        }
        fanOut(new OutboundEvent(event.name(), event.json().get()));
    }

    /**
     * Heartbeats travel the same lanes as events: a healthy peer gets the keep-alive
     * comment, and at a peer whose send is stuck they accumulate like any other event,
     * so even an idle stream's wedge is eventually detected by lane overflow.
     */
    void heartbeat() {
        fanOut(OutboundEvent.heartbeat());
    }

    /**
     * Offers one event to every subscriber's lane. The offers never block, so a peer
     * that has stopped reading wedges only its own sender; once its lane overflows the
     * peer is dropped (see {@link Subscriber}).
     */
    private void fanOut(OutboundEvent event) {
        for (Subscriber subscriber : subscribers) {
            subscriber.offerOrDrop(event);
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
     * {@link #stop()} completes emitters under the same guard, but grants a send in
     * flight {@link #STOP_GRACE} to finish first: it runs on the context's thread, where
     * a bounded wait costs less than an async request left open for the container.
     */
    class SubscriberEmitter extends SseEmitter {

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
            completeUnlessSendingWithin(Duration.ZERO);
        }

        /**
         * Completes once the write lock is free within {@code grace}; a peer still sending
         * after that is only detached. The untimed attempt goes first: the timed one throws
         * on entry for an interrupted caller, and stop() can arrive interrupted with every
         * idle emitter still to complete.
         */
        private void completeUnlessSendingWithin(Duration grace) {
            if (!writeLock.tryLock() && !awaitWriteLock(grace)) {
                removeSubscriber(this);
                return;
            }
            try {
                complete();
            } finally {
                writeLock.unlock();
            }
        }

        private boolean awaitWriteLock(Duration grace) {
            try {
                return writeLock.tryLock(grace.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private record SseEvent(String name, Supplier<String> json) {}
}

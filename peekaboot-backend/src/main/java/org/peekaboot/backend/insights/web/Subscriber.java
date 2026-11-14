package org.peekaboot.backend.insights.web;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One connected dashboard: its emitter plus the bounded lane and sender thread that
 * decouple it from every other subscriber. The sender performs the blocking send()
 * calls, so a peer that stops reading wedges only itself; its lane then fills and the
 * overflow drops the subscriber, mirroring the drop on a failed send.
 *
 * <p>A dropped peer's stream is completed, so its browser reconnects. complete() takes the
 * write lock a send holds, and the drop runs on the dispatch thread, which must not wait
 * behind a stuck send. So the drop completes the emitter only while no send is in flight,
 * and otherwise the sender does once its send returns. A send that never returns still
 * holds its emitter until the container gives up.
 */
final class Subscriber {

    private static final Logger log = LoggerFactory.getLogger(Subscriber.class);

    /** The sender thread's name; package-private so a test can tell its exceptions from other threads'. */
    static final String SENDER_THREAD = "peekaboot-insights-sse-send";

    /**
     * Each subscriber's own send lane. A healthy peer drains it as fast as the dispatch
     * thread fills it and a burst spans a handful of events, so a backlog this deep only
     * ever means a peer that has stopped reading.
     */
    static final int LANE_CAPACITY = 32;

    private static final String LANE_OVERFLOW = "lane overflow";

    private final InsightsSsePublisher.SubscriberEmitter emitter;
    private final BlockingQueue<OutboundEvent> lane = new ArrayBlockingQueue<>(LANE_CAPACITY);
    private final Thread sender;
    private final Runnable onDrop;
    /** Set before the drop interrupts the sender, so a sender that leaves on that interrupt sees it. */
    private volatile boolean dropped;

    /** {@code onDrop} detaches this subscriber from the publisher. */
    Subscriber(InsightsSsePublisher.SubscriberEmitter emitter, Runnable onDrop) {
        this.emitter = emitter;
        this.onDrop = onDrop;
        this.sender = Thread.ofVirtual().name(SENDER_THREAD).unstarted(this::drainLane);
    }

    InsightsSsePublisher.SubscriberEmitter emitter() {
        return emitter;
    }

    /** Starts the sender; the caller sequences this against {@link #interruptSender()}. */
    void startSender() {
        sender.start();
    }

    /** Ends the sender before its next take; a send already inside the container's write runs on. */
    void interruptSender() {
        sender.interrupt();
    }

    void offerOrDrop(OutboundEvent event) {
        if (lane.offer(event)) {
            return;
        }
        log.debug("Dropping insights SSE subscriber that stopped reading (send lane of {} full)", LANE_CAPACITY);
        dropped = true;
        onDrop.run();
        endStream(emitter::completeUnlessSending, LANE_OVERFLOW);
    }

    private void drainLane() {
        try {
            while (true) {
                OutboundEvent event = lane.take();
                if (event.isHeartbeat()) {
                    emitter.send(SseEmitter.event().comment("hb"));
                } else {
                    emitter.send(SseEmitter.event().name(event.name()).data(event.json()));
                }
            }
        } catch (InterruptedException e) {
            if (dropped) {
                endStream(emitter::complete, LANE_OVERFLOW);
            }
            // Restored last, so the completion's flush does not run on an interrupted thread.
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalStateException e) {
            log.debug("Dropping insights SSE subscriber after send failure: {}", e.toString());
            try {
                endStream(() -> emitter.completeWithError(e), "send failure: " + e);
            } finally {
                // The emitter detaches the subscriber itself, but only when the completion goes through.
                onDrop.run();
            }
        }
    }

    /** Ends the stream of a peer that is being dropped; {@code cause} names the drop in a failure report. */
    private void endStream(Runnable completion, String cause) {
        try {
            completion.run();
        } catch (IllegalStateException e) {
            // A container refuses a completion once its async request has errored or gone, so
            // this peer left before we could end its stream - as routine as the drop itself.
            log.debug("Insights SSE subscriber had already gone when its stream was ended: {}", e.toString());
        } catch (RuntimeException e) {
            log.warn("Failed to end an insights SSE subscriber's stream after {}", cause, e);
        }
    }

    /** One rendered event on its way to the lanes; {@code name == null} is the heartbeat comment. */
    record OutboundEvent(String name, String json) {

        static OutboundEvent heartbeat() {
            return new OutboundEvent(null, null);
        }

        boolean isHeartbeat() {
            return name == null;
        }
    }
}

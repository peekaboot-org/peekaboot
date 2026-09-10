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
 * overflow drops the subscriber, mirroring the drop on a failed send. Completing the
 * emitter on that path would block behind the very send that is stuck (complete()
 * takes the same write lock), so a dropped emitter is left to its timeout instead.
 */
final class Subscriber {

    private static final Logger log = LoggerFactory.getLogger(Subscriber.class);

    /**
     * Each subscriber's own send lane. A healthy peer drains it as fast as the dispatch
     * thread fills it and a burst spans a handful of events, so a backlog this deep only
     * ever means a peer that has stopped reading.
     */
    static final int LANE_CAPACITY = 32;

    private final InsightsSsePublisher.SubscriberEmitter emitter;
    private final BlockingQueue<OutboundEvent> lane = new ArrayBlockingQueue<>(LANE_CAPACITY);
    private final Thread sender;
    private final Runnable onDrop;

    /** {@code onDrop} detaches this subscriber from the publisher once its lane overflows. */
    Subscriber(InsightsSsePublisher.SubscriberEmitter emitter, Runnable onDrop) {
        this.emitter = emitter;
        this.onDrop = onDrop;
        this.sender = Thread.ofVirtual().name("peekaboot-insights-sse-send").unstarted(this::drainLane);
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
        onDrop.run();
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
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalStateException e) {
            log.debug("Dropping insights SSE subscriber after send failure: {}", e.toString());
            endStream(e);
        }
    }

    /** Ends the stream for a peer whose send failed, and detaches the subscriber either way. */
    private void endStream(Exception sendFailure) {
        try {
            emitter.completeWithError(sendFailure);
        } catch (IllegalStateException e) {
            // A container refuses a completion once its async request has errored or gone, so
            // this peer left before we could end its stream - as routine as the failed send.
            log.debug("Insights SSE subscriber had already gone when its stream was ended: {}", e.toString());
        } catch (RuntimeException e) {
            log.warn("Failed to end an insights SSE subscriber's stream", e);
        } finally {
            // The emitter detaches the subscriber itself, but only when the completion goes through.
            onDrop.run();
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

package org.peekaboot.fixtures;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves its page immediately, then keeps a child of the request's trace running until the test
 * releases it - the shape of an {@code @Async} continuation, a streamed body or a
 * {@code DeferredResult}. The collapsed toolbar only ever sees that span if it keeps reading
 * after its first successful render. Ending on release rather than on a timer lets the test
 * prove that render came first, however slowly the page loads.
 */
@RestController
public class LateSpanController {

    /**
     * The least a late span runs, so the trace it stretches reaches a duration the request's own
     * spans never do.
     */
    public static final Duration LATE_WORK = Duration.ofMillis(1500);

    /** Ends late work nobody released, so a test that failed first leaves no span running. */
    private static final Duration ABANDONED_AFTER = Duration.ofSeconds(30);

    private static final String PAGE =
            "<!DOCTYPE html><html><head><title>Late span</title></head><body>ok</body></html>";

    private final Tracer tracer;

    /**
     * Bounded rather than a single slot: a browser may well re-request the page (a reload, a
     * favicon-triggered navigation), and the test wants the first run's span, not the last.
     */
    private final BlockingQueue<String> endedTraceIds = new ArrayBlockingQueue<>(8);

    private final Semaphore releases = new Semaphore(0);

    public LateSpanController(Tracer tracer) {
        this.tracer = tracer;
    }

    @GetMapping(value = "/late-span", produces = MediaType.TEXT_HTML_VALUE)
    public String lateSpan() {
        // The parent has to be read here, on the request thread. The thread below carries no
        // trace context of its own, so the child is attached by handing the builder the
        // captured parent rather than by re-entering a scope over there.
        TraceContext parent = tracer.currentTraceContext().context();
        Thread.ofVirtual().name("late-work").start(() -> runLateWork(parent));
        return PAGE;
    }

    /** Lets one run of late work end, once it has run for {@link #LATE_WORK}. */
    public void release() {
        releases.release();
    }

    /** Blocks until a released late span has ended and returns the trace it belongs to. */
    public String awaitLateSpanTraceId(Duration timeout) throws InterruptedException {
        String traceId = endedTraceIds.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (traceId == null) {
            throw new AssertionError("the late span never ended within " + timeout);
        }
        return traceId;
    }

    private void runLateWork(TraceContext parent) {
        Span span = tracer.spanBuilder().setParent(parent).name("late-work").start();
        try {
            Thread.sleep(LATE_WORK.toMillis());
            releases.tryAcquire(ABANDONED_AFTER.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            span.end();
            endedTraceIds.offer(span.context().traceId());
        }
    }
}

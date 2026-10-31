package org.peekaboot.fixtures;

import io.micrometer.tracing.Span;
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
 * Holds its request open until the test releases it, after a child of the request's trace has
 * ended: the shape of any request still running when its trace is read, whose child is exported
 * and whose own spans are not.
 */
@RestController
public class HeldRequestController {

    public static final String PATH = "/held-request";

    public static final String CHILD_SPAN = "held-request.child";

    /** Lets a request go that nobody released, so a test that failed first leaves no request hanging. */
    private static final Duration ABANDONED_AFTER = Duration.ofSeconds(30);

    private final Tracer tracer;

    private final BlockingQueue<String> heldTraceIds = new ArrayBlockingQueue<>(8);

    private final Semaphore releases = new Semaphore(0);

    public HeldRequestController(Tracer tracer) {
        this.tracer = tracer;
    }

    @GetMapping(value = PATH, produces = MediaType.TEXT_PLAIN_VALUE)
    public String held() throws InterruptedException {
        Span child = tracer.nextSpan().name(CHILD_SPAN).start();
        child.end();
        heldTraceIds.offer(child.context().traceId());
        releases.tryAcquire(ABANDONED_AFTER.toMillis(), TimeUnit.MILLISECONDS);
        return "released";
    }

    /** Blocks until a request is held and returns its trace id. */
    public String awaitHeldTraceId(Duration timeout) throws InterruptedException {
        String traceId = heldTraceIds.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (traceId == null) {
            throw new AssertionError("no request reached " + PATH + " within " + timeout);
        }
        return traceId;
    }

    public void release() {
        releases.release();
    }
}

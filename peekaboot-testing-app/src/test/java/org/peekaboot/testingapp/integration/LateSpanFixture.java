package org.peekaboot.testingapp.integration;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves its page immediately, then finishes a child of the request's trace roughly
 * {@link LateSpanController#LATE_WORK} later - the shape of an {@code @Async} continuation, a
 * streamed body or a {@code DeferredResult}. The collapsed toolbar only ever sees that span if it
 * keeps reading after its first successful render.
 */
@TestConfiguration
public class LateSpanFixture {

    // No @Bean method: the nested @RestController is a component candidate in its own right, so
    // Spring registers it when this configuration is imported - declaring it again would map
    // /late-span twice.

    /** What the late span actually turned out to be, recorded by the thread that ended it. */
    public record LateSpan(String traceId, Instant endedAt) {}

    @RestController
    public static class LateSpanController {

        /**
         * Long enough that the bar provably renders at least once before the span lands.
         *
         * <p>Two clocks: the late span starts at response time and ends {@code LATE_WORK} later.
         * The toolbar's fetch ladder ({@code attemptDelays} in {@code toolbar.js}) starts only once
         * its module graph has executed, some latency {@code L} after the response, so its
         * attempts fire at {@code L+250}, {@code L+750}, {@code L+1750} and {@code L+4750}.
         * {@code L} is what inflates under CI load, so it is the margin that matters.
         *
         * <p>The constraints: the first render must still show the short baseline duration, i.e.
         * land before the late span ends - {@code L+750 < LATE_WORK} even if the first attempt is
         * missed - and the late span must be exported ({@code LATE_WORK} plus the test profile's
         * 50ms {@code schedule-delay}) before the third attempt: {@code LATE_WORK + 50 < L+1750},
         * which holds at {@code L=0} and only gains room as {@code L} grows.
         *
         * <p>Changing the ladder, the export delay, or this value invalidates this arithmetic - redo
         * it rather than assume it still holds.
         */
        public static final Duration LATE_WORK = Duration.ofMillis(1500);

        private static final String PAGE =
                "<!DOCTYPE html><html><head><title>Late span</title></head><body>ok</body></html>";

        private final Tracer tracer;

        /**
         * Bounded rather than a single slot: a browser may well re-request the page (a reload, a
         * favicon-triggered navigation), and the test wants the first run's span, not the last.
         */
        private final BlockingQueue<LateSpan> ended = new ArrayBlockingQueue<>(8);

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

        /** Blocks until the async span has ended, so the test asserts on facts rather than timing. */
        public LateSpan awaitLateSpan(Duration timeout) throws InterruptedException {
            LateSpan lateSpan = ended.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (lateSpan == null) {
                throw new AssertionError("the late span never ended within " + timeout);
            }
            return lateSpan;
        }

        private void runLateWork(TraceContext parent) {
            Span span = tracer.spanBuilder().setParent(parent).name("late-work").start();
            try {
                Thread.sleep(LATE_WORK.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                span.end();
                ended.offer(new LateSpan(span.context().traceId(), Instant.now()));
            }
        }
    }
}

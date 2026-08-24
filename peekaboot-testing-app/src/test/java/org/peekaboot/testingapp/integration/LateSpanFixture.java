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
         * Long enough that the bar provably renders at least once before the span lands - not an
         * accident of round numbers, but a margin against numbers this class does not own.
         *
         * <p><b>Two different clocks.</b> This fixture's clock starts on the request thread, in
         * {@link #lateSpan()}: the late span begins essentially at response time and ends
         * {@code LATE_WORK} later. The toolbar's fetch ladder ({@code attemptDelays} in {@code
         * toolbar.js}) does not start there - {@code DevToolbarFilter} injects {@code toolbar.js} as
         * {@code <script type="module">}, which defers past HTML parse and then pulls in five
         * further ES modules ({@code format.js}, {@code severity.js}, {@code theme.js}, {@code
         * shadow-styles.js}, {@code copyable.js}), each a separate round trip, cold on first
         * navigation. Call that response-to-module-graph-executed latency {@code L}; the ladder's
         * attempts fire at {@code L+250} and {@code L+750} (cumulative), not at 250ms and 750ms from
         * the response. {@code L} is exactly what inflates under CI load, so it is the margin that
         * matters, not the raw attempt delays.
         *
         * <p><b>The constraint.</b> The first render must still show the trace's short baseline
         * duration, i.e. land before the late span ends: {@code L+250 < LATE_WORK} if the first
         * attempt supplies it, or the more conservative {@code L+750 < LATE_WORK} if the first
         * attempt is missed and the second supplies it instead. At {@code LATE_WORK = 1500ms} that
         * is a 1250ms tolerance for {@code L} on the first attempt, or 750ms on the conservative
         * path - both comfortably wider than the 50ms this class ran with when both attempt delays
         * were measured from the response instead of from {@code L}.
         *
         * <p>The other side of the ladder needs no margin at all: the late span becomes visible to
         * {@code /insights} at {@code LATE_WORK} plus the test profile's OTel span export {@code
         * schedule-delay} ({@code application-test.yml}), 50ms - 1550ms from the response,
         * independent of {@code L}. The third attempt, at {@code L+1750}, already clears that at
         * {@code L=0}; growing {@code L} only pushes it later, which helps rather than hurts here.
         * The fourth attempt, at {@code L+4750}, is a 3.2s backstop that fires regardless -
         * {@code toolbar.js} chains {@code attempt(index + 1)} off a {@code .finally()}, so it runs
         * whether or not the previous attempt's fetch succeeded.
         *
         * <p>One more effect of the raised value: past 1000ms, {@code formatDurationMs} switches
         * from {@code Math.round(ms) + 'ms'} to {@code (ms/1000).toFixed(2) + 's'} - 10ms
         * granularity. The trace duration this test asserts on is strictly greater than {@code
         * LATE_WORK} (root-start to late-end spans more than the sleep itself), so the worst-case
         * rendering rounds down to {@code "1.50s"}, which parses back to exactly 1500.0 - the
         * assertion's {@code isGreaterThanOrEqualTo(LATE_WORK.toMillis())} still passes, but on the
         * boundary rather than with headroom.
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

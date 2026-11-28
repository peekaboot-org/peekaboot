package org.peekaboot.autoconfigure;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.config.PeekabootProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Runs each health check on a thread of its own, at most one evaluation per check at a time,
 * and reports a check that misses the deadline DOWN.
 *
 * <p>Single-flight bounds the threads by the number of checks; a fixed pool would queue healthy
 * checks behind hung ones until they time out too. A late check is never interrupted, since
 * that closes the socket it blocks on, which may be a connection it borrowed from the app. It is
 * left to finish on its own and only its answer is logged.
 */
public final class HealthCheckRuns {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckRuns.class);

    // built up front, so ending a run never needs memory a failing check may have used up
    private static final Health UNDESCRIBED_DOWN = Health.down()
            .withDetail("error", "Failed with an error it could not describe")
            .build();

    private final Duration timeout;

    private final @Nullable Duration slowThreshold;

    private final Executor executor;

    private final LongSupplier ticker;

    private final ConcurrentMap<String, Run> inFlight = new ConcurrentHashMap<>();

    /** The seam: {@code ticker} answers nanoseconds, as {@link System#nanoTime()} does. */
    HealthCheckRuns(Duration timeout, @Nullable Duration slowThreshold, Executor executor, LongSupplier ticker) {
        this.timeout = timeout;
        this.slowThreshold = slowThreshold;
        this.executor = executor;
        this.ticker = ticker;
    }

    /** Each evaluation on a virtual thread named {@code peekaboot-health-check}. */
    static HealthCheckRuns onVirtualThreads(Duration timeout, @Nullable Duration slowThreshold) {
        return new HealthCheckRuns(
                timeout,
                slowThreshold,
                check -> Thread.ofVirtual().name("peekaboot-health-check").start(check),
                System::nanoTime);
    }

    /** The evaluation of {@code path} in flight, else a new one. Never waits. */
    // an executor that cannot start the check, whatever it throws, must still end the run
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    Run start(String path, HealthIndicator indicator) {
        Run fresh = new Run(path, indicator, ticker.getAsLong());
        // registered before it starts, so a request arriving while the executor starts it joins it
        Run running = inFlight.putIfAbsent(path, fresh);
        if (running != null) {
            return running;
        }
        try {
            executor.execute(() -> PeekabootProbe.run(() -> evaluate(fresh)));
        } catch (Throwable refused) {
            finish(fresh, down(refused));
        }
        return fresh;
    }

    /** The instant, on the ticker, by which every check of one request must have answered. */
    long deadline() {
        return ticker.getAsLong() + timeoutNanos();
    }

    // a timeout beyond what a long holds in nanoseconds is as good as none
    private long timeoutNanos() {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException tooLarge) {
            return Long.MAX_VALUE;
        }
    }

    /** The run's answer, or DOWN timed out if it has none by {@code deadline}. */
    Result await(Run run, long deadline) {
        try {
            if (run.finished.await(Math.max(0, deadline - ticker.getAsLong()), NANOSECONDS)) {
                return run.answer();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return interrupted(run);
        }
        return timedOut(run);
    }

    // a check can fail with anything, an Error included, and must still end its run
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void evaluate(Run run) {
        @Nullable Health health = UNDESCRIBED_DOWN;
        try {
            health = run.indicator.health(true);
        } catch (Throwable failure) {
            health = down(failure);
        } finally {
            finish(run, health);
        }
    }

    // describing a failure can fail too, an out-of-memory error or a throwing getMessage()
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static Health down(Throwable failure) {
        try {
            return Health.down(failure).build();
        } catch (Throwable undescribable) {
            return UNDESCRIBED_DOWN;
        }
    }

    private void finish(Run run, @Nullable Health health) {
        long end = ticker.getAsLong();
        boolean late;
        synchronized (run) {
            if (run.done) {
                return;
            }
            run.done = true;
            run.endNanos = end;
            run.health = health;
            late = run.late;
        }
        inFlight.remove(run.path, run);
        Duration took = Duration.ofNanos(end - run.startNanos);
        if (late) {
            String answer = health == null ? "nothing" : health.getStatus().getCode();
            log.warn("Peekaboot health: '{}' answered {} after {}", run.path, answer, print(took));
        } else if (slowThreshold != null && took.compareTo(slowThreshold) > 0) {
            log.warn(
                    "Health contributor {} ({}) took {} to respond",
                    run.indicator.getClass().getName(),
                    run.path,
                    DurationStyle.SIMPLE.print(took));
        }
        // last: whoever the latch wakes also sees the run removed and its line logged
        run.finished.countDown();
    }

    // not a miss of the deadline, so the run is not marked late and nothing is logged
    private Result interrupted(Run run) {
        long now = ticker.getAsLong();
        synchronized (run) {
            if (run.done) {
                return run.answer();
            }
        }
        Health down = Health.down()
                .withDetail("error", "Interrupted while waiting for the check")
                .build();
        return new Result(down, NANOSECONDS.toMillis(now - run.startNanos));
    }

    private Result timedOut(Run run) {
        long now = ticker.getAsLong();
        synchronized (run) {
            if (run.done) {
                return run.answer();
            }
            // under the monitor, so a late answer cannot be logged before the miss it follows
            if (!run.late) {
                run.late = true;
                log.warn("Peekaboot health: '{}' did not answer within {}; reported DOWN", run.path, print(timeout));
            }
        }
        Health down = Health.down()
                .withDetail("error", "Timed out after " + print(timeout))
                .build();
        return new Result(down, NANOSECONDS.toMillis(now - run.startNanos));
    }

    /** Whole seconds as seconds, anything else as milliseconds: {@code 10s}, {@code 1500ms}, {@code 0ms}. */
    static String print(Duration duration) {
        boolean wholeSeconds = !duration.isZero() && duration.toNanosPart() == 0;
        return DurationStyle.SIMPLE.print(duration, wholeSeconds ? ChronoUnit.SECONDS : ChronoUnit.MILLIS);
    }

    /** A check's answer, null where the indicator answered nothing, and how long it took. */
    record Result(@Nullable Health health, long durationMs) {}

    /** One evaluation of one check. */
    static final class Run {

        private final String path;

        private final HealthIndicator indicator;

        private final long startNanos;

        private final CountDownLatch finished = new CountDownLatch(1);

        private boolean done;

        private boolean late;

        private long endNanos;

        private @Nullable Health health;

        private Run(String path, HealthIndicator indicator, long startNanos) {
            this.path = path;
            this.indicator = indicator;
            this.startNanos = startNanos;
        }

        private synchronized Result answer() {
            return new Result(health, NANOSECONDS.toMillis(endNanos - startNanos));
        }
    }
}

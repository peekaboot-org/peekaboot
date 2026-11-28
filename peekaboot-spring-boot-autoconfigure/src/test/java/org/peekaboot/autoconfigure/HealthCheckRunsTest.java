package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.groups.Tuple;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootProbe;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

class HealthCheckRunsTest {

    private static final Duration TEN_SECONDS = Duration.ofSeconds(10);

    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    private final AtomicLong ticker = new AtomicLong();

    private final List<Runnable> held = new ArrayList<>();

    private final Executor holding = held::add;

    private final Executor inline = Runnable::run;

    private final HealthIndicator up = () -> Health.up().build();

    private LogCapture log;

    @BeforeEach
    void captureLog() {
        log = LogCapture.attach(HealthCheckRuns.class);
    }

    @AfterEach
    void assertNothingElseWasLogged() {
        try {
            assertThat(log.appender().list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .isEmpty();
        } finally {
            log.close();
        }
    }

    @Test
    void aCheckAnswersWhatItsIndicatorAnswers() {
        Health answer = Health.up().withDetail("database", "H2").build();
        HealthCheckRuns runs = runs(TEN_SECONDS, null, inline);

        HealthCheckRuns.Result result = startAndAwait(runs, after(Duration.ofMillis(30), answer));

        assertThat(result.health()).isEqualTo(answer);
        assertThat(result.durationMs()).isEqualTo(30);
    }

    @Test
    void aThrowingCheckAnswersDownWithItsError() {
        HealthCheckRuns runs = runs(TEN_SECONDS, null, inline);

        HealthCheckRuns.Result result = startAndAwait(runs, () -> {
            throw new IllegalStateException("boom");
        });

        assertThat(result.health()).isNotNull();
        assertThat(result.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.health().getDetails()).isEqualTo(Map.of("error", "java.lang.IllegalStateException: boom"));
    }

    @Test
    void anErrorEndsTheCheckDownAndTheNextRequestRetries() {
        HealthCheckRuns runs = runs(TEN_SECONDS, null, holding);
        HealthIndicator broken = () -> {
            throw new NoClassDefFoundError("x/Y");
        };

        HealthCheckRuns.Run run = runs.start("db", broken);
        assertThatCode(held.getFirst()::run).doesNotThrowAnyException();
        HealthCheckRuns.Result result = runs.await(run, runs.deadline());
        runs.start("db", broken);

        assertThat(result.health()).isNotNull();
        assertThat(result.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.health().getDetails()).isEqualTo(Map.of("error", "java.lang.NoClassDefFoundError: x/Y"));
        assertThat(held).hasSize(2);
    }

    @Test
    void aFailureThatCannotBeDescribedStillEndsTheCheckDown() {
        HealthCheckRuns runs = runs(TEN_SECONDS, null, holding);
        HealthIndicator broken = () -> {
            throw new UnreadableException();
        };

        HealthCheckRuns.Run run = runs.start("db", broken);
        assertThatCode(held.getFirst()::run).doesNotThrowAnyException();
        runs.start("db", broken);
        assertThat(held).hasSize(2);
        HealthCheckRuns.Result result = runs.await(run, runs.deadline());

        assertUndescribedDown(result);
    }

    @Test
    void aRefusalThatCannotBeDescribedStillEndsTheRunDown() {
        AtomicInteger attempts = new AtomicInteger();
        Executor refusing = check -> {
            attempts.incrementAndGet();
            throw new UnreadableException();
        };
        HealthCheckRuns runs = runs(TEN_SECONDS, null, refusing);

        HealthCheckRuns.Run run = runs.start("db", up);
        runs.start("db", up);
        assertThat(attempts).hasValue(2);
        HealthCheckRuns.Result result = runs.await(run, runs.deadline());

        assertUndescribedDown(result);
    }

    @Test
    void aRefusedStartAnswersDownAndTheNextRequestRetries() {
        AtomicInteger attempts = new AtomicInteger();
        Executor refusing = check -> {
            attempts.incrementAndGet();
            throw new RejectedExecutionException("no threads");
        };
        HealthCheckRuns runs = runs(TEN_SECONDS, null, refusing);

        HealthCheckRuns.Result result = startAndAwait(runs, up);
        runs.start("db", up);

        assertThat(result.health()).isNotNull();
        assertThat(result.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.health().getDetails())
                .isEqualTo(Map.of("error", "java.util.concurrent.RejectedExecutionException: no threads"));
        assertThat(attempts).hasValue(2);
    }

    @Test
    void aCheckRunningAtTheDeadlineAnswersDownTimedOut() {
        HealthCheckRuns runs = runs(Duration.ZERO, null, holding);

        HealthCheckRuns.Result result = startAndAwait(runs, up);

        assertTimedOut(result, "Timed out after 0ms");
        assertThat(result.durationMs()).isZero();
        assertWarned("Peekaboot health: 'db' did not answer within 0ms; reported DOWN");
    }

    @Test
    void theTimeoutReadsAsConfigured() {
        HealthCheckRuns runs = runs(TEN_SECONDS, null, holding);

        HealthCheckRuns.Run run = runs.start("db", up);
        long deadline = runs.deadline();
        ticker.set(TEN_SECONDS.toNanos());
        HealthCheckRuns.Result result = runs.await(run, deadline);

        assertTimedOut(result, "Timed out after 10s");
        assertThat(result.durationMs()).isEqualTo(10_000);
        assertWarned("Peekaboot health: 'db' did not answer within 10s; reported DOWN");
    }

    @Test
    void aRequestJoinsTheCheckInFlight() {
        HealthCheckRuns runs = runs(TEN_SECONDS, null, holding);

        runs.start("db", up);
        runs.start("db", up);
        assertThat(held).hasSize(1);
        held.getFirst().run();
        runs.start("db", up);

        assertThat(held).hasSize(2);
    }

    @Test
    void aRequestArrivingWhileTheCheckIsBeingStartedJoinsIt() {
        AtomicReference<HealthCheckRuns> runs = new AtomicReference<>();
        Executor joinedWhileStarting = check -> {
            runs.get().start("db", up);
            held.add(check);
        };
        runs.set(runs(TEN_SECONDS, null, joinedWhileStarting));

        runs.get().start("db", up);

        assertThat(held).hasSize(1);
    }

    @Test
    void aCheckStuckForeverIsStartedOnceAndWarnedOnce() {
        HealthCheckRuns runs = runs(Duration.ZERO, null, holding);

        List<HealthCheckRuns.Result> results =
                List.of(startAndAwait(runs, up), startAndAwait(runs, up), startAndAwait(runs, up));

        assertThat(held).hasSize(1);
        results.forEach(result -> assertTimedOut(result, "Timed out after 0ms"));
        assertWarned("Peekaboot health: 'db' did not answer within 0ms; reported DOWN");
    }

    @Test
    void anInterruptedReadReportsItselfNotATimeoutAndKeepsTheInterrupt() {
        HealthCheckRuns runs = runs(TEN_SECONDS, null, holding);
        HealthCheckRuns.Run run = runs.start("db", up);
        long deadline = runs.deadline();
        ticker.set(Duration.ofMillis(40).toNanos());

        Thread.currentThread().interrupt();
        HealthCheckRuns.Result result;
        boolean interruptKept;
        try {
            result = runs.await(run, deadline);
        } finally {
            interruptKept = Thread.interrupted();
        }
        held.getFirst().run();

        assertThat(interruptKept).isTrue();
        assertThat(result.health()).isNotNull();
        assertThat(result.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.health().getDetails()).isEqualTo(Map.of("error", "Interrupted while waiting for the check"));
        assertThat(result.durationMs()).isEqualTo(40);
    }

    @Test
    void anInterruptedReadStillReportsACheckThatHadAnswered() {
        HealthCheckRuns runs = runs(TEN_SECONDS, null, inline);
        HealthCheckRuns.Run run = runs.start("db", up);

        Thread.currentThread().interrupt();
        HealthCheckRuns.Result result;
        try {
            result = runs.await(run, runs.deadline());
        } finally {
            Thread.interrupted();
        }

        assertThat(result.health()).isEqualTo(Health.up().build());
    }

    @Test
    void aTimeoutTooLargeForNanosWaitsInsteadOfFailing() {
        HealthCheckRuns runs = runs(Duration.ofSeconds(Long.MAX_VALUE), null, inline);

        HealthCheckRuns.Result result = startAndAwait(runs, up);

        assertThat(result.health()).isEqualTo(Health.up().build());
    }

    @Test
    void aLateAnswerIsLoggedNotKept() {
        HealthCheckRuns runs = runs(TEN_SECONDS, null, holding);

        timeOutAndAnswerLate(runs, up);
        runs.start("db", up);

        assertWarned(
                "Peekaboot health: 'db' did not answer within 10s; reported DOWN",
                "Peekaboot health: 'db' answered UP after 12500ms");
        assertThat(held).hasSize(2);
    }

    @Test
    void aLateNullAnswerIsLoggedAsNothing() {
        HealthCheckRuns runs = runs(TEN_SECONDS, null, holding);

        timeOutAndAnswerLate(runs, () -> null);

        assertWarned(
                "Peekaboot health: 'db' did not answer within 10s; reported DOWN",
                "Peekaboot health: 'db' answered nothing after 12500ms");
    }

    @Test
    void aCheckSlowerThanBootsThresholdIsLoggedAsBootLogsIt() {
        HealthCheckRuns runs = runs(TEN_SECONDS, ONE_SECOND, inline);
        HealthIndicator slow = after(Duration.ofMillis(1500), Health.up().build());

        startAndAwait(runs, slow);

        assertWarned("Health contributor " + slow.getClass().getName() + " (db) took 1500ms to respond");
    }

    @Test
    void aCheckWithinBootsThresholdOrWithoutOneIsNotLogged() {
        startAndAwait(
                runs(TEN_SECONDS, ONE_SECOND, inline),
                after(Duration.ofMillis(500), Health.up().build()));
        startAndAwait(
                runs(TEN_SECONDS, null, inline),
                after(Duration.ofMillis(1500), Health.up().build()));
    }

    @Test
    void aLateCheckIsNotAlsoLoggedAsSlow() {
        HealthCheckRuns runs = runs(TEN_SECONDS, ONE_SECOND, holding);

        timeOutAndAnswerLate(runs, up);

        assertWarned(
                "Peekaboot health: 'db' did not answer within 10s; reported DOWN",
                "Peekaboot health: 'db' answered UP after 12500ms");
    }

    @Test
    void aCheckRunsAsAPeekabootProbe() {
        AtomicBoolean probing = new AtomicBoolean();
        HealthCheckRuns runs = runs(TEN_SECONDS, null, inline);

        startAndAwait(runs, () -> {
            probing.set(PeekabootProbe.isRunning());
            return Health.up().build();
        });

        assertThat(probing).isTrue();
    }

    @Test
    void eachCheckRunsOnANamedVirtualThread() {
        AtomicReference<Thread> checking = new AtomicReference<>();
        HealthCheckRuns runs = HealthCheckRuns.onVirtualThreads(TEN_SECONDS, null);

        HealthCheckRuns.Result result = startAndAwait(runs, () -> {
            checking.set(Thread.currentThread());
            return Health.up().build();
        });

        assertThat(result.health()).isEqualTo(Health.up().build());
        assertThat(checking.get().isVirtual()).isTrue();
        assertThat(checking.get().getName()).isEqualTo("peekaboot-health-check");
    }

    @Test
    void printsWholeSecondsAsSecondsAndTheRestAsMilliseconds() {
        assertThat(HealthCheckRuns.print(TEN_SECONDS)).isEqualTo("10s");
        assertThat(HealthCheckRuns.print(Duration.ofMillis(200))).isEqualTo("200ms");
        assertThat(HealthCheckRuns.print(Duration.ofMillis(1500))).isEqualTo("1500ms");
        assertThat(HealthCheckRuns.print(Duration.ZERO)).isEqualTo("0ms");
    }

    private HealthCheckRuns runs(Duration timeout, @Nullable Duration slowThreshold, Executor executor) {
        return new HealthCheckRuns(timeout, slowThreshold, executor, ticker::get);
    }

    private static HealthCheckRuns.Result startAndAwait(HealthCheckRuns runs, HealthIndicator indicator) {
        HealthCheckRuns.Run run = runs.start("db", indicator);
        return runs.await(run, runs.deadline());
    }

    /** Times the held check out at 10 s, then lets it answer at 12.5 s. */
    private void timeOutAndAnswerLate(HealthCheckRuns runs, HealthIndicator indicator) {
        HealthCheckRuns.Run run = runs.start("db", indicator);
        long deadline = runs.deadline();
        ticker.set(TEN_SECONDS.toNanos());
        assertTimedOut(runs.await(run, deadline), "Timed out after 10s");
        ticker.set(Duration.ofMillis(12_500).toNanos());
        held.getFirst().run();
    }

    private HealthIndicator after(Duration took, Health answer) {
        return () -> {
            ticker.addAndGet(took.toNanos());
            return answer;
        };
    }

    private static void assertTimedOut(HealthCheckRuns.Result result, String error) {
        assertThat(result.health()).isNotNull();
        assertThat(result.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.health().getDetails()).isEqualTo(Map.of("error", error));
    }

    private static void assertUndescribedDown(HealthCheckRuns.Result result) {
        assertThat(result.health()).isNotNull();
        assertThat(result.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.health().getDetails())
                .isEqualTo(Map.of("error", "Failed with an error it could not describe"));
    }

    /** Fails as an out-of-memory error can: describing it fails too. */
    private static final class UnreadableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new IllegalStateException("unreadable");
        }
    }

    /** Asserts the WARN lines logged so far, in order, and consumes them. */
    private void assertWarned(String... lines) {
        assertThat(log.appender().list)
                .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .containsExactly(Arrays.stream(lines)
                        .map(line -> tuple(Level.WARN, line))
                        .toArray(Tuple[]::new));
        log.appender().list.clear();
    }
}

package org.peekaboot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.peekaboot.backend.testsupport.Latches.awaitQuietly;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.security.DerivationGate.Outcome;

class DerivationGateTest {

    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void releaseParkedRuns() {
        release.countDown();
    }

    @Test
    void runsADerivationAndReportsItsAnswer() {
        var gate = new DerivationGate(1);

        assertThat(gate.check("right", () -> true)).isEqualTo(Outcome.MATCHED);
        assertThat(gate.check("wrong", () -> false)).isEqualTo(Outcome.REJECTED);
    }

    @Test
    void shedsACheckBeyondTheCapWithoutRunningIt() {
        var gate = new DerivationGate(2);
        parkedCheck(gate, "first");
        parkedCheck(gate, "second");
        var ran = new AtomicInteger();

        Outcome third = gate.check("third", counting(ran, () -> true));

        assertThat(third).isEqualTo(Outcome.SHED);
        assertThat(ran).hasValue(0);
    }

    /** Capacity one, so the second check is shared although the gate is full, as the dashboard's parallel requests are. */
    @Test
    void aSecondCheckOfTheSameHeaderSharesTheRun() throws Exception {
        var gate = new DerivationGate(1);
        var ran = new AtomicInteger();
        BooleanSupplier derivation = counting(ran, () -> true);

        Shared shared = callerAndWaiterOf(gate, derivation, derivation);

        assertThat(shared.caller().outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(shared.waiter().outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(ran).hasValue(1);
    }

    @Test
    void aSlotIsFreeAgainOnceItsRunEnds() throws Exception {
        var gate = new DerivationGate(1);
        Check first = parkedCheck(gate, "first");
        assertThat(gate.check("second", () -> true)).isEqualTo(Outcome.SHED);

        release.countDown();
        first.outcome();

        assertThat(gate.check("second", () -> true)).isEqualTo(Outcome.MATCHED);
    }

    @Test
    void aDerivationThatThrowsReleasesItsSlotAndItsWaiters() {
        var gate = new DerivationGate(1);
        var failure = new IllegalStateException("PBKDF2 is unavailable on this JVM");

        Shared shared = callerAndWaiterOf(
                gate,
                () -> {
                    throw failure;
                },
                () -> true);

        assertThatThrownBy(shared.caller()::outcome)
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isSameAs(failure);
        assertThatThrownBy(shared.waiter()::outcome)
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isSameAs(failure);
        assertThat(gate.check("another", () -> true)).isEqualTo(Outcome.MATCHED);
        assertThat(gate.check("same", () -> true)).isEqualTo(Outcome.MATCHED);
    }

    /** The gate catches no {@code Error}, so the waiter is released by a cancellation. */
    @Test
    void aDerivationThatDiesOfAnErrorReleasesItsSlotAndItsWaiters() {
        var gate = new DerivationGate(1);
        var failure = new LinkageError("no PBKDF2 provider could be loaded");

        Shared shared = callerAndWaiterOf(
                gate,
                () -> {
                    throw failure;
                },
                () -> true);

        assertThatThrownBy(shared.caller()::outcome)
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isSameAs(failure);
        assertThatThrownBy(shared.waiter()::outcome)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(CancellationException.class);
        assertThat(gate.check("another", () -> true)).isEqualTo(Outcome.MATCHED);
    }

    /** A check of "same" that ends as {@code callers} does, and a second check of it that was waiting by then. */
    private Shared callerAndWaiterOf(DerivationGate gate, BooleanSupplier callers, BooleanSupplier waiters) {
        var entered = new CountDownLatch(1);
        Check caller = start(gate, "same", parkedOn(entered, callers));
        awaitQuietly(entered);
        Check waiter = start(gate, "same", waiters);
        await().until(() -> waiter.thread().getState() == Thread.State.WAITING);
        release.countDown();
        return new Shared(caller, waiter);
    }

    /** A check of {@code header} that is inside its derivation when this returns and stays there until {@link #release} opens. */
    private Check parkedCheck(DerivationGate gate, String header) {
        var entered = new CountDownLatch(1);
        Check check = start(gate, header, parkedOn(entered, () -> true));
        awaitQuietly(entered);
        return check;
    }

    private BooleanSupplier parkedOn(CountDownLatch entered, BooleanSupplier answer) {
        return () -> {
            entered.countDown();
            awaitQuietly(release);
            return answer.getAsBoolean();
        };
    }

    private static BooleanSupplier counting(AtomicInteger ran, BooleanSupplier derivation) {
        return () -> {
            ran.incrementAndGet();
            return derivation.getAsBoolean();
        };
    }

    private static Check start(DerivationGate gate, String header, BooleanSupplier derivation) {
        var task = new FutureTask<>(() -> gate.check(header, derivation));
        return new Check(Thread.ofPlatform().start(task), task);
    }

    private record Shared(Check caller, Check waiter) {}

    private record Check(Thread thread, FutureTask<Outcome> task) {

        /** What the check returned, or an {@link ExecutionException} around what it threw. */
        Outcome outcome() throws Exception {
            return task.get(10, TimeUnit.SECONDS);
        }
    }
}

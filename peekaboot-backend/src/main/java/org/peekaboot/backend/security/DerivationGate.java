package org.peekaboot.backend.security;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Bounds how many password derivations run at once, so wrong passwords cannot take every request
 * thread into PBKDF2.
 *
 * <p>Checks of one header share one run. The dashboard's parallel requests carry the same header,
 * so they cost one derivation between them and none is shed.
 */
final class DerivationGate {

    /** One slot can be held by a wrong guess while one stays free for the real reader. */
    static final int MAX_CONCURRENT = 2;

    enum Outcome {
        MATCHED,
        REJECTED,
        /** Not derived, because every slot was taken. */
        SHED
    }

    private final int maxConcurrent;

    /** Per header, what its run hands the checks that share it: its outcome, or the exception it threw. */
    private final Map<String, CompletableFuture<Supplier<Outcome>>> runs = new HashMap<>();

    DerivationGate(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    int maxConcurrent() {
        return maxConcurrent;
    }

    /**
     * Runs {@code derivation} for {@code header}, waits for the run another thread has under way
     * for it, or answers {@link Outcome#SHED} at once when every slot is taken.
     *
     * <p>A waiter of a run that threw gets the exception that run threw, or a
     * {@code CancellationException} where that was an {@code Error}.
     */
    Outcome check(String header, BooleanSupplier derivation) {
        CompletableFuture<Supplier<Outcome>> run = new CompletableFuture<>();
        CompletableFuture<Supplier<Outcome>> underWay;
        synchronized (runs) {
            underWay = runs.get(header);
            if (underWay == null) {
                if (runs.size() >= maxConcurrent) {
                    return Outcome.SHED;
                }
                runs.put(header, run);
            }
        }
        if (underWay != null) {
            return underWay.join().get();
        }
        try {
            Outcome outcome = derivation.getAsBoolean() ? Outcome.MATCHED : Outcome.REJECTED;
            run.complete(() -> outcome);
            return outcome;
        } catch (RuntimeException failure) {
            run.complete(() -> {
                throw failure;
            });
            throw failure;
        } finally {
            synchronized (runs) {
                runs.remove(header);
            }
            // only an Error leaves the run open here, and its waiters must not wait for ever
            run.cancel(false);
        }
    }
}

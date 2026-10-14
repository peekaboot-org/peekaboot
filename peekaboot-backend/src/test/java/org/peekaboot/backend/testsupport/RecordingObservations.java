package org.peekaboot.backend.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A real registry whose handler records every observation started, scoped, failed and stopped on
 * it. Thread-safe: executor tests start and stop observations on pool threads.
 */
public final class RecordingObservations {

    private final ObservationRegistry registry = ObservationRegistry.create();
    private final List<Observation.Context> started = new CopyOnWriteArrayList<>();
    private final List<Observation.Context> scoped = new CopyOnWriteArrayList<>();
    private final List<Observation.Context> errored = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Observation.Context> stopped = new LinkedBlockingQueue<>();

    public RecordingObservations() {
        // supports every context, so observations are real rather than no-ops
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStart(Observation.Context context) {
                started.add(context);
            }

            @Override
            public void onScopeOpened(Observation.Context context) {
                scoped.add(context);
            }

            @Override
            public void onError(Observation.Context context) {
                errored.add(context);
            }

            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context);
            }
        });
    }

    public ObservationRegistry registry() {
        return registry;
    }

    public List<Observation.Context> startedNamed(String observationName) {
        return named(started, observationName);
    }

    public List<Observation.Context> scopedNamed(String observationName) {
        return named(scoped, observationName);
    }

    /** One entry per error recorded, so an error recorded twice shows twice. */
    public List<Observation.Context> erroredNamed(String observationName) {
        return named(errored, observationName);
    }

    /** The next observation named {@code observationName} to stop; a task's stops after its caller's future completes. */
    public Observation.Context awaitStopped(String observationName) throws InterruptedException {
        while (true) {
            Observation.Context context = stopped.poll(10, TimeUnit.SECONDS);
            assertThat(context)
                    .as("an observation named %s stopped", observationName)
                    .isNotNull();
            if (observationName.equals(context.getName())) {
                return context;
            }
        }
    }

    /** Runs the call with an observation named {@code parent} current. */
    public <T> T insideParentObservation(Callable<T> call) throws Exception {
        Observation parent = Observation.createNotStarted("parent", registry).start();
        try (Observation.Scope scope = parent.openScope()) {
            return call.call();
        } finally {
            parent.stop();
        }
    }

    /** Runs the task with an observation named {@code parent} current. */
    public void runInsideParentObservation(Runnable task) {
        try {
            insideParentObservation(() -> {
                task.run();
                return null;
            });
        } catch (RuntimeException | Error unchecked) {
            throw unchecked;
        } catch (Exception checked) {
            throw new IllegalStateException(checked);
        }
    }

    private static List<Observation.Context> named(List<Observation.Context> contexts, String observationName) {
        return contexts.stream()
                .filter(context -> observationName.equals(context.getName()))
                .toList();
    }
}

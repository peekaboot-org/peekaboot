package org.peekaboot.backend.testsupport;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** A real registry whose handler records every observation started and every scope opened on it. */
public final class RecordingObservations {

    private final ObservationRegistry registry = ObservationRegistry.create();
    private final List<Observation.Context> started = new ArrayList<>();
    private final List<Observation.Context> scoped = new ArrayList<>();

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
        });
    }

    public ObservationRegistry registry() {
        return registry;
    }

    public List<Observation.Context> startedNamed(String observationName) {
        return started.stream()
                .filter(context -> observationName.equals(context.getName()))
                .toList();
    }

    public List<Observation.Context> scopedNamed(String observationName) {
        return scoped.stream()
                .filter(context -> observationName.equals(context.getName()))
                .toList();
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
}

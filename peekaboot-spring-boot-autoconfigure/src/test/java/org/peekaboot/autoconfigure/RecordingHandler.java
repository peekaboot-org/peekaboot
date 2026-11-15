package org.peekaboot.autoconfigure;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records the name of every observation its registry starts. */
class RecordingHandler implements ObservationHandler<Observation.Context> {

    final List<String> started = new CopyOnWriteArrayList<>();

    @Override
    public boolean supportsContext(Observation.Context context) {
        return true;
    }

    @Override
    public void onStart(Observation.Context context) {
        started.add(context.getName());
    }
}

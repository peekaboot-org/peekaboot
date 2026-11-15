package org.peekaboot.backend.tracing.store;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** A store the trace journal can restore: what only its replay does, on top of TraceStore. */
public interface RestorableTraceStore extends TraceStore {

    /**
     * Ends the run in progress and begins the next: traces first stored from here on belong to it, and an
     * incomplete trace of an ended run is interrupted. Every span of the new run sorts after every span
     * stored before it. Only the trace journal's replay calls this, for each run it replays, and its restore
     * once more for the live run, before any live input reaches the store.
     *
     * @param startedAt when the run reported ready; null for a run that has not, or never did
     */
    void beginRun(@Nullable Instant startedAt);

    /**
     * Seeds {@code traceId}'s bundle with what a fold carried for the inputs its caps dropped. Only the
     * trace journal's replay calls this, before the trace's kept inputs.
     */
    void restoreRemnant(String traceId, TraceRemnant remnant);

    /**
     * While {@code held}, All evicts nothing, so a snapshot's replay keeps every trace it names until its
     * bucket lines list each bucket; releasing evicts All's oldest down to its cap. Only the trace
     * journal's replay calls this.
     */
    void holdAll(boolean held);

    /**
     * Lists exactly {@code traceIds} in {@code bucket}, oldest first, and no other trace; an id the store
     * holds no trace for is skipped. Only the trace journal's replay calls this, for a snapshot's bucket
     * lines.
     */
    void restoreBucket(TraceBucket bucket, List<String> traceIds);
}

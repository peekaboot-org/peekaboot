package org.peekaboot.backend.tracing.store;

/** Where the store's inputs go: straight into the store, or through the trace journal, which records them too. */
@FunctionalInterface
public interface TraceInputs {

    void accept(TraceInput input);

    /** Into {@code store} and nowhere else, as while storage is off. */
    static TraceInputs into(TraceStore store) {
        return input -> input.applyTo(store);
    }
}

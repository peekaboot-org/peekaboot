package org.peekaboot.backend.domain.trace;

/**
 * The marker Peekaboot's async-task instrumentation puts on the observation it raises, and the
 * one place every reader of it looks: the decorator sets it, {@code TraceTreeMapper} classifies
 * from it, and {@code TraceDataBundle} resolves async-subtree membership with it.
 *
 * <p>Classification keys on a tag rather than the span's name because Peekaboot controls both,
 * and a tag keeps the rule this project already states - kind and tags, not the name.
 */
public final class AsyncTaskMarker {

    /** The observation name, and so the name Micrometer records any derived meter under. */
    public static final String OBSERVATION_NAME = "peekaboot.async.task";

    /**
     * The span name. Set through {@code contextualName} rather than left as the observation
     * name, because an async entry span becomes a row title in the trace list and
     * {@code peekaboot.async.task} is a poor one.
     */
    public static final String CONTEXTUAL_NAME = "async task";

    /** Low-cardinality key whose presence identifies an async entry span. */
    public static final String TAG_KEY = "peekaboot.async";

    public static final String TAG_VALUE = "true";

    /** High-cardinality key naming the thread the task ran on; never a metric dimension. */
    public static final String THREAD_TAG_KEY = "peekaboot.async.thread";

    private AsyncTaskMarker() {}
}

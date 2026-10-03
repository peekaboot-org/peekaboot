package org.peekaboot.backend.domain.trace;

/** The names Peekaboot's event-listener instrumentation puts on the observation it raises. */
public final class EventListenerMarker {

    /** The observation name, and so the name Micrometer records any derived meter under. */
    public static final String OBSERVATION_NAME = "peekaboot.event.listener";

    /** Low-cardinality key: the simple names of the annotation's event classes, else of the method's parameter type. */
    public static final String EVENT_TYPE_TAG_KEY = "peekaboot.event.type";

    /** Low-cardinality key: the listener bean's class, fully qualified. */
    public static final String LISTENER_CLASS_TAG_KEY = "peekaboot.event.listener.class";

    /** Low-cardinality key: the listener method's name. */
    public static final String LISTENER_METHOD_TAG_KEY = "peekaboot.event.listener.method";

    private EventListenerMarker() {}
}

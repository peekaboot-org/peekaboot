package org.peekaboot.backend.lifecycle;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ApplicationContextEvent;

/**
 * A child context's events reach the parent too: its close (Boot's management context on a
 * separate port) and its ready event (a {@code SpringApplicationBuilder} hierarchy). A
 * listener that reports on the application acts on its own context's event alone.
 */
final class ContextEvents {

    private ContextEvents() {}

    // Identity is the point - the very context the listener belongs to, not one that
    // happens to compare equal.
    @SuppressWarnings({"PMD.CompareObjectsWithEquals", "ReferenceEquality"})
    static boolean fromOwnContext(ApplicationContextEvent event, ApplicationContext ownContext) {
        return event.getApplicationContext() == ownContext;
    }

    @SuppressWarnings({"PMD.CompareObjectsWithEquals", "ReferenceEquality"})
    static boolean fromOwnContext(ApplicationReadyEvent event, ApplicationContext ownContext) {
        return event.getApplicationContext() == ownContext;
    }
}

package org.peekaboot.backend.lifecycle;

import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ApplicationContextEvent;

/**
 * A child context's close (Boot's management context on a separate port) is forwarded to
 * the parent too; a listener that reports on the application acts on its own context's
 * event alone.
 */
final class ContextEvents {

    private ContextEvents() {}

    // Identity is the point - the very context the listener belongs to, not one that
    // happens to compare equal.
    @SuppressWarnings({"PMD.CompareObjectsWithEquals", "ReferenceEquality"})
    static boolean fromOwnContext(ApplicationContextEvent event, ApplicationContext ownContext) {
        return event.getApplicationContext() == ownContext;
    }
}

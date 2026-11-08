package org.peekaboot.backend.lifecycle;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ApplicationContextEvent;

/**
 * A child context's events reach the parent too: its close (Boot's management context on a
 * separate port) and its started and ready events (a {@code SpringApplicationBuilder}
 * hierarchy). A listener that reports on the application acts on its own context's event alone.
 */
public final class ContextEvents {

    private ContextEvents() {}

    public static boolean fromOwnContext(ApplicationContextEvent event, ApplicationContext ownContext) {
        return isOwn(event.getApplicationContext(), ownContext);
    }

    public static boolean fromOwnContext(ApplicationStartedEvent event, ApplicationContext ownContext) {
        return isOwn(event.getApplicationContext(), ownContext);
    }

    public static boolean fromOwnContext(ApplicationReadyEvent event, ApplicationContext ownContext) {
        return isOwn(event.getApplicationContext(), ownContext);
    }

    // Reference equality on purpose, since only the listener's own context counts.
    @SuppressWarnings({"PMD.CompareObjectsWithEquals", "ReferenceEquality"})
    private static boolean isOwn(ApplicationContext eventContext, ApplicationContext ownContext) {
        return eventContext == ownContext;
    }
}

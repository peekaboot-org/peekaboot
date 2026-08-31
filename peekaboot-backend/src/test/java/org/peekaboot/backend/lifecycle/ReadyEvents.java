package org.peekaboot.backend.lifecycle;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.ConfigurableWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Builds the {@link ApplicationReadyEvent}s the lifecycle tests fire at {@link ServerUrlResolver}
 * and {@link ApplicationReadyListener}. Each variant also decides whether the dashboard's
 * configuration bean is in the context, which is how the resolver tells that Peekaboot is
 * serving its dashboard.
 */
final class ReadyEvents {

    private ReadyEvents() {}

    /** A servlet application on {@code port} that does not serve the Peekaboot dashboard. */
    static ApplicationReadyEvent webApplication(int port) {
        return webApplication(port, false);
    }

    /** A servlet application on {@code port} that also serves the Peekaboot dashboard. */
    static ApplicationReadyEvent webApplicationServingDashboard(int port) {
        return webApplication(port, true);
    }

    /**
     * An application with no web server. The dashboard bean is present, so a resolver that
     * returns nothing for this event can only be reacting to the missing server.
     */
    static ApplicationReadyEvent nonWebApplication() {
        var event = mock(ApplicationReadyEvent.class);
        var context = mock(ConfigurableApplicationContext.class);
        when(event.getApplicationContext()).thenReturn(context);
        when(context.containsBean(ServerUrlResolver.DASHBOARD_CONFIG_BEAN_NAME)).thenReturn(true);
        return event;
    }

    private static ApplicationReadyEvent webApplication(int port, boolean servesDashboard) {
        var event = mock(ApplicationReadyEvent.class);
        var context = mock(ConfigurableWebServerApplicationContext.class);
        var webServer = mock(WebServer.class);
        when(event.getApplicationContext()).thenReturn(context);
        when(context.getWebServer()).thenReturn(webServer);
        when(webServer.getPort()).thenReturn(port);
        when(context.containsBean(ServerUrlResolver.DASHBOARD_CONFIG_BEAN_NAME)).thenReturn(servesDashboard);
        return event;
    }
}

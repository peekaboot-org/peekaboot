package org.peekaboot.testingapp.order;

import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Remembers the port the embedded server actually bound to.
 *
 * <p>{@code CustomerClient} calls this application's own API to produce a realistic
 * outbound span. Under {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} the port is
 * chosen at startup and never appears in {@code server.port}, so it has to be captured
 * from the event.
 */
@Component
public class LocalPortSupplier {

    private volatile int port;


    @EventListener
    public void onWebServerReady(WebServerInitializedEvent event) {

        this.port = event.getWebServer().getPort();
    }


    public int port() {

        return port;
    }
}

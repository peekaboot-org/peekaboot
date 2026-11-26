package org.peekaboot.testingapp.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Tomcat rewrites the client address itself ({@code web-server}; {@code native} on Boot 4.1). */
class NativeForwardedHeadersIT extends ForwardedHeadersContract {

    @DynamicPropertySource
    static void strategy(DynamicPropertyRegistry registry) {
        registry.add(strategyProperty(), () -> BootLine.isAtLeast(4, 2) ? "web-server" : "native");
    }
}

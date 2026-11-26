package org.peekaboot.testingapp.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Spring's {@code ForwardedHeaderFilter} unwraps the request and hides the headers. */
class FrameworkForwardedHeadersIT extends ForwardedHeadersContract {

    @DynamicPropertySource
    static void strategy(DynamicPropertyRegistry registry) {
        registry.add(strategyProperty(), () -> "framework");
    }
}

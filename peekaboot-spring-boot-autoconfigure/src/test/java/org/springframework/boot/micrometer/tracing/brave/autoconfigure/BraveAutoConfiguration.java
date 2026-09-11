package org.springframework.boot.micrometer.tracing.brave.autoconfigure;

import static org.mockito.Mockito.mock;

import io.micrometer.tracing.Tracer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Stands in for Boot's Brave auto-configuration, which this module does not compile
 * against. It bears that class's exact name because the {@code afterName} edge in
 * {@code DevToolbarAutoConfiguration} is a string, and only a class of this name lets an
 * auto-configuration sort exercise it: by name alone {@code org.springframework} sorts
 * after {@code org.peekaboot}, so the {@link Tracer} bean it registers would otherwise
 * arrive after the toolbar's conditions have been evaluated.
 */
@AutoConfiguration
public class BraveAutoConfiguration {

    @Bean
    Tracer braveTracer() {
        return mock(Tracer.class);
    }
}

package org.peekaboot.autoconfigure;

import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.util.ClassUtils;

/** Every Peekaboot auto-configuration the {@code .imports} file registers, for the tests that check them all at once. */
final class RegisteredAutoConfigurations {

    private RegisteredAutoConfigurations() {}

    static List<Class<?>> classes() {
        ClassLoader classLoader = RegisteredAutoConfigurations.class.getClassLoader();
        return ImportCandidates.load(AutoConfiguration.class, classLoader).getCandidates().stream()
                .filter(name -> name.startsWith("org.peekaboot."))
                .<Class<?>>map(name -> ClassUtils.resolveClassName(name, classLoader))
                .toList();
    }

    /** A servlet context with every registered auto-configuration, for the tests that check them all at once. */
    static WebApplicationContextRunner servletRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(classes().toArray(Class<?>[]::new)))
                // the DispatcherServlet a servlet application brings, which tells Peekaboot where its dashboard sits
                .withConfiguration(AutoConfigurations.of(DispatcherServletAutoConfiguration.class))
                // the beans the conditional halves look for, so nothing is off for lack of them
                .withBean(Tracer.class, () -> mock(Tracer.class))
                .withBean(ObservationRegistry.class, ObservationRegistry::create)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new);
    }
}

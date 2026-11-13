package org.peekaboot.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.peekaboot.backend.config.PeekabootPaths;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.mock.web.MockServletContext;
import org.springframework.util.Assert;
import org.springframework.web.context.support.GenericWebApplicationContext;

/**
 * A servlet application with Peekaboot on, started by {@link SpringMvcAbsentTest} through a class
 * loader of its own. Public, since that loader's copy of this class sits in another runtime
 * package than the test that calls it.
 */
public final class SpringMvcAbsentLaunch {

    private SpringMvcAbsentLaunch() {}

    public static void run() {
        try (GenericWebApplicationContext context = new GenericWebApplicationContext(new MockServletContext())) {
            AnnotationConfigUtils.registerAnnotationConfigProcessors(context);
            TestPropertyValues.of("peekaboot.enabled=true").applyTo(context);
            // the beans the conditional halves look for, so nothing is off for lack of them
            context.registerBean(Tracer.class, () -> Tracer.NOOP);
            context.registerBean(ObservationRegistry.class, ObservationRegistry::create);
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.registerBean(Application.class);
            context.refresh();
            Assert.state(
                    context.getBeanNamesForType(PeekabootPaths.class).length > 0,
                    "Peekaboot stayed off, so this launch shows nothing");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PeekabootAutoConfigurations.class)
    static class Application {}

    /**
     * Imports by name, as Boot's own selector does: the conditions are read from bytecode, and a
     * class they turn away is never loaded.
     */
    static class PeekabootAutoConfigurations implements ImportSelector {

        @Override
        public String[] selectImports(AnnotationMetadata importingClassMetadata) {
            ClassLoader classLoader = SpringMvcAbsentLaunch.class.getClassLoader();
            return ImportCandidates.load(AutoConfiguration.class, classLoader).getCandidates().stream()
                    .filter(name -> name.startsWith("org.peekaboot."))
                    .toArray(String[]::new);
        }
    }
}

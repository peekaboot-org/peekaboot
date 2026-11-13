package org.peekaboot.autoconfigure;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * States the launch a context runs under. Inside a JUnit run the real detection always reads a
 * test launch, and a context runner never runs the detection at all.
 */
public final class DetectedLaunches {

    private DetectedLaunches() {}

    static ApplicationContextInitializer<ConfigurableApplicationContext> as(LocalDevDetector.LaunchKind kind) {
        // addLast drops a source of the same name first, so this replaces what the detection left
        return context -> context.getEnvironment().getPropertySources().addLast(new DetectedLaunch(kind));
    }

    /** For {@code @ContextConfiguration(initializers = ...)} outside this package, which cannot name a launch kind. */
    public static final class LocalDevelopment
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            as(LocalDevDetector.LaunchKind.LOCAL_DEV).initialize(context);
        }
    }
}

package org.peekaboot.autoconfigure;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

/**
 * The launch {@link PeekabootDefaultsEnvironmentPostProcessor} detected, carried on the
 * environment in a source of its own type. It answers no property, so no property file,
 * environment variable or system property can claim or deny a launch, and it needs no bean,
 * so it is readable before the context exists. It also stays where the detected defaults are
 * folded into {@code defaultProperties}.
 */
final class DetectedLaunch extends PropertySource<LocalDevDetector.LaunchKind> {

    static final String NAME = "peekabootDetectedLaunch";

    DetectedLaunch(LocalDevDetector.LaunchKind kind) {
        super(NAME, kind);
    }

    @Override
    public @Nullable Object getProperty(String name) {
        return null;
    }

    /** Empty where the post-processor never ran on this environment. */
    static Optional<LocalDevDetector.LaunchKind> in(Environment environment) {
        if (environment instanceof ConfigurableEnvironment configurable
                && configurable.getPropertySources().get(NAME) instanceof DetectedLaunch detected) {
            return Optional.of(detected.getSource());
        }
        return Optional.empty();
    }

    static boolean is(Environment environment, LocalDevDetector.LaunchKind kind) {
        return in(environment).filter(kind::equals).isPresent();
    }
}

package org.peekaboot.autoconfigure;

import java.util.Optional;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

/**
 * The launch {@link PeekabootDefaultsEnvironmentPostProcessor} detected, carried on the
 * environment in a source of its own type. It answers no property, so no property file,
 * environment variable or system property can claim or deny a launch, and it needs no bean,
 * so it is readable before the context exists. It also stays where the detected defaults are
 * folded into {@code defaultProperties}.
 *
 * <p>A stub, because Boot's binder skips those. Any other source that cannot list its names
 * makes every {@code @ConfigurationProperties} bind walk its nested beans to their leaves, and
 * this one is on every host that carries the starter, Peekaboot on or off.
 */
final class DetectedLaunch extends PropertySource.StubPropertySource {

    static final String NAME = "peekabootDetectedLaunch";

    private final LocalDevDetector.LaunchKind kind;

    DetectedLaunch(LocalDevDetector.LaunchKind kind) {
        super(NAME);
        this.kind = kind;
    }

    /** Empty where the post-processor never ran on this environment. */
    static Optional<LocalDevDetector.LaunchKind> in(Environment environment) {
        if (environment instanceof ConfigurableEnvironment configurable
                && configurable.getPropertySources().get(NAME) instanceof DetectedLaunch detected) {
            return Optional.of(detected.kind);
        }
        return Optional.empty();
    }

    static boolean is(Environment environment, LocalDevDetector.LaunchKind kind) {
        return in(environment).filter(kind::equals).isPresent();
    }
}

package org.peekaboot.autoconfigure;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.diagnostics.FailureAnalyzedException;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.env.PropertyResolver;

/**
 * Stops startup when a switch in {@link PeekabootPropertyKeys#BOOLEAN_SWITCHES} holds a value
 * its {@code @ConditionalOnBooleanProperty} and the bound {@code PeekabootProperties} would read
 * differently. The conditions compare the raw value with {@code equalsIgnoreCase}, while the
 * binder trims it and also reads {@code yes}, {@code on} and {@code 1} as true, so such a value
 * leaves the feature off while the Config tab shows it on. {@code no}, {@code off} and {@code 0}
 * read as off both ways and pass, except on {@link PeekabootPropertyKeys#SWITCHES_ALSO_GATED_ON_FALSE}.
 */
final class BooleanSwitches {

    private BooleanSwitches() {}

    /**
     * Reads each switch as the conditions do, as a string, and as the binder does, through Boot's
     * shared conversion service, and accepts it only where the two agree.
     *
     * @throws FailureAnalyzedException naming every offending switch and its value, which Boot
     *     reports as a failure analysis rather than a stack trace
     */
    static void requireUnambiguous(PropertyResolver properties) {
        List<String> offending = PeekabootPropertyKeys.BOOLEAN_SWITCHES.stream()
                .filter(properties::containsProperty)
                .filter(key -> !readAlike(key, properties.getProperty(key)))
                .toList();
        if (offending.isEmpty()) {
            return;
        }
        String found = offending.stream()
                .map(key -> "    " + key + " = '" + properties.getProperty(key) + "'")
                .collect(Collectors.joining("\n"));
        throw new FailureAnalyzedException(
                "Peekaboot's conditions and its bound properties would read these switches differently:\n" + found,
                "Set " + String.join(", ", offending) + " to true or false.");
    }

    private static boolean readAlike(String key, String value) {
        return binderReading(value)
                .map(bound -> bound
                        ? "true".equalsIgnoreCase(value)
                        : !PeekabootPropertyKeys.SWITCHES_ALSO_GATED_ON_FALSE.contains(key)
                                || "false".equalsIgnoreCase(value))
                .orElse(false);
    }

    /**
     * Empty where the binder has no reading: a value it cannot convert, or a blank one, which it
     * cannot bind to a {@code boolean} and the detection's own read takes as unset.
     */
    private static Optional<Boolean> binderReading(String value) {
        try {
            return Optional.ofNullable(
                    ApplicationConversionService.getSharedInstance().convert(value, Boolean.class));
        } catch (ConversionException unconvertible) {
            return Optional.empty();
        }
    }
}

package org.peekaboot.autoconfigure;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.diagnostics.FailureAnalyzedException;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertyResolver;

/**
 * Keeps the conditions and the bound properties reading every switch in
 * {@link PeekabootPropertyKeys#BOOLEAN_SWITCHES} the same way. The conditions compare the raw
 * value with {@code equalsIgnoreCase}, while the binder trims it and also reads {@code yes},
 * {@code on} and {@code 1} as true, so such a value would leave the feature off while the Config
 * tab shows it on; startup stops on it. {@code no}, {@code off} and {@code 0} read as off both
 * ways and pass, except on {@link PeekabootPropertyKeys#SWITCHES_ALSO_GATED_ON_FALSE}. A blank
 * value, which compose or Helm templating leaves for an unset variable, reads as unset.
 *
 * <p>The source that shadows a blank is a snapshot taken at startup; a later environment refresh
 * does not update it.
 */
final class BooleanSwitches {

    private static final String BLANK_SWITCHES_PROPERTY_SOURCE_NAME = "peekabootBlankSwitches";

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

    /**
     * Shadows every blank switch and detected key with the value the next property source down
     * holds, or else its detected value, or else the switch's unset default. The conditions would
     * read the blank as off, and the binder cannot bind it to a {@code boolean} at all. The
     * detected value is passed in rather than looked up because an application's own
     * {@code defaultProperties} blank sits on top of it in the same source.
     */
    static void treatBlanksAsUnset(ConfigurableEnvironment environment, Map<String, ?> detected) {
        Set<String> keys = new LinkedHashSet<>(PeekabootPropertyKeys.BOOLEAN_SWITCHES);
        keys.addAll(detected.keySet());
        Map<String, Object> shadowing = new LinkedHashMap<>();
        for (String key : keys) {
            if (isBlank(environment.getProperty(key))) {
                shadowing.put(
                        key,
                        firstValueBeneathTheBlank(environment, key)
                                .or(() -> Optional.ofNullable(detected.get(key)).map(String::valueOf))
                                .orElseGet(() -> unsetDefault(key)));
            }
        }
        if (!shadowing.isEmpty()) {
            environment
                    .getPropertySources()
                    .addFirst(new MapPropertySource(BLANK_SWITCHES_PROPERTY_SOURCE_NAME, shadowing));
        }
    }

    /** Through the same relaxed names the binder uses, so {@code PEEKABOOT_ENABLED} counts as {@code peekaboot.enabled}. */
    private static Optional<String> firstValueBeneathTheBlank(ConfigurableEnvironment environment, String key) {
        ConfigurationPropertyName name = ConfigurationPropertyName.of(key);
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            ConfigurationProperty property = source.getConfigurationProperty(name);
            if (property == null) {
                continue;
            }
            // a placeholder that resolves blank, ${TRACING:} with TRACING unset, is no value either
            String value = environment.resolvePlaceholders(String.valueOf(property.getValue()));
            if (!value.isBlank()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private static String unsetDefault(String key) {
        String unsetDefault = PeekabootPropertyKeys.UNSET_SWITCH_DEFAULTS.get(key);
        if (unsetDefault == null) {
            throw new IllegalStateException("No unset default for " + key + "; add it to UNSET_SWITCH_DEFAULTS");
        }
        return unsetDefault;
    }

    private static boolean isBlank(@Nullable String value) {
        return value != null && value.isBlank();
    }

    private static boolean readAlike(String key, String value) {
        return binderReading(value)
                .map(bound -> bound
                        ? "true".equalsIgnoreCase(value)
                        : !PeekabootPropertyKeys.SWITCHES_ALSO_GATED_ON_FALSE.contains(key)
                                || "false".equalsIgnoreCase(value))
                .orElse(false);
    }

    /** Empty where the binder has no reading: a value it cannot convert. */
    private static Optional<Boolean> binderReading(String value) {
        try {
            return Optional.ofNullable(
                    ApplicationConversionService.getSharedInstance().convert(value, Boolean.class));
        } catch (ConversionException unconvertible) {
            return Optional.empty();
        }
    }
}

package org.peekaboot.autoconfigure;

import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.capture.MdcKeys;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * Resolves the MDC keys the application's tracing writes the trace and span id under.
 * Boot 4.2 lets an app rename them with {@code management.tracing.mdc.*}. Boot 4.1 ignores those
 * properties and keeps writing the defaults, so reading them there would unlink every log line.
 */
final class TracingMdcKeys {

    static final String BOOT_MDC_SETTINGS =
            "org.springframework.boot.micrometer.tracing.autoconfigure.TracingProperties$Mdc";

    private TracingMdcKeys() {}

    static MdcKeys of(Environment environment, @Nullable ClassLoader classLoader) {
        if (!ClassUtils.isPresent(BOOT_MDC_SETTINGS, classLoader)) {
            return MdcKeys.DEFAULTS;
        }
        Binder binder = Binder.get(environment);
        return new MdcKeys(
                binder.bind("management.tracing.mdc.trace-id-key", String.class).orElse(MdcKeys.DEFAULTS.traceId()),
                binder.bind("management.tracing.mdc.span-id-key", String.class).orElse(MdcKeys.DEFAULTS.spanId()));
    }
}

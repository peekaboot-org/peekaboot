package org.peekaboot.testingapp;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.logging.LoggingSystemFactory;
import org.springframework.boot.logging.logback.LogbackLoggingSystem;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Boot's Logback system for a JVM that runs many applications side by side, as this module's
 * failsafe JVM does. Boot assumes one application per JVM. Closing any context marks the shared
 * Logback context uninitialised, and the next application to start then denies every log event,
 * JVM-wide, until it has reset Logback, a reset that also detaches every appender added in code.
 * Even a start that finds Logback configured denies every event for a moment while it stores its
 * environment. Requests the other applications serve meanwhile lose their log lines.
 *
 * <p>Here the first start configures Logback and every later start leaves it alone until the
 * JVM exits. Levels an application sets still apply, since Boot sets them outside
 * {@link #initialize}; a level a closed application set stays for the JVM's life, which is
 * harmless while the suite only ever sets {@code org.peekaboot: INFO}. Registered in this
 * module's test {@code META-INF/spring.factories}.
 */
public class SharedJvmLogbackLoggingSystem extends LogbackLoggingSystem {

    private static volatile boolean configured;

    public SharedJvmLogbackLoggingSystem(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public void initialize(
            LoggingInitializationContext initializationContext,
            @Nullable String configLocation,
            @Nullable LogFile logFile) {
        // Boot stores each start's environment under a JVM-wide suppress-all filter; only the
        // structured log encoder reads it, and only while the first start configures Logback
        if (configured) {
            return;
        }
        super.initialize(initializationContext, configLocation, logFile);
        configured = true;
    }

    @Override
    public void cleanUp() {
        // a later start would reset the context the other applications still log through
    }

    /** Ordered ahead of Boot's own Logback factory. */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class Factory implements LoggingSystemFactory {

        @Override
        public LoggingSystem getLoggingSystem(ClassLoader classLoader) {
            return new SharedJvmLogbackLoggingSystem(classLoader);
        }
    }
}

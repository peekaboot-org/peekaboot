package org.peekaboot.autoconfigure;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;

/**
 * Restores peekaboot's log capture after Spring Boot re-initialises Logback.
 *
 * <p>Every application that starts has {@link LoggingApplicationListener} reconfigure the
 * JVM-wide {@code LoggerContext}, which detaches and stops every appender that was registered
 * in code. Appenders declared in {@code logback.xml} are recreated by the configuration that
 * follows; peekaboot's is not. Any application context that is still serving requests would
 * therefore keep recording traces while silently capturing no logs against them - a fully
 * populated toolbar whose log counts are all zero.
 *
 * <p>Logback's own reset-resistant listeners cannot cover this: Spring Boot stops the logger
 * context before resetting it, and stopping drops every listener, reset-resistant ones
 * included. Only a hook outside Logback - registered for every application through
 * {@code spring.factories}, and ordered to run once logging has been initialised - can see
 * the re-initialisation and repair it.
 */
class LogbackCaptureReinstaller implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    private static final boolean LOGBACK_PRESENT = ClassUtils.isPresent(
            "ch.qos.logback.classic.LoggerContext", LogbackCaptureReinstaller.class.getClassLoader());

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        if (LOGBACK_PRESENT) {
            reattachCaptureAppenders();
        }
    }

    /** Kept apart so that no Logback type is resolved when Logback is absent. */
    private void reattachCaptureAppenders() {
        DevToolbarAutoConfiguration.LogbackAppenderRegistrar.reattachLiveAppenders();
    }

    @Override
    public int getOrder() {
        // LoggingApplicationListener performs the re-initialisation on this same event
        return LoggingApplicationListener.DEFAULT_ORDER + 1;
    }
}

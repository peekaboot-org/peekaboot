package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.peekaboot.testsupport.LogCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;

/**
 * This module runs many applications in one JVM, and so against one Logback context. Pins
 * {@code SharedJvmLogbackLoggingSystem}: an application starting after another one closed
 * leaves the events and appenders of everything else in the JVM alone. The event is logged on
 * the starting application's own thread, before Boot's logging listener handles the same
 * event, which is the window a request on another context would otherwise fall into.
 */
// Boots its own applications outside spring-test's context-load lock, like AutoSecuredDashboardIT.
@Isolated
class ContextBootLoggingIT {

    private static final Logger log = LoggerFactory.getLogger(ContextBootLoggingIT.class);

    private static final String PROBE = "peekaboot.testingapp.probe";

    @Test
    void anApplicationStartingAfterAnotherClosedDropsNoEventAndNoAppender() {
        application().run().close();

        try (LogCapture capture = LogCapture.attach(ContextBootLoggingIT.class);
                ConfigurableApplicationContext ignored = application()
                        .listeners(new LogsWhileTheEnvironmentIsPrepared())
                        .run()) {
            log.info("logged after the boot");

            assertThat(capture.appender().list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("logged while the environment is prepared", "logged after the boot");
        }
    }

    /**
     * Boot stores each start's environment while a JVM-wide filter denies every log event, even
     * on a start that finds Logback configured. The probe runs inside that store, on the starting
     * thread, so any call made while logging is denied is recorded.
     */
    @Test
    void aLaterStartNeverDeniesLoggingWhileItInitialises() {
        application().run().close();
        List<Boolean> loggingEnabledDuringInitialise = new ArrayList<>();
        LoggingInitializationContext probe = new LoggingInitializationContext(new StandardEnvironment()) {
            @Override
            public Environment getEnvironment() {
                loggingEnabledDuringInitialise.add(log.isErrorEnabled());
                return super.getEnvironment();
            }
        };

        LoggingSystem.get(ContextBootLoggingIT.class.getClassLoader()).initialize(probe, null, null);

        assertThat(loggingEnabledDuringInitialise).doesNotContain(false);
    }

    /** Guards a Boot behaviour the shared logging system must keep, not the window. */
    @Test
    void aLevelALaterApplicationSetsStillApplies() {
        application().run().close();

        try (ConfigurableApplicationContext booted = application().run("--logging.level." + PROBE + "=DEBUG")) {
            assertThat(LoggerFactory.getLogger(PROBE).isDebugEnabled()).isTrue();
            booted.getBean(LoggingSystem.class).setLogLevel(PROBE, null);
        }
    }

    private static SpringApplicationBuilder application() {
        return new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false);
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfig {}

    /** Ordered ahead of Boot's LoggingApplicationListener, which handles the same event. */
    private static final class LogsWhileTheEnvironmentIsPrepared
            implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            log.info("logged while the environment is prepared");
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}

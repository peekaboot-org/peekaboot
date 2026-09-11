package org.peekaboot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.boot.context.event.ApplicationReadyEvent;

/**
 * The listener does not build the message, only dispatches it - what matters here is that a WARN
 * posture reaches the log at WARN and an INFO one at INFO, since a swap between the two would
 * silently downgrade a certain-exposure warning.
 */
class SecurityPostureListenerTest {

    private static final Path FILE = Path.of("/home/app/.peekaboot/com.acme.orders/security.properties");

    private static final ApplicationReadyEvent EVENT = mock(ApplicationReadyEvent.class);

    @Test
    void onApplicationEvent_logsAWarnPostureAtWarn() {
        var posture = SecurityPosture.disabledOnADeployment();
        var expectedText = posture.report().orElseThrow().text();

        try (var logs = LogCapture.attach(SecurityPostureListener.class)) {
            new SecurityPostureListener(posture).onApplicationEvent(EVENT);

            assertThat(logs.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo(expectedText);
            });
        }
    }

    @Test
    void onApplicationEvent_logsAnInfoPostureAtInfo() {
        var posture = SecurityPosture.armed(generated(), FILE, true, false);
        var expectedText = posture.report().orElseThrow().text();

        try (var logs = LogCapture.attach(SecurityPostureListener.class)) {
            new SecurityPostureListener(posture).onApplicationEvent(EVENT);

            assertThat(logs.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage()).isEqualTo(expectedText);
            });
        }
    }

    @Test
    void onApplicationEvent_logsNothingForAQuietPosture() {
        try (var logs = LogCapture.attach(SecurityPostureListener.class)) {
            new SecurityPostureListener(SecurityPosture.quiet()).onApplicationEvent(EVENT);

            assertThat(logs.appender().list).isEmpty();
        }
    }

    private static DashboardCredentials generated() {
        return new DashboardCredentials(
                "orders-admin",
                PasswordHash.of("hunter2"),
                "hunter2",
                Instant.parse("2026-09-11T08:15:30Z"),
                DashboardCredentials.Origin.GENERATED);
    }
}

package org.peekaboot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Latches.awaitQuietly;
import static org.peekaboot.backend.testsupport.Requests.get;

import ch.qos.logback.classic.Level;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.testsupport.Credentials;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.mock.web.MockHttpServletResponse;

class CredentialsAccessTest {

    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void releaseTheHeldCheck() {
        release.countDown();
    }

    @Test
    void aWrongPasswordBeyondTheCapIsChallengedUnchecked() throws Exception {
        var gate = new DerivationGate(1);
        hold(gate);
        var access = new CredentialsAccess(Credentials.loaded("s3cret"), new CredentialCache(), gate);

        try (LogCapture logs = LogCapture.attach(DashboardAuthenticationFilter.class, Level.WARN)) {
            assertThat(statusFor(access, "wrong")).isEqualTo(401);
            assertThat(statusFor(access, "wrong again")).isEqualTo(401);

            assertThat(logs.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Peekaboot refused a sign-in unchecked because 1 password checks were already"
                                + " running. It logs this once.");
            });
        }
    }

    @Test
    void stripsControlCharactersFromTheUsernameItLogs() throws Exception {
        assertThat(lineLoggedFor("orders\r\nINFO forged\u001b[31m"))
                .isEqualTo("Rejected credentials for /peekaboot/api/insights - presented username was"
                        + " orders??INFO forged?[31m");
    }

    @Test
    void stripsTheControlCharactersBeyondAsciiToo() throws Exception {
        assertThat(lineLoggedFor("orders\u0085INFO forged\u009b31m"))
                .isEqualTo("Rejected credentials for /peekaboot/api/insights - presented username was"
                        + " orders?INFO forged?31m");
    }

    /** Takes the gate's one slot with a check that is inside its derivation when this returns. */
    private void hold(DerivationGate gate) {
        var entered = new CountDownLatch(1);
        Thread.ofPlatform()
                .start(() -> gate.check("held", () -> {
                    entered.countDown();
                    awaitQuietly(release);
                    return false;
                }));
        awaitQuietly(entered);
    }

    private static int statusFor(CredentialsAccess access, String password) throws IOException {
        return statusFor(access, "orders-admin", password);
    }

    private static int statusFor(CredentialsAccess access, String username, String password) throws IOException {
        var request = get("/peekaboot/api/insights");
        request.addHeader(
                "Authorization",
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8)));
        var response = new MockHttpServletResponse();

        assertThat(access.admit(request, response)).isFalse();
        return response.getStatus();
    }

    /** The one DEBUG line the guard writes for a sign-in under a username that is not Peekaboot's. */
    private static String lineLoggedFor(String username) throws IOException {
        var access = new CredentialsAccess(Credentials.loaded("s3cret"), new CredentialCache());

        try (LogCapture logs = LogCapture.attach(DashboardAuthenticationFilter.class, Level.DEBUG)) {
            assertThat(statusFor(access, username, "s3cret")).isEqualTo(401);

            assertThat(logs.appender().list)
                    .singleElement()
                    .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.DEBUG));
            return logs.appender().list.getFirst().getFormattedMessage();
        }
    }
}

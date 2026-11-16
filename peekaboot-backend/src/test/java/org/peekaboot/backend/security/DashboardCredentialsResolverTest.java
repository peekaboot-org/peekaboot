package org.peekaboot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.testsupport.PosixPermissions;
import org.peekaboot.testsupport.LogCapture;

class DashboardCredentialsResolverTest {

    @Test
    void resolvePrefersTheConfiguredPasswordOverAStoredOne(@TempDir Path dir) {
        var file = new CredentialsFile(dir.resolve("security.properties"));
        var generated =
                new DashboardCredentialsResolver(new PeekabootProperties.Security(), file, "orders-service").resolve();
        var security = new PeekabootProperties.Security();
        security.setPassword("from-the-vault");

        var credentials = new DashboardCredentialsResolver(security, file, "orders-service").resolve();

        assertThat(credentials.origin()).isEqualTo(DashboardCredentials.Origin.CONFIGURED);
        assertThat(credentials.passwordHash().matches("from-the-vault")).isTrue();
        assertThat(file.read()).hasValueSatisfying(stored -> {
            assertThat(stored.passwordHash().matches(generated.plaintext())).isTrue();
            assertThat(stored.passwordHash().matches("from-the-vault")).isFalse();
        });
    }

    @Test
    void resolvePersistsNothingForAConfiguredPassword(@TempDir Path dir) {
        var security = new PeekabootProperties.Security();
        security.setPassword("from-the-vault");
        var file = new CredentialsFile(dir.resolve("security.properties"));

        var credentials = new DashboardCredentialsResolver(security, file, "orders-service").resolve();

        assertThat(credentials.plaintext()).isNull();
        assertThat(file.read()).isEmpty();
    }

    @Test
    void resolveDatesConfiguredAndGeneratedCredentialsByTheClock(@TempDir Path dir) {
        var clock = Clock.fixed(Instant.parse("2026-09-11T08:00:00Z"), ZoneOffset.UTC);
        var security = new PeekabootProperties.Security();
        security.setPassword("from-the-vault");

        var configured = new DashboardCredentialsResolver(
                        security, CredentialsFile.unpersisted(), "orders-service", clock)
                .resolve();
        var generated = new DashboardCredentialsResolver(
                        new PeekabootProperties.Security(),
                        new CredentialsFile(dir.resolve("security.properties")),
                        "orders-service",
                        clock)
                .resolve();

        assertThat(configured.createdAt()).isEqualTo(clock.instant());
        assertThat(generated.createdAt()).isEqualTo(clock.instant());
    }

    @Test
    void resolveGeneratesAndPersistsWhenNothingIsConfiguredOrStored(@TempDir Path dir) {
        var file = new CredentialsFile(dir.resolve("security.properties"));

        var credentials =
                new DashboardCredentialsResolver(new PeekabootProperties.Security(), file, "orders-service").resolve();

        assertThat(credentials.origin()).isEqualTo(DashboardCredentials.Origin.GENERATED);
        assertThat(credentials.plaintext()).isNotNull();
        assertThat(credentials.passwordHash().matches(credentials.plaintext())).isTrue();
        assertThat(file.read()).isPresent();
    }

    /** The whole point of the file: the password survives a restart. */
    @Test
    void resolveReusesTheStoredPasswordOnASecondRun(@TempDir Path dir) {
        var file = new CredentialsFile(dir.resolve("security.properties"));
        var first =
                new DashboardCredentialsResolver(new PeekabootProperties.Security(), file, "orders-service").resolve();

        var second =
                new DashboardCredentialsResolver(new PeekabootProperties.Security(), file, "orders-service").resolve();

        assertThat(second.origin()).isEqualTo(DashboardCredentials.Origin.LOADED);
        assertThat(second.plaintext()).isNull();
        assertThat(second.passwordHash().matches(first.plaintext())).isTrue();
        assertThat(second.createdAt()).isEqualTo(first.createdAt());
    }

    @Test
    void resolveReplacesAnExposedFileWithAnOwnerOnlyOne(@TempDir Path dir) throws IOException {
        PosixPermissions.assumeSupported();
        var path = dir.resolve("security.properties");
        var file = new CredentialsFile(path);
        var exposed =
                new DashboardCredentialsResolver(new PeekabootProperties.Security(), file, "orders-service").resolve();
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));

        try (LogCapture capture = LogCapture.attach(CredentialsFile.class)) {
            var credentials = new DashboardCredentialsResolver(
                            new PeekabootProperties.Security(), file, "orders-service")
                    .resolve();

            assertThat(credentials.origin()).isEqualTo(DashboardCredentials.Origin.GENERATED);
            assertThat(credentials.passwordHash().matches(exposed.plaintext())).isFalse();
            assertThat(PosixPermissions.of(path)).isEqualTo("rw-------");
            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("The Peekaboot credentials file " + path
                                + " is accessible to its group or to others; generating a new password");
            });
        }
    }

    @Test
    void resolveReportsAnUnpersistedGenerationWhenTheFileCannotBeWritten(@TempDir Path dir) throws Exception {
        var blocker = dir.resolve("blocker");
        java.nio.file.Files.writeString(blocker, "not a directory");
        var file = new CredentialsFile(blocker.resolve("security.properties"));

        try (LogCapture capture = LogCapture.attach(CredentialsFile.class)) {
            var credentials = new DashboardCredentialsResolver(
                            new PeekabootProperties.Security(), file, "orders-service")
                    .resolve();

            assertThat(credentials.origin()).isEqualTo(DashboardCredentials.Origin.GENERATED_UNPERSISTED);
            assertThat(credentials.plaintext()).isNotNull();
            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("Could not write the Peekaboot credentials file");
            });
        }
    }

    @Test
    void resolveReportsGeneratedUnpersistedWhenThereIsNoCredentialsFile() {
        var credentials = new DashboardCredentialsResolver(
                        new PeekabootProperties.Security(), CredentialsFile.unpersisted(), "orders-service")
                .resolve();

        assertThat(credentials.origin()).isEqualTo(DashboardCredentials.Origin.GENERATED_UNPERSISTED);
        assertThat(credentials.plaintext()).isNotNull();
        assertThat(credentials.passwordHash().matches(credentials.plaintext())).isTrue();
    }

    @Test
    void usernameForSuffixesTheApplicationName() {
        assertThat(DashboardCredentialsResolver.usernameFor("orders-service")).isEqualTo("orders-service-admin");
    }

    @Test
    void usernameForLowerCasesAndReplacesEverythingOutsideTheAllowedSet() {
        assertThat(DashboardCredentialsResolver.usernameFor("Orders Service!")).isEqualTo("orders-service--admin");
        assertThat(DashboardCredentialsResolver.usernameFor("com.acme:orders")).isEqualTo("com.acme-orders-admin");
    }

    @Test
    void usernameForFallsBackWhenThereIsNoApplicationName() {
        assertThat(DashboardCredentialsResolver.usernameFor(null)).isEqualTo("peekaboot-admin");
        assertThat(DashboardCredentialsResolver.usernameFor("   ")).isEqualTo("peekaboot-admin");
    }

    @Test
    void resolvePrefersTheConfiguredUsername(@TempDir Path dir) {
        var security = new PeekabootProperties.Security();
        security.setUsername("ops");

        var credentials = new DashboardCredentialsResolver(
                        security, new CredentialsFile(dir.resolve("security.properties")), "orders-service")
                .resolve();

        assertThat(credentials.username()).isEqualTo("ops");
    }

    @Test
    void generatePasswordDrawsTwentySixCharactersFromTheUnambiguousAlphabet() {
        var password = DashboardCredentialsResolver.generatePassword();

        assertThat(password).matches("[23456789abcdefghijkmnpqrstuvwxyz]{26}");
        assertThat(password).isNotEqualTo(DashboardCredentialsResolver.generatePassword());
    }
}

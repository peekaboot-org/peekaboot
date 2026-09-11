package org.peekaboot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.backend.config.PeekabootProperties;

class DashboardCredentialsResolverTest {

    @Test
    void resolve_prefersTheConfiguredPasswordAndPersistsNothing(@TempDir Path dir) {
        var security = new PeekabootProperties.Security();
        security.setPassword("from-the-vault");
        var file = new CredentialsFile(dir.resolve("security.properties"));

        var credentials = new DashboardCredentialsResolver(security, file, "orders-service").resolve();

        assertThat(credentials.origin()).isEqualTo(DashboardCredentials.Origin.CONFIGURED);
        assertThat(credentials.passwordHash().matches("from-the-vault")).isTrue();
        assertThat(credentials.plaintext()).isNull();
        assertThat(file.read()).isEmpty();
    }

    @Test
    void resolve_generatesAndPersistsWhenNothingIsConfiguredOrStored(@TempDir Path dir) {
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
    void resolve_reusesTheStoredPasswordOnASecondRun(@TempDir Path dir) {
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
    void resolve_reportsAnUnpersistedGenerationWhenTheFileCannotBeWritten(@TempDir Path dir) throws Exception {
        var blocker = dir.resolve("blocker");
        java.nio.file.Files.writeString(blocker, "not a directory");
        var file = new CredentialsFile(blocker.resolve("security.properties"));

        var credentials =
                new DashboardCredentialsResolver(new PeekabootProperties.Security(), file, "orders-service").resolve();

        assertThat(credentials.origin()).isEqualTo(DashboardCredentials.Origin.GENERATED_UNPERSISTED);
        assertThat(credentials.plaintext()).isNotNull();
    }

    @Test
    void usernameFor_suffixesTheApplicationName() {
        assertThat(DashboardCredentialsResolver.usernameFor("orders-service")).isEqualTo("orders-service-admin");
    }

    @Test
    void usernameFor_lowerCasesAndReplacesEverythingOutsideTheAllowedSet() {
        assertThat(DashboardCredentialsResolver.usernameFor("Orders Service!")).isEqualTo("orders-service--admin");
        assertThat(DashboardCredentialsResolver.usernameFor("com.acme:orders")).isEqualTo("com.acme-orders-admin");
    }

    @Test
    void usernameFor_fallsBackWhenThereIsNoApplicationName() {
        assertThat(DashboardCredentialsResolver.usernameFor(null)).isEqualTo("peekaboot-admin");
        assertThat(DashboardCredentialsResolver.usernameFor("   ")).isEqualTo("peekaboot-admin");
    }

    @Test
    void resolve_prefersTheConfiguredUsername(@TempDir Path dir) {
        var security = new PeekabootProperties.Security();
        security.setUsername("ops");

        var credentials = new DashboardCredentialsResolver(
                        security, new CredentialsFile(dir.resolve("security.properties")), "orders-service")
                .resolve();

        assertThat(credentials.username()).isEqualTo("ops");
    }

    @Test
    void generatePassword_drawsTwentySixCharactersFromTheUnambiguousAlphabet() {
        var password = DashboardCredentialsResolver.generatePassword();

        assertThat(password).matches("[23456789abcdefghijkmnpqrstuvwxyz]{26}");
        assertThat(password).isNotEqualTo(DashboardCredentialsResolver.generatePassword());
    }
}

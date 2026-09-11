package org.peekaboot.backend.security;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.config.PeekabootProperties;

/**
 * Where the dashboard's fallback credentials come from: the configured password, else the
 * credentials file, else a fresh generated one.
 *
 * <p>The username is resolved on every run rather than taken from the file, so changing {@code
 * peekaboot.security.username} takes effect without invalidating the stored password.
 */
public class DashboardCredentialsResolver {

    /** No 0/o/1/l: a password that has to survive being read off a terminal and typed back in. */
    private static final String ALPHABET = "23456789abcdefghijkmnpqrstuvwxyz";

    private static final int PASSWORD_LENGTH = 26;
    private static final String USERNAME_SUFFIX = "-admin";
    private static final String UNNAMED_APPLICATION = "peekaboot";
    private static final Pattern DISALLOWED_USERNAME_CHARACTERS = Pattern.compile("[^a-z0-9._-]");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PeekabootProperties.Security security;
    private final CredentialsFile credentialsFile;

    @Nullable
    private final String applicationName;

    public DashboardCredentialsResolver(
            PeekabootProperties.Security security, CredentialsFile credentialsFile, @Nullable String applicationName) {
        this.security = security;
        this.credentialsFile = credentialsFile;
        this.applicationName = applicationName;
    }

    public DashboardCredentials resolve() {
        String username = resolveUsername();

        String configured = security.getPassword();
        if (configured != null && !configured.isBlank()) {
            return new DashboardCredentials(
                    username, PasswordHash.of(configured), null, Instant.now(), DashboardCredentials.Origin.CONFIGURED);
        }

        Optional<CredentialsFile.StoredCredentials> stored = credentialsFile.read();
        if (stored.isPresent()) {
            return new DashboardCredentials(
                    username,
                    stored.get().passwordHash(),
                    null,
                    stored.get().createdAt(),
                    DashboardCredentials.Origin.LOADED);
        }

        String generated = generatePassword();
        DashboardCredentials credentials = new DashboardCredentials(
                username, PasswordHash.of(generated), generated, Instant.now(), DashboardCredentials.Origin.GENERATED);
        return credentialsFile.write(credentials)
                ? credentials
                : new DashboardCredentials(
                        credentials.username(),
                        credentials.passwordHash(),
                        generated,
                        credentials.createdAt(),
                        DashboardCredentials.Origin.GENERATED_UNPERSISTED);
    }

    private String resolveUsername() {
        String configured = security.getUsername();
        return configured != null && !configured.isBlank() ? configured.trim() : usernameFor(applicationName);
    }

    static String usernameFor(@Nullable String applicationName) {
        if (applicationName == null || applicationName.isBlank()) {
            return UNNAMED_APPLICATION + USERNAME_SUFFIX;
        }
        String sanitized = DISALLOWED_USERNAME_CHARACTERS
                .matcher(applicationName.trim().toLowerCase(Locale.ROOT))
                .replaceAll("-");
        return sanitized + USERNAME_SUFFIX;
    }

    static String generatePassword() {
        StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }
}

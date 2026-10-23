package org.peekaboot.backend.security;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.storage.OwnerOnlyFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Only a hash ever reaches disk - the plaintext is printed once and then only lives in memory.
 * Every failure resolves to "no usable file": the caller's answer to all of them is the same,
 * generate a password for this run and say so.
 *
 * <p>Every path, an explicit {@code peekaboot.security.credentials-file} included, is written
 * through {@link OwnerOnlyFiles}: owner-only, replaced in one step, never through a symlink.
 */
public final class CredentialsFile {

    private static final Logger log = LoggerFactory.getLogger(CredentialsFile.class);

    private static final String USERNAME_KEY = "username";
    private static final String PASSWORD_KEY = "password";
    private static final String CREATED_KEY = "created";

    private static final String HEADER = "Peekaboot generated these credentials for its"
            + " /peekaboot/** guard.\nDelete this file for a new password, or set"
            + " peekaboot.security.password.";

    @Nullable
    private final Path path;

    public CredentialsFile(Path path) {
        this.path = path;
    }

    private CredentialsFile() {
        this.path = null;
    }

    /** Nowhere to write: storage is off and nothing overrides it with an explicit path. */
    public static CredentialsFile unpersisted() {
        return new CredentialsFile();
    }

    public Optional<Path> path() {
        return Optional.ofNullable(path);
    }

    Optional<StoredCredentials> read() {
        if (path == null || !Files.isReadable(path)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Could not read the Peekaboot credentials file {}: {}", path, e.getMessage());
            return Optional.empty();
        }
        return parse(properties);
    }

    /** False when nothing was persisted, which the caller reports rather than retries. */
    public boolean write(DashboardCredentials credentials) {
        if (path == null) {
            return false;
        }
        Properties properties = new Properties();
        // never read back, since the username is resolved on every run; it tells a reader whom the file is for
        properties.setProperty(USERNAME_KEY, credentials.username());
        properties.setProperty(PASSWORD_KEY, credentials.passwordHash().format());
        properties.setProperty(CREATED_KEY, credentials.createdAt().toString());
        try {
            OwnerOnlyFiles.replaceAtomically(
                    path, out -> properties.store(new OutputStreamWriter(out, StandardCharsets.UTF_8), HEADER));
            return true;
        } catch (IOException e) {
            log.warn("Could not write the Peekaboot credentials file {}: {}", path, e.getMessage());
            return false;
        }
    }

    private Optional<StoredCredentials> parse(Properties properties) {
        Optional<PasswordHash> hash = PasswordHash.parse(properties.getProperty(PASSWORD_KEY));
        if (hash.isEmpty()) {
            log.warn("The Peekaboot credentials file {} holds no usable entry; generating a new password", path);
            return Optional.empty();
        }
        try {
            Instant created = Instant.parse(properties.getProperty(CREATED_KEY, ""));
            return Optional.of(new StoredCredentials(hash.get(), created));
        } catch (DateTimeParseException e) {
            log.warn("The Peekaboot credentials file {} has no readable creation instant", path);
            return Optional.empty();
        }
    }

    record StoredCredentials(PasswordHash passwordHash, Instant createdAt) {}
}

package org.peekaboot.backend.security;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Only a hash ever reaches disk - the plaintext is printed once and then only lives in memory.
 * Every failure resolves to "no usable file": the caller's answer to all of them is the same,
 * generate a password for this run and say so.
 */
public final class CredentialsFile {

    private static final Logger log = LoggerFactory.getLogger(CredentialsFile.class);

    private static final String USERNAME_KEY = "username";
    private static final String PASSWORD_KEY = "password";
    private static final String CREATED_KEY = "created";

    private static final String HEADER = "Peekaboot generated these credentials because nothing else"
            + " authenticates /peekaboot/**.\nDelete this file for a new password, or set"
            + " peekaboot.security.password.";

    private static final String OWNER_ONLY = "rw-------";

    private final Path path;

    public CredentialsFile(Path path) {
        this.path = path;
    }

    public Path path() {
        return path;
    }

    Optional<StoredCredentials> read() {
        if (!Files.isReadable(path)) {
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
        Properties properties = new Properties();
        properties.setProperty(USERNAME_KEY, credentials.username());
        properties.setProperty(PASSWORD_KEY, credentials.passwordHash().format());
        properties.setProperty(CREATED_KEY, credentials.createdAt().toString());
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            createOwnerOnly();
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, HEADER);
            }
            return true;
        } catch (IOException e) {
            log.warn("Could not write the Peekaboot credentials file {}: {}", path, e.getMessage());
            return false;
        }
    }

    /**
     * Created with the permissions already set rather than set afterwards, so the file is never
     * briefly world-readable. A file system without POSIX permissions gets a plain create.
     */
    private void createOwnerOnly() throws IOException {
        try {
            try {
                Files.createFile(
                        path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY)));
            } catch (UnsupportedOperationException e) {
                Files.createFile(path);
            }
        } catch (FileAlreadyExistsException e) {
            log.debug("Overwriting the existing Peekaboot credentials file {}", path);
            tightenPermissions();
        }
    }

    /**
     * A file that predates this write may hold looser permissions than Peekaboot creates; best
     * effort, since a file system without POSIX permissions must still write the file.
     */
    private void tightenPermissions() {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(OWNER_ONLY));
        } catch (UnsupportedOperationException | IOException e) {
            log.debug("Could not tighten permissions on the Peekaboot credentials file {}: {}", path, e.getMessage());
        }
    }

    private Optional<StoredCredentials> parse(Properties properties) {
        String username = properties.getProperty(USERNAME_KEY);
        Optional<PasswordHash> hash = PasswordHash.parse(properties.getProperty(PASSWORD_KEY));
        if (username == null || username.isBlank() || hash.isEmpty()) {
            log.warn("The Peekaboot credentials file {} holds no usable entry; generating a new password", path);
            return Optional.empty();
        }
        try {
            Instant created = Instant.parse(properties.getProperty(CREATED_KEY, ""));
            return Optional.of(new StoredCredentials(username, hash.get(), created));
        } catch (DateTimeParseException e) {
            log.warn("The Peekaboot credentials file {} has no readable creation instant", path);
            return Optional.empty();
        }
    }

    record StoredCredentials(String username, PasswordHash passwordHash, Instant createdAt) {}
}

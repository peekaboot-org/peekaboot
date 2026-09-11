package org.peekaboot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.testsupport.LogCapture;

class CredentialsFileTest {

    private static final Instant CREATED = Instant.parse("2026-09-11T08:15:30Z");

    @Test
    void write_thenRead_roundTripsTheCredentials(@TempDir Path dir) {
        var file = new CredentialsFile(dir.resolve("security.properties"));
        var hash = PasswordHash.of("s3cret");

        assertThat(file.write(credentials("orders-admin", hash))).isTrue();

        var stored = file.read();
        assertThat(stored).isPresent();
        assertThat(stored.get().username()).isEqualTo("orders-admin");
        assertThat(stored.get().createdAt()).isEqualTo(CREATED);
        assertThat(stored.get().passwordHash().matches("s3cret")).isTrue();
    }

    @Test
    void write_overwritesAnExistingFile(@TempDir Path dir) {
        var file = new CredentialsFile(dir.resolve("security.properties"));
        file.write(credentials("orders-admin", PasswordHash.of("s3cret")));
        var replacement = PasswordHash.of("n3wpass");

        assertThat(file.write(credentials("new-admin", replacement))).isTrue();

        var stored = file.read();
        assertThat(stored).isPresent();
        assertThat(stored.get().username()).isEqualTo("new-admin");
        assertThat(stored.get().passwordHash().matches("n3wpass")).isTrue();
    }

    @Test
    void write_createsMissingParentDirectories(@TempDir Path dir) {
        var file = new CredentialsFile(dir.resolve("a/b/security.properties"));

        assertThat(file.write(credentials("orders-admin", PasswordHash.of("s3cret"))))
                .isTrue();
        assertThat(file.read()).isPresent();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void write_leavesTheFileReadableOnlyByItsOwner(@TempDir Path dir) throws IOException {
        var path = dir.resolve("security.properties");

        new CredentialsFile(path).write(credentials("orders-admin", PasswordHash.of("s3cret")));

        assertThat(PosixFilePermissions.toString(Files.getFileAttributeView(path, PosixFileAttributeView.class)
                        .readAttributes()
                        .permissions()))
                .isEqualTo("rw-------");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void write_tightensThePermissionsOfAPreExistingFile(@TempDir Path dir) throws IOException {
        var path = dir.resolve("security.properties");
        Files.createFile(path);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));

        new CredentialsFile(path).write(credentials("orders-admin", PasswordHash.of("s3cret")));

        assertThat(PosixFilePermissions.toString(Files.getFileAttributeView(path, PosixFileAttributeView.class)
                        .readAttributes()
                        .permissions()))
                .isEqualTo("rw-------");
    }

    @Test
    void read_isEmptyWhenTheFileIsAbsent(@TempDir Path dir) {
        assertThat(new CredentialsFile(dir.resolve("security.properties")).read())
                .isEmpty();
    }

    @Test
    void read_isEmptyWhenTheEntryIsUnparseable(@TempDir Path dir) throws IOException {
        var path = dir.resolve("security.properties");
        Files.writeString(path, "username=orders-admin\npassword=not-a-hash\ncreated=2026-09-11T08:15:30Z\n");

        assertThat(new CredentialsFile(path).read()).isEmpty();
    }

    @Test
    void read_isEmptyWhenTheCreationInstantIsUnparseable(@TempDir Path dir) throws IOException {
        var path = dir.resolve("security.properties");
        Files.writeString(
                path,
                "username=orders-admin\npassword=" + PasswordHash.of("s3cret").format() + "\ncreated=never\n");

        assertThat(new CredentialsFile(path).read()).isEmpty();
    }

    @Test
    void write_reportsFailureWhenTheDirectoryCannotBeCreated(@TempDir Path dir) throws IOException {
        var blocker = dir.resolve("blocker");
        Files.writeString(blocker, "not a directory");

        var file = new CredentialsFile(blocker.resolve("security.properties"));

        assertThat(file.write(credentials("orders-admin", PasswordHash.of("s3cret"))))
                .isFalse();
        assertThat(file.read()).isEmpty();
    }

    @Test
    void unpersisted_hasNoPath() {
        assertThat(CredentialsFile.unpersisted().path()).isEmpty();
    }

    @Test
    void unpersisted_readIsEmpty() {
        assertThat(CredentialsFile.unpersisted().read()).isEmpty();
    }

    @Test
    void unpersisted_writeReturnsFalse() {
        assertThat(CredentialsFile.unpersisted().write(credentials("orders-admin", PasswordHash.of("s3cret"))))
                .isFalse();
    }

    @Test
    void unpersisted_writeLogsNoWarning() {
        try (LogCapture capture = LogCapture.attach(CredentialsFile.class)) {
            CredentialsFile.unpersisted().write(credentials("orders-admin", PasswordHash.of("s3cret")));

            assertThat(capture.appender().list).isEmpty();
        }
    }

    private static DashboardCredentials credentials(String username, PasswordHash hash) {
        return new DashboardCredentials(username, hash, "s3cret", CREATED, DashboardCredentials.Origin.GENERATED);
    }
}

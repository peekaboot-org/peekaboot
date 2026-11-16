package org.peekaboot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.testsupport.Credentials;
import org.peekaboot.backend.testsupport.PosixPermissions;
import org.peekaboot.testsupport.LogCapture;

class CredentialsFileTest {

    @Test
    void writeThenReadRoundTripsTheCredentials(@TempDir Path dir) {
        var file = new CredentialsFile(dir.resolve("security.properties"));

        assertThat(file.write(Credentials.generated("s3cret"))).isTrue();

        var stored = file.read().orElseThrow();
        assertThat(stored.createdAt()).isEqualTo(Credentials.CREATED);
        assertThat(stored.passwordHash().matches("s3cret")).isTrue();
    }

    @Test
    void writeOverwritesAnExistingFile(@TempDir Path dir) {
        var file = new CredentialsFile(dir.resolve("security.properties"));
        file.write(Credentials.generated("s3cret"));

        assertThat(file.write(Credentials.generated("n3wpass"))).isTrue();

        var stored = file.read().orElseThrow();
        assertThat(stored.passwordHash().matches("n3wpass")).isTrue();
    }

    @Test
    void writeCreatesMissingParentDirectories(@TempDir Path dir) {
        var file = new CredentialsFile(dir.resolve("a/b/security.properties"));

        assertThat(file.write(Credentials.generated("s3cret"))).isTrue();
        assertThat(file.read()).isPresent();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void writeLeavesTheFileReadableOnlyByItsOwner(@TempDir Path dir) throws IOException {
        var path = dir.resolve("security.properties");

        new CredentialsFile(path).write(Credentials.generated("s3cret"));

        assertThat(PosixFilePermissions.toString(Files.getFileAttributeView(path, PosixFileAttributeView.class)
                        .readAttributes()
                        .permissions()))
                .isEqualTo("rw-------");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void writeTightensThePermissionsOfAPreExistingFile(@TempDir Path dir) throws IOException {
        var path = dir.resolve("security.properties");
        Files.createFile(path);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));

        new CredentialsFile(path).write(Credentials.generated("s3cret"));

        assertThat(PosixFilePermissions.toString(Files.getFileAttributeView(path, PosixFileAttributeView.class)
                        .readAttributes()
                        .permissions()))
                .isEqualTo("rw-------");
    }

    /** The default path sits in the storage directory; an explicit one may sit anywhere, so neither may be written through. */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void writeNeverWritesThroughASymlinkPlantedAtThePath(@TempDir Path dir) throws IOException {
        var victim = dir.resolve("victim");
        Files.writeString(victim, "untouched");
        Files.setPosixFilePermissions(victim, PosixFilePermissions.fromString("rw-r--r--"));
        var path = dir.resolve("security.properties");
        Files.createSymbolicLink(path, victim);

        assertThat(new CredentialsFile(path).write(Credentials.generated("s3cret")))
                .isTrue();

        assertThat(Files.readString(victim)).isEqualTo("untouched");
        assertThat(PosixPermissions.of(victim)).isEqualTo("rw-r--r--");
        assertThat(Files.isSymbolicLink(path)).isFalse();
        assertThat(new CredentialsFile(path).read()).isPresent();
    }

    /** A second name for the file must keep the old content: the write replaces the file rather than writing into it. */
    @Test
    void writeReplacesTheFileRatherThanWritingIntoIt(@TempDir Path dir) throws IOException {
        var path = dir.resolve("security.properties");
        Files.writeString(path, "previous");
        var otherName = Files.createLink(dir.resolve("other-name"), path);

        assertThat(new CredentialsFile(path).write(Credentials.generated("s3cret")))
                .isTrue();

        assertThat(Files.readString(otherName)).isEqualTo("previous");
        assertThat(new CredentialsFile(path).read()).isPresent();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void writeCreatesMissingParentDirectoriesReadableOnlyByTheOwner(@TempDir Path dir) throws IOException {
        var path = dir.resolve("a/b/security.properties");

        new CredentialsFile(path).write(Credentials.generated("s3cret"));

        assertThat(PosixPermissions.of(dir.resolve("a"))).isEqualTo("rwx------");
        assertThat(PosixPermissions.of(dir.resolve("a/b"))).isEqualTo("rwx------");
    }

    @Test
    void writeLeavesNoTemporaryBehind(@TempDir Path dir) throws IOException {
        new CredentialsFile(dir.resolve("security.properties")).write(Credentials.generated("s3cret"));

        try (var entries = Files.list(dir)) {
            assertThat(entries).containsExactly(dir.resolve("security.properties"));
        }
    }

    /** The username is resolved on every run, so the file records it only for a human reading it. */
    @Test
    void writeRecordsWhomTheCredentialsAreFor(@TempDir Path dir) throws IOException {
        var path = dir.resolve("security.properties");

        new CredentialsFile(path).write(Credentials.generated("s3cret"));

        assertThat(Files.readString(path)).contains("username=orders-admin");
    }

    /** Nothing reads the stored username, so a file without one still keeps its password. */
    @ParameterizedTest
    @ValueSource(strings = {"", "username=\n", "username=  \n"})
    void readKeepsThePasswordOfAFileWithoutAUsername(String usernameLine, @TempDir Path dir) throws IOException {
        var path = dir.resolve("security.properties");
        writeOwnerOnly(
                path,
                usernameLine + "password=" + PasswordHash.of("s3cret").format() + "\ncreated=2026-09-11T08:15:30Z\n");

        var stored = new CredentialsFile(path).read();

        assertThat(stored)
                .hasValueSatisfying(credentials ->
                        assertThat(credentials.passwordHash().matches("s3cret")).isTrue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"rw-r-----", "rw----r--", "rw--w----", "rw-----w-", "rw---x---", "rw------x"})
    void readRefusesAFileItsGroupOrOthersCanAccess(String mode, @TempDir Path dir) throws IOException {
        PosixPermissions.assumeSupported();
        var path = dir.resolve("security.properties");
        var file = new CredentialsFile(path);
        file.write(Credentials.generated("s3cret"));
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));

        try (LogCapture capture = LogCapture.attach(CredentialsFile.class)) {
            assertThat(file.read()).isEmpty();

            assertSingleWarning(capture, path + " is accessible to its group or to others; generating a new password");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rw-------", "r--------"})
    void readUsesAnOwnerOnlyFile(String mode, @TempDir Path dir) throws IOException {
        PosixPermissions.assumeSupported();
        var path = dir.resolve("security.properties");
        var file = new CredentialsFile(path);
        file.write(Credentials.generated("s3cret"));
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));

        try (LogCapture capture = LogCapture.attach(CredentialsFile.class)) {
            assertThat(file.read())
                    .hasValueSatisfying(
                            credentials -> assertThat(credentials.passwordHash().matches("s3cret"))
                                    .isTrue());

            assertThat(capture.appender().list).isEmpty();
        }
    }

    @Test
    void readIsEmptyWhenTheFileIsAbsent(@TempDir Path dir) {
        assertThat(new CredentialsFile(dir.resolve("security.properties")).read())
                .isEmpty();
    }

    @Test
    void readIsEmptyWhenTheEntryIsUnparseable(@TempDir Path dir) throws IOException {
        var path = dir.resolve("security.properties");
        writeOwnerOnly(path, "username=orders-admin\npassword=not-a-hash\ncreated=2026-09-11T08:15:30Z\n");

        try (LogCapture capture = LogCapture.attach(CredentialsFile.class)) {
            assertThat(new CredentialsFile(path).read()).isEmpty();

            assertSingleWarning(capture, "holds no usable entry; generating a new password");
        }
    }

    @Test
    void readIsEmptyWhenTheCreationInstantIsUnparseable(@TempDir Path dir) throws IOException {
        var path = dir.resolve("security.properties");
        writeOwnerOnly(
                path,
                "username=orders-admin\npassword=" + PasswordHash.of("s3cret").format() + "\ncreated=never\n");

        try (LogCapture capture = LogCapture.attach(CredentialsFile.class)) {
            assertThat(new CredentialsFile(path).read()).isEmpty();

            assertSingleWarning(capture, "has no readable creation instant");
        }
    }

    @Test
    void writeReportsFailureWhenTheDirectoryCannotBeCreated(@TempDir Path dir) throws IOException {
        var blocker = dir.resolve("blocker");
        Files.writeString(blocker, "not a directory");

        var file = new CredentialsFile(blocker.resolve("security.properties"));

        try (LogCapture capture = LogCapture.attach(CredentialsFile.class)) {
            assertThat(file.write(Credentials.generated("s3cret"))).isFalse();
            assertThat(file.read()).isEmpty();

            assertSingleWarning(capture, "Could not write the Peekaboot credentials file");
        }
    }

    @Test
    void unpersistedHasNoPath() {
        assertThat(CredentialsFile.unpersisted().path()).isEmpty();
    }

    @Test
    void anUnpersistedReadIsEmpty() {
        assertThat(CredentialsFile.unpersisted().read()).isEmpty();
    }

    @Test
    void anUnpersistedWriteReturnsFalse() {
        assertThat(CredentialsFile.unpersisted().write(Credentials.generated("s3cret")))
                .isFalse();
    }

    @Test
    void anUnpersistedWriteLogsNoWarning() {
        try (LogCapture capture = LogCapture.attach(CredentialsFile.class)) {
            CredentialsFile.unpersisted().write(Credentials.generated("s3cret"));

            assertThat(capture.appender().list).isEmpty();
        }
    }

    /** A file written by hand takes the umask's mode, which {@code read} refuses where it leaves a group or others bit. */
    private static void writeOwnerOnly(Path path, String content) throws IOException {
        Files.writeString(path, content);
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
        }
    }

    private static void assertSingleWarning(LogCapture capture, String messageFragment) {
        assertThat(capture.appender().list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(messageFragment);
        });
    }
}

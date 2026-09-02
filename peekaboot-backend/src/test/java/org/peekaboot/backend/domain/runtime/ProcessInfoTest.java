package org.peekaboot.backend.domain.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessInfoTest {

    @Test
    void current_isComputedOnceAndCached() {
        // process identity is static for the JVM's lifetime; current() is called on
        // every insights request and must not re-read /proc or walk the parent chain
        assertThat(ProcessInfo.current()).isSameAs(ProcessInfo.current());
    }

    @Test
    void current_shouldReturnUsername() {
        ProcessInfo info = ProcessInfo.current();
        assertThat(info.username()).isEqualTo(System.getProperty("user.name"));
    }

    @Test
    void current_shouldReturnPid() {
        ProcessInfo info = ProcessInfo.current();
        assertThat(info.pid()).isEqualTo(ProcessHandle.current().pid());
    }

    /**
     * The credentials the process runs under - not the owner of some file: a container
     * whose root-owned WORKDIR runs as {@code USER 1000} must report uid 1000, not 0.
     * {@code id} is the oracle; forking it is fine here, never in current() itself.
     * Where {@code /proc} is absent (anything but Linux), both ids are simply unknown.
     */
    @Test
    void current_shouldReturnTheProcessOwnUidAndGid() throws Exception {
        ProcessInfo info = ProcessInfo.current();
        if (Files.exists(Path.of("/proc/self/status"))) {
            assertThat(info.uid()).isEqualTo(id("-ru"));
            assertThat(info.gid()).isEqualTo(id("-rg"));
        } else {
            assertThat(info.uid()).isNull();
            assertThat(info.gid()).isNull();
        }
    }

    @Test
    void procStatusId_takesTheRealIdFromTheFourOnTheLine(@TempDir Path dir) throws IOException {
        // the kernel lists real, effective, saved and filesystem ids, tab-separated
        Path status = Files.writeString(
                dir.resolve("status"), "Name:\tjava\nUid:\t1000\t1001\t1002\t1003\nGid:\t100\t101\t102\t103\n");

        assertThat(ProcessInfo.procStatusId(status, "Uid")).isEqualTo("1000");
        assertThat(ProcessInfo.procStatusId(status, "Gid")).isEqualTo("100");
    }

    @Test
    void procStatusId_isNullWithoutTheLine(@TempDir Path dir) throws IOException {
        Path status = Files.writeString(dir.resolve("status"), "Name:\tjava\nUid:\t1000\t1000\t1000\t1000\n");

        assertThat(ProcessInfo.procStatusId(status, "Gid")).isNull();
    }

    @Test
    void procStatusId_isNullWhenTheFileIsMissing(@TempDir Path dir) {
        assertThat(ProcessInfo.procStatusId(dir.resolve("status"), "Uid")).isNull();
    }

    @Test
    void current_shouldReturnNonEmptyParentProcessTree() {
        ProcessInfo info = ProcessInfo.current();
        // every process except PID 0/1 has at least one parent
        assertThat(info.parentProcesses()).isNotEmpty();
    }

    @Test
    void current_parentProcessesShouldHavePidAndCommand() {
        ProcessInfo info = ProcessInfo.current();
        for (ProcessInfo.ParentProcess parent : info.parentProcesses()) {
            assertThat(parent.pid()).isGreaterThan(0);
            // command may be empty for system processes but should never be null
            assertThat(parent.command()).isNotNull();
        }
    }

    @Test
    void current_firstParentShouldBeDirectParent() {
        ProcessInfo info = ProcessInfo.current();
        long expectedParentPid =
                ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(-1L);
        if (expectedParentPid > 0) {
            assertThat(info.parentProcesses().get(0).pid()).isEqualTo(expectedParentPid);
        }
    }

    private static String id(String option) throws IOException, InterruptedException {
        Process id = new ProcessBuilder("id", option).start();
        String output = new String(id.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(id.waitFor()).as("id %s exits cleanly", option).isZero();
        return output;
    }
}

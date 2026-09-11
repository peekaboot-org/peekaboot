package org.peekaboot.backend.domain.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.backend.domain.runtime.ProcessInfo.ParentProcess;
import org.peekaboot.backend.domain.runtime.ProcessInfo.Signals;

class ProcessInfoTest {

    /** A process handle with the three facts the chain walk reads; {@code parent} null means the root of the tree. */
    private static ProcessHandle process(long pid, String command, ProcessHandle parent) {
        ProcessHandle.Info info = mock(ProcessHandle.Info.class);
        when(info.command()).thenReturn(Optional.ofNullable(command));
        ProcessHandle handle = mock(ProcessHandle.class);
        when(handle.pid()).thenReturn(pid);
        when(handle.info()).thenReturn(info);
        when(handle.parent()).thenReturn(Optional.ofNullable(parent));
        return handle;
    }

    private static Path status(Path dir, String content) throws IOException {
        return Files.writeString(dir.resolve("status"), content);
    }

    @Test
    void currentIsComputedOnceAndCached() {
        // process identity is static for the JVM's lifetime; current() is called on
        // every insights request and must not re-read /proc or walk the parent chain
        assertThat(ProcessInfo.current()).isSameAs(ProcessInfo.current());
    }

    /**
     * The ids are the credentials the process runs under, read off its own status file: a
     * container whose root-owned WORKDIR runs as {@code USER 1000} reports uid 1000, not 0.
     */
    @Test
    void theIdentityIsTheUsernamePidAndTheStatusFilesRealIds(@TempDir Path dir) throws IOException {
        Path status = status(dir, "Name:\tjava\nUid:\t1000\t1001\t1002\t1003\nGid:\t100\t101\t102\t103\n");

        ProcessInfo info = ProcessInfo.read(new Signals("svc", process(4242, "/usr/bin/java", null), status));

        assertThat(info.username()).isEqualTo("svc");
        assertThat(info.pid()).isEqualTo(4242);
        assertThat(info.uid()).isEqualTo("1000");
        assertThat(info.gid()).isEqualTo("100");
    }

    @Test
    void theParentChainIsWalkedToTheRootWithBareCommandNames(@TempDir Path dir) throws IOException {
        ProcessHandle init = process(1, "/sbin/init", null);
        ProcessHandle shell = process(100, "/usr/bin/bash", init);
        ProcessHandle self = process(4242, "/usr/bin/java", shell);

        ProcessInfo info = ProcessInfo.read(new Signals("svc", self, dir.resolve("status")));

        assertThat(info.parentProcesses())
                .containsExactly(new ParentProcess(100, "bash"), new ParentProcess(1, "init"));
    }

    @Test
    void aWindowsCommandPathIsCutAtItsLastBackslash(@TempDir Path dir) {
        ProcessHandle explorer = process(700, "C:\\Windows\\explorer.exe", null);
        ProcessHandle self = process(4242, "C:\\java\\bin\\java.exe", explorer);

        ProcessInfo info = ProcessInfo.read(new Signals("svc", self, dir.resolve("status")));

        assertThat(info.parentProcesses()).containsExactly(new ParentProcess(700, "explorer.exe"));
    }

    /** The OS hides some processes' commands (another user's, a kernel thread); the row keeps its pid. */
    @Test
    void aParentWhoseCommandIsHiddenIsListedWithAnEmptyName(@TempDir Path dir) {
        ProcessHandle hidden = process(2, null, null);
        ProcessHandle self = process(4242, "java", hidden);

        ProcessInfo info = ProcessInfo.read(new Signals("svc", self, dir.resolve("status")));

        assertThat(info.parentProcesses()).containsExactly(new ParentProcess(2, ""));
    }

    /** PID 1 in a container has no parent; the chain is empty rather than an error. */
    @Test
    void aProcessWithoutAParentHasAnEmptyChain(@TempDir Path dir) {
        ProcessInfo info = ProcessInfo.read(new Signals("svc", process(1, "java", null), dir.resolve("status")));

        assertThat(info.parentProcesses()).isEmpty();
    }

    @Test
    void procStatusIdTakesTheRealIdFromTheFourOnTheLine(@TempDir Path dir) throws IOException {
        // the kernel lists real, effective, saved and filesystem ids, tab-separated
        Path status = status(dir, "Name:\tjava\nUid:\t1000\t1001\t1002\t1003\nGid:\t100\t101\t102\t103\n");

        assertThat(ProcessInfo.procStatusId(status, "Uid")).isEqualTo("1000");
        assertThat(ProcessInfo.procStatusId(status, "Gid")).isEqualTo("100");
    }

    @Test
    void procStatusIdIsNullWithoutTheLine(@TempDir Path dir) throws IOException {
        Path status = status(dir, "Name:\tjava\nUid:\t1000\t1000\t1000\t1000\n");

        assertThat(ProcessInfo.procStatusId(status, "Gid")).isNull();
    }

    @Test
    void procStatusIdIsNullWhenTheLineCarriesNoId(@TempDir Path dir) throws IOException {
        Path status = status(dir, "Name:\tjava\nUid:\n");

        assertThat(ProcessInfo.procStatusId(status, "Uid")).isNull();
    }

    /** Anything but Linux has no status file; both ids are simply unknown. */
    @Test
    void procStatusIdIsNullWhenTheFileIsMissing(@TempDir Path dir) {
        assertThat(ProcessInfo.procStatusId(dir.resolve("status"), "Uid")).isNull();
    }
}

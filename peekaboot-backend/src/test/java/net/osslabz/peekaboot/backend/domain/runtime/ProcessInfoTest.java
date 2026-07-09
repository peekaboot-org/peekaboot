package net.osslabz.peekaboot.backend.domain.runtime;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessInfoTest {

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

    @Test
    void current_shouldReturnUidAndGid() {
        ProcessInfo info = ProcessInfo.current();
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            assertThat(info.uid()).isNull();
            assertThat(info.gid()).isNull();
        } else {
            assertThat(info.uid()).isNotNull();
            assertThat(info.gid()).isNotNull();
        }
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
        long expectedParentPid = ProcessHandle.current().parent()
            .map(ProcessHandle::pid)
            .orElse(-1L);
        if (expectedParentPid > 0) {
            assertThat(info.parentProcesses().get(0).pid()).isEqualTo(expectedParentPid);
        }
    }

    @Test
    void of_shouldCreateWithExplicitValues() {
        List<ProcessInfo.ParentProcess> parents = List.of(
            new ProcessInfo.ParentProcess(100L, "bash"),
            new ProcessInfo.ParentProcess(1L, "init")
        );
        ProcessInfo info = ProcessInfo.of("testuser", "1000", "1000", 42L, parents);
        assertThat(info.username()).isEqualTo("testuser");
        assertThat(info.uid()).isEqualTo("1000");
        assertThat(info.gid()).isEqualTo("1000");
        assertThat(info.pid()).isEqualTo(42L);
        assertThat(info.parentProcesses()).hasSize(2);
        assertThat(info.parentProcesses().get(0).command()).isEqualTo("bash");
    }
}

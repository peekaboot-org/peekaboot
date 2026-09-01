package org.peekaboot.backend.domain.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.domain.runtime.ContainerRuntime.Signals;

class ContainerRuntimeTest {

    @Test
    void detectsKubernetesFromServiceHostEvenAlongsideADockerMarker(@TempDir Path dir) throws Exception {
        // a pod's container also carries the container runtime's own marker file
        Path dockerEnv = Files.createFile(dir.resolve(".dockerenv"));
        Signals signals = new Signals(
                dockerEnv,
                dir.resolve(".containerenv"),
                Map.of("KUBERNETES_SERVICE_HOST", "10.0.0.1"),
                dir.resolve("cgroup"));

        assertThat(ContainerRuntime.detect(signals)).isEqualTo(ContainerRuntime.KUBERNETES);
    }

    @Test
    void detectsDockerFromItsMarkerFile(@TempDir Path dir) throws Exception {
        Path dockerEnv = Files.createFile(dir.resolve(".dockerenv"));
        Signals signals = new Signals(dockerEnv, dir.resolve(".containerenv"), Map.of(), dir.resolve("cgroup"));

        assertThat(ContainerRuntime.detect(signals)).isEqualTo(ContainerRuntime.DOCKER);
    }

    @Test
    void detectsPodmanFromItsMarkerFile(@TempDir Path dir) throws Exception {
        Path containerEnv = Files.createFile(dir.resolve(".containerenv"));
        Signals signals = new Signals(dir.resolve(".dockerenv"), containerEnv, Map.of(), dir.resolve("cgroup"));

        assertThat(ContainerRuntime.detect(signals)).isEqualTo(ContainerRuntime.PODMAN);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0::/docker/3f4c2a\n",
                "12:memory:/kubepods/burstable/pod1/abc\n",
                "0::/system.slice/containerd.service\n"
            })
    void detectsAGenericContainerFromCgroupMarkers(String cgroup, @TempDir Path dir) throws Exception {
        Path cgroupFile = Files.writeString(dir.resolve("cgroup"), cgroup);
        Signals signals = new Signals(dir.resolve(".dockerenv"), dir.resolve(".containerenv"), Map.of(), cgroupFile);

        assertThat(ContainerRuntime.detect(signals)).isEqualTo(ContainerRuntime.CONTAINER);
    }

    @Test
    void detectsNoneOnAPlainHost(@TempDir Path dir) throws Exception {
        Path cgroupFile = Files.writeString(dir.resolve("cgroup"), "0::/user.slice/user-1000.slice/session-2.scope\n");
        Signals signals = new Signals(dir.resolve(".dockerenv"), dir.resolve(".containerenv"), Map.of(), cgroupFile);

        assertThat(ContainerRuntime.detect(signals)).isEqualTo(ContainerRuntime.NONE);
    }

    @Test
    void detectsNoneWhenTheCgroupFileIsMissing(@TempDir Path dir) {
        Signals signals =
                new Signals(dir.resolve(".dockerenv"), dir.resolve(".containerenv"), Map.of(), dir.resolve("cgroup"));

        assertThat(ContainerRuntime.detect(signals)).isEqualTo(ContainerRuntime.NONE);
    }

    @Test
    void detectsNoneWhenTheCgroupFileIsUnreadable(@TempDir Path dir) throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path cgroupFile = Files.writeString(dir.resolve("cgroup"), "0::/docker/3f4c2a\n");
        Files.setPosixFilePermissions(cgroupFile, Set.<PosixFilePermission>of());
        Signals signals = new Signals(dir.resolve(".dockerenv"), dir.resolve(".containerenv"), Map.of(), cgroupFile);

        assertThat(ContainerRuntime.detect(signals)).isEqualTo(ContainerRuntime.NONE);
    }

    @Test
    void current_reportsARuntimeForThisProcess() {
        assertThat(ContainerRuntime.current()).isNotNull();
    }

    @Test
    void wireNameIsTheLowercaseWordTheFrontendRenders() {
        assertThat(ContainerRuntime.KUBERNETES.wireName()).isEqualTo("kubernetes");
        assertThat(ContainerRuntime.NONE.wireName()).isEqualTo("none");
    }
}

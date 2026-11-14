package org.peekaboot.backend.domain.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.domain.runtime.ContainerRuntime.Signals;
import org.peekaboot.backend.testsupport.PosixPermissions;

class ContainerRuntimeTest {

    /**
     * {@code /proc/1/mountinfo} of a Docker 29 container on a cgroup v2 host, some lines left
     * out and the container id shortened.
     */
    private static final String CONTAINER_MOUNT_INFO = """
            673 637 0:62 / / rw,relatime - overlay overlay rw,\
            lowerdir=/var/lib/containerd/io.containerd.snapshotter.v1.overlayfs/snapshots/108988/fs:\
            /var/lib/containerd/io.containerd.snapshotter.v1.overlayfs/snapshots/108987/fs,\
            upperdir=/var/lib/containerd/io.containerd.snapshotter.v1.overlayfs/snapshots/108989/fs,\
            workdir=/var/lib/containerd/io.containerd.snapshotter.v1.overlayfs/snapshots/108989/work
            675 673 0:101 / /proc rw,nosuid,nodev,noexec,relatime - proc proc rw
            679 678 0:28 / /sys/fs/cgroup ro,nosuid,nodev,noexec,relatime - cgroup2 cgroup rw,nsdelegate,memory_recursiveprot
            682 673 8:1 /var/lib/docker/containers/4b36c071eae0/hostname /etc/hostname rw,relatime - ext4 /dev/sda1 rw,errors=remount-ro
            683 673 8:1 /var/lib/docker/containers/4b36c071eae0/hosts /etc/hosts rw,relatime - ext4 /dev/sda1 rw,errors=remount-ro
            684 673 8:1 /var/lib/docker/containers/4b36c071eae0/resolv.conf /etc/resolv.conf rw,relatime - ext4 /dev/sda1 rw,errors=remount-ro
            638 675 0:101 /bus /proc/bus ro,nosuid,nodev,noexec,relatime - proc proc rw
            """;

    /**
     * {@code /proc/1/mountinfo} of the host those containers run on, some lines left out,
     * the container ids shortened and each overlay's lower layers cut to the first.
     */
    private static final String CONTAINER_HOST_MOUNT_INFO = """
            25 30 0:23 / /proc rw,nosuid,nodev,noexec,relatime shared:11 - proc proc rw
            28 30 0:25 / /run rw,nosuid,nodev,noexec,relatime shared:5 - tmpfs tmpfs rw,size=3209852k,mode=755,inode64
            30 1 8:1 / / rw,relatime shared:1 - ext4 /dev/sda1 rw,errors=remount-ro
            33 24 0:28 / /sys/fs/cgroup rw,nosuid,nodev,noexec,relatime shared:8 - cgroup2 cgroup2 rw,nsdelegate,memory_recursiveprot
            325 28 0:4 net:[4026531840] /run/docker/netns/default rw shared:262 - nsfs nsfs rw
            312 30 0:49 / /var/lib/docker/rootfs/overlayfs/fdfcf73bd72b rw,relatime shared:259 - overlay overlay rw,\
            lowerdir=/var/lib/containerd/io.containerd.snapshotter.v1.overlayfs/snapshots/43309/fs,\
            upperdir=/var/lib/containerd/io.containerd.snapshotter.v1.overlayfs/snapshots/43310/fs,\
            workdir=/var/lib/containerd/io.containerd.snapshotter.v1.overlayfs/snapshots/43310/work
            326 28 0:4 net:[4026532319] /run/docker/netns/2d668455b362 rw shared:355 - nsfs nsfs rw
            467 30 0:53 / /var/lib/docker/rootfs/overlayfs/817b12cf268f rw,relatime shared:273 - overlay overlay rw,\
            lowerdir=/var/lib/containerd/io.containerd.snapshotter.v1.overlayfs/snapshots/19837/fs,\
            upperdir=/var/lib/containerd/io.containerd.snapshotter.v1.overlayfs/snapshots/19839/fs,\
            workdir=/var/lib/containerd/io.containerd.snapshotter.v1.overlayfs/snapshots/19839/work
            """;

    @Test
    void detectsKubernetesFromServiceHostEvenAlongsideADockerMarker(@TempDir Path dir) throws Exception {
        // a pod's container also carries the container runtime's own marker file
        Path dockerEnv = Files.createFile(dir.resolve(".dockerenv"));
        Signals signals = new Signals(
                dockerEnv,
                dir.resolve(".containerenv"),
                Map.of("KUBERNETES_SERVICE_HOST", "10.0.0.1"),
                dir.resolve("cgroup"),
                dir.resolve("mountinfo"));

        assertThat(ContainerRuntime.detect(signals)).isEqualTo(ContainerRuntime.KUBERNETES);
    }

    @Test
    void detectsDockerFromItsMarkerFile(@TempDir Path dir) throws Exception {
        Path dockerEnv = Files.createFile(dir.resolve(".dockerenv"));
        Signals signals = new Signals(
                dockerEnv, dir.resolve(".containerenv"), Map.of(), dir.resolve("cgroup"), dir.resolve("mountinfo"));

        assertThat(ContainerRuntime.detect(signals)).isEqualTo(ContainerRuntime.DOCKER);
    }

    @Test
    void detectsPodmanFromItsMarkerFile(@TempDir Path dir) throws Exception {
        Path containerEnv = Files.createFile(dir.resolve(".containerenv"));
        Signals signals = new Signals(
                dir.resolve(".dockerenv"), containerEnv, Map.of(), dir.resolve("cgroup"), dir.resolve("mountinfo"));

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
        Signals signals = new Signals(
                dir.resolve(".dockerenv"),
                dir.resolve(".containerenv"),
                Map.of(),
                cgroupFile,
                dir.resolve("mountinfo"));

        assertThat(ContainerRuntime.detect(signals)).isEqualTo(ContainerRuntime.CONTAINER);
    }

    @Test
    void detectsNoneOnAPlainHost(@TempDir Path dir) throws Exception {
        Path cgroupFile = Files.writeString(dir.resolve("cgroup"), "0::/user.slice/user-1000.slice/session-2.scope\n");
        Signals signals = new Signals(
                dir.resolve(".dockerenv"),
                dir.resolve(".containerenv"),
                Map.of(),
                cgroupFile,
                dir.resolve("mountinfo"));

        assertThat(ContainerRuntime.detect(signals)).isEqualTo(ContainerRuntime.NONE);
    }

    @Test
    void detectsNoneWhenTheCgroupFileIsMissing(@TempDir Path dir) {
        Signals signals = new Signals(
                dir.resolve(".dockerenv"),
                dir.resolve(".containerenv"),
                Map.of(),
                dir.resolve("cgroup"),
                dir.resolve("mountinfo"));

        assertThat(ContainerRuntime.detect(signals)).isEqualTo(ContainerRuntime.NONE);
    }

    @Test
    void detectsNoneWhenTheCgroupFileIsUnreadable(@TempDir Path dir) throws Exception {
        PosixPermissions.assumeSupported();
        Path cgroupFile = Files.writeString(dir.resolve("cgroup"), "0::/docker/3f4c2a\n");
        Files.setPosixFilePermissions(cgroupFile, Set.<PosixFilePermission>of());
        assumeFalse(Files.isReadable(cgroupFile), "root reads a file whatever its mode says");
        Signals signals = new Signals(
                dir.resolve(".dockerenv"),
                dir.resolve(".containerenv"),
                Map.of(),
                cgroupFile,
                dir.resolve("mountinfo"));

        assertThat(ContainerRuntime.detect(signals)).isEqualTo(ContainerRuntime.NONE);
    }

    @Test
    void detectsAGenericContainerFromTheContainerEnvironmentVariable(@TempDir Path dir) throws Exception {
        Path cgroupFile = Files.writeString(dir.resolve("cgroup"), "0::/\n");
        Signals signals = new Signals(
                dir.resolve(".dockerenv"),
                dir.resolve(".containerenv"),
                Map.of("container", "oci"),
                cgroupFile,
                dir.resolve("mountinfo"));

        assertThat(ContainerRuntime.detect(signals)).isEqualTo(ContainerRuntime.CONTAINER);
    }

    @Test
    void detectsNoneFromABlankContainerVariable(@TempDir Path dir) throws Exception {
        Path cgroupFile = Files.writeString(dir.resolve("cgroup"), "0::/\n");
        Signals signals = new Signals(
                dir.resolve(".dockerenv"),
                dir.resolve(".containerenv"),
                Map.of("container", " "),
                cgroupFile,
                dir.resolve("mountinfo"));

        assertThat(ContainerRuntime.detect(signals)).isEqualTo(ContainerRuntime.NONE);
    }

    @Test
    void detectsAGenericContainerFromAMountInfoThatBindsItsHostsFile(@TempDir Path dir) throws Exception {
        Path cgroupFile = Files.writeString(dir.resolve("cgroup"), "0::/\n");
        Path mountInfo = Files.writeString(dir.resolve("mountinfo"), CONTAINER_MOUNT_INFO);
        Signals signals =
                new Signals(dir.resolve(".dockerenv"), dir.resolve(".containerenv"), Map.of(), cgroupFile, mountInfo);

        assertThat(ContainerRuntime.detect(signals)).isEqualTo(ContainerRuntime.CONTAINER);
    }

    /** One of the three files is enough. Each line is taken from the container excerpt. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "682 673 8:1 /var/lib/docker/containers/4b36c071eae0/hostname /etc/hostname rw,relatime - ext4 /dev/sda1 rw,errors=remount-ro\n",
                "683 673 8:1 /var/lib/docker/containers/4b36c071eae0/hosts /etc/hosts rw,relatime - ext4 /dev/sda1 rw,errors=remount-ro\n",
                "684 673 8:1 /var/lib/docker/containers/4b36c071eae0/resolv.conf /etc/resolv.conf rw,relatime - ext4 /dev/sda1 rw,errors=remount-ro\n"
            })
    void detectsAGenericContainerFromEachFileARuntimeBinds(String mount, @TempDir Path dir) throws Exception {
        Path cgroupFile = Files.writeString(dir.resolve("cgroup"), "0::/\n");
        Path mountInfo = Files.writeString(dir.resolve("mountinfo"), mount);
        Signals signals =
                new Signals(dir.resolve(".dockerenv"), dir.resolve(".containerenv"), Map.of(), cgroupFile, mountInfo);

        assertThat(ContainerRuntime.detect(signals)).isEqualTo(ContainerRuntime.CONTAINER);
    }

    @Test
    void detectsNoneOnAHostThatRunsContainersItself(@TempDir Path dir) throws Exception {
        Path cgroupFile = Files.writeString(dir.resolve("cgroup"), "0::/init.scope\n");
        Path mountInfo = Files.writeString(dir.resolve("mountinfo"), CONTAINER_HOST_MOUNT_INFO);
        Signals signals =
                new Signals(dir.resolve(".dockerenv"), dir.resolve(".containerenv"), Map.of(), cgroupFile, mountInfo);

        assertThat(ContainerRuntime.detect(signals)).isEqualTo(ContainerRuntime.NONE);
    }

    @Test
    void detectsNoneWhenTheMountInfoIsUnreadable(@TempDir Path dir) throws Exception {
        PosixPermissions.assumeSupported();
        Path cgroupFile = Files.writeString(dir.resolve("cgroup"), "0::/\n");
        Path mountInfo = Files.writeString(dir.resolve("mountinfo"), CONTAINER_MOUNT_INFO);
        Files.setPosixFilePermissions(mountInfo, Set.<PosixFilePermission>of());
        assumeFalse(Files.isReadable(mountInfo), "root reads a file whatever its mode says");
        Signals signals =
                new Signals(dir.resolve(".dockerenv"), dir.resolve(".containerenv"), Map.of(), cgroupFile, mountInfo);

        assertThat(ContainerRuntime.detect(signals)).isEqualTo(ContainerRuntime.NONE);
    }

    /** The frontend renders the word as it arrives; the JSON is the contract, not the accessor. */
    @Test
    void serialisesAsTheLowercaseWordTheFrontendRenders() {
        assertThat(PeekabootJson.MAPPER.writeValueAsString(List.of(ContainerRuntime.KUBERNETES, ContainerRuntime.NONE)))
                .isEqualTo("[\"kubernetes\",\"none\"]");
    }
}

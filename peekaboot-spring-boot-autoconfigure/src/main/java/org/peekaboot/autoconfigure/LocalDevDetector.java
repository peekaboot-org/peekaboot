package org.peekaboot.autoconfigure;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.NativeDetector;

/**
 * Detects whether the application is running in a local development environment
 * (IDE launch, {@code spring-boot:run}/{@code bootRun}) as opposed to a packaged
 * archive, a servlet container, a container image, a test, an AOT build, or a native
 * image.
 *
 * <p>Starts from the heuristics Spring Boot DevTools uses ({@code DevToolsEnablementDeducer}
 * and {@code DefaultRestartInitializer}): the {@code main} thread runs on the JDK's
 * {@code AppClassLoader} (packaged jars use Boot's {@code LaunchedClassLoader}, wars the
 * container's webapp loader) and no test-framework or AOT frames are on the stack. That
 * alone also matches every exploded-classpath launch - a Jib image, Spring Boot's
 * {@code extract} layout, a hand-written {@code java -cp} - so two further signals decide:
 * the class path must contain a build tool's output directory (the positive proof of an
 * IDE, {@code spring-boot:run} or {@code bootRun} launch), and the process must not show
 * a container marker.
 *
 * <p>With DevTools on the classpath the application is relaunched on the
 * {@code restartedMain} thread under DevTools' {@code RestartClassLoader}
 * before the environment is built; since DevTools only enables itself in a
 * local launch, that classloader is itself proof of local development.
 */
final class LocalDevDetector {

    private static final Set<String> SKIPPED_STACK_ELEMENTS = Set.of(
            "org.junit.runners.",
            "org.junit.platform.",
            "org.springframework.boot.test.",
            "org.springframework.boot.SpringApplicationAotProcessor",
            "cucumber.runtime.");

    /** Build output directories as IDEs, spring-boot:run and bootRun put them on the class path. */
    private static final Set<String> BUILD_OUTPUT_SUFFIXES =
            Set.of("/target/classes", "/build/classes/java/main", "/build/classes/kotlin/main", "/bin/main");

    /** IntelliJ's own builder: {@code out/production/<module>}. */
    private static final String INTELLIJ_OUTPUT_SEGMENT = "/out/production/";

    private static final Set<String> CONTAINER_CGROUP_MARKERS = Set.of("docker", "kubepods", "containerd");

    private LocalDevDetector() {}

    /**
     * The launch facts the class-loader, thread and stack checks cannot see: the class
     * path and whether the process runs in a container. A record so tests can state a
     * launch shape outright instead of faking a JVM.
     */
    record LaunchSignals(String classPath, boolean containerMarkers) {

        static LaunchSignals fromRuntime() {
            return new LaunchSignals(
                    System.getProperty("java.class.path", ""),
                    containerMarkersPresent(Path.of("/.dockerenv"), System.getenv(), Path.of("/proc/1/cgroup")));
        }

        boolean buildOutputOnClassPath() {
            for (String entry : classPath.split(Pattern.quote(File.pathSeparator), -1)) {
                String normalized = "/" + entry.replace('\\', '/');
                if (normalized.endsWith("/")) {
                    normalized = normalized.substring(0, normalized.length() - 1);
                }
                if (normalized.contains(INTELLIJ_OUTPUT_SEGMENT)) {
                    return true;
                }
                for (String suffix : BUILD_OUTPUT_SUFFIXES) {
                    if (normalized.endsWith(suffix)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    static boolean isLocalDevelopment(Thread thread) {
        return isLocalDevelopment(thread, thread.getStackTrace(), LaunchSignals.fromRuntime());
    }

    static boolean isLocalDevelopment(Thread thread, StackTraceElement[] stackTrace, LaunchSignals signals) {
        if (NativeDetector.inNativeImage()) {
            return false;
        }
        ClassLoader classLoader = thread.getContextClassLoader();
        if (classLoader == null) {
            return false;
        }
        if (classLoader.getClass().getName().contains("RestartClassLoader")) {
            return true;
        }
        if (!"main".equals(thread.getName())) {
            return false;
        }
        if (!classLoader.getClass().getName().contains("AppClassLoader")) {
            return false;
        }
        for (StackTraceElement element : stackTrace) {
            for (String skipped : SKIPPED_STACK_ELEMENTS) {
                if (element.getClassName().startsWith(skipped)) {
                    return false;
                }
            }
        }
        return signals.buildOutputOnClassPath() && !signals.containerMarkers();
    }

    /**
     * Docker's marker file, Kubernetes' service environment, or a cgroup path naming a
     * container runtime. Any one of them means the exploded class path is an image layout,
     * not a developer's checkout.
     */
    static boolean containerMarkersPresent(Path dockerEnv, Map<String, String> env, Path cgroup) {
        if (Files.exists(dockerEnv) || env.containsKey("KUBERNETES_SERVICE_HOST")) {
            return true;
        }
        if (!Files.isRegularFile(cgroup)) {
            return false;
        }
        try {
            String content = Files.readString(cgroup).toLowerCase(Locale.ROOT);
            for (String marker : CONTAINER_CGROUP_MARKERS) {
                if (content.contains(marker)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}

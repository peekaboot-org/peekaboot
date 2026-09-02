package org.peekaboot.autoconfigure;

import java.io.File;
import java.util.Set;
import java.util.regex.Pattern;
import org.peekaboot.backend.domain.runtime.ContainerRuntime;
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
 * local launch, that classloader stands in for the class-path check. The
 * container check still applies: DevTools has none of its own, and Jib or an
 * extracted layout ships it whenever it is a runtime dependency.
 */
final class LocalDevDetector {

    private static final Set<String> SKIPPED_STACK_ELEMENTS = Set.of(
            "org.junit.runners.",
            "org.junit.platform.",
            "org.springframework.boot.test.",
            "org.springframework.boot.SpringApplicationAotProcessor",
            "cucumber.runtime.");

    /** Build output directories as IDEs, spring-boot:run and bootRun put them on the class path. */
    private static final Set<String> BUILD_OUTPUT_SUFFIXES = Set.of(
            "/target/classes",
            "/build/classes/java/main",
            "/build/classes/kotlin/main",
            "/build/classes/groovy/main",
            "/build/classes/scala/main",
            "/bin/main");

    /** IntelliJ's own builder: {@code out/production/<module>}. */
    private static final String INTELLIJ_OUTPUT_SEGMENT = "/out/production/";

    private LocalDevDetector() {}

    /**
     * The launch facts the class-loader, thread and stack checks cannot see: the class
     * path and whether the process runs in a container. A record so tests can state a
     * launch shape outright instead of faking a JVM.
     */
    record LaunchSignals(String classPath, boolean containerMarkers) {

        static LaunchSignals fromRuntime() {
            // any detected runtime means the exploded class path is an image
            // layout, not a developer's checkout
            return new LaunchSignals(
                    System.getProperty("java.class.path", ""), ContainerRuntime.current() != ContainerRuntime.NONE);
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
            return !signals.containerMarkers();
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
}

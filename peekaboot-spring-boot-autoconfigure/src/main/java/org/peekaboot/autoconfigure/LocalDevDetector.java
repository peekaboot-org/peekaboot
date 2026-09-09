package org.peekaboot.autoconfigure;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
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
 * a container marker. The output directory may sit behind a jar's {@code Class-Path}
 * manifest attribute: IntelliJ's "JAR manifest" command-line shortening leaves one temp
 * jar on {@code java.class.path} and moves every real entry into that jar's manifest.
 *
 * <p>With DevTools on the classpath the application is relaunched on the
 * {@code restartedMain} thread under DevTools' {@code RestartClassLoader}
 * before the environment is built. DevTools only reaches that relaunch after
 * its own gate ({@code RestartApplicationListener}, {@code DefaultRestartInitializer})
 * has required a thread named {@code main}, an {@code AppClassLoader} and a
 * stack free of the same frames this class skips - so the restart branch does
 * not repeat those three. Its class-path signal is not equivalent, though:
 * DevTools' {@code ChangeableUrls} treats every directory URL on the class
 * path as reloadable, not just a build tool's output directory, so it says yes
 * for a Jib image or an extracted layout too - the exact shapes the build-output
 * check exists to reject. The class-path and container checks below both still
 * apply to this branch.
 *
 * <p>A container marker is a container marker wherever it comes from, so a checkout worked on
 * inside a devcontainer - VS Code Dev Containers, GitHub Codespaces - is not a local launch by
 * this measure however it was started; what the detection would have switched on there
 * ({@code peekaboot.enabled}, {@code peekaboot.dev-toolbar}, {@code peekaboot.storage.enabled})
 * has to be set explicitly.
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

    private static final Pattern MANIFEST_ENTRY_SEPARATOR = Pattern.compile("\\s+");

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
                if (isBuildOutput(entry)) {
                    return true;
                }
                if (entry.endsWith(".jar")
                        && manifestClassPath(new File(entry)).stream().anyMatch(LaunchSignals::isBuildOutput)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isBuildOutput(String entry) {
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
            return false;
        }

        /**
         * The jar's {@code Class-Path} manifest entries as file paths. The JAR spec makes them
         * URLs relative to the jar's own location; IntelliJ writes absolute {@code file:} URLs.
         * A jar without a manifest, or one that cannot be opened, contributes nothing.
         */
        private static List<String> manifestClassPath(File jar) {
            try (JarFile jarFile = new JarFile(jar)) {
                Manifest manifest = jarFile.getManifest();
                String classPath =
                        manifest == null ? null : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
                if (classPath == null) {
                    return List.of();
                }
                URI jarUri = jar.toURI();
                List<String> paths = new ArrayList<>();
                for (String entry : MANIFEST_ENTRY_SEPARATOR.split(classPath.trim(), -1)) {
                    resolvedFilePath(jarUri, entry).ifPresent(paths::add);
                }
                return paths;
            } catch (IOException e) {
                return List.of();
            }
        }

        /** Empty for a non-file URL, and for an entry that is no URI at all: one bad entry must not hide the rest. */
        private static Optional<String> resolvedFilePath(URI jarUri, String entry) {
            try {
                URI resolved = jarUri.resolve(entry);
                return "file".equals(resolved.getScheme()) ? Optional.of(resolved.getPath()) : Optional.empty();
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
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
            return isDeveloperLaunch(signals);
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
        return isDeveloperLaunch(signals);
    }

    /** The two signals a class loader and a clean stack cannot see, shared by every branch that gets this far. */
    private static boolean isDeveloperLaunch(LaunchSignals signals) {
        return signals.buildOutputOnClassPath() && !signals.containerMarkers();
    }
}

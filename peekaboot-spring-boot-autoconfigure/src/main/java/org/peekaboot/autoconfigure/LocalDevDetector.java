package org.peekaboot.autoconfigure;

import java.util.Set;
import org.springframework.core.NativeDetector;

/**
 * Detects whether the application is running in a local development environment
 * (IDE launch, {@code spring-boot:run}/{@code bootRun}) as opposed to a packaged
 * archive, a servlet container, a test, an AOT build, or a native image.
 *
 * <p>Mirrors the heuristics Spring Boot DevTools uses ({@code DevToolsEnablementDeducer}
 * and {@code DefaultRestartInitializer}): local development means the {@code main}
 * thread runs on the JDK's {@code AppClassLoader} (packaged jars use Boot's
 * {@code LaunchedClassLoader}, wars the container's webapp loader) and no
 * test-framework or AOT frames are on the stack.
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

    private LocalDevDetector() {}

    static boolean isLocalDevelopment(Thread thread) {
        return isLocalDevelopment(thread, thread.getStackTrace());
    }

    static boolean isLocalDevelopment(Thread thread, StackTraceElement[] stackTrace) {
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
        return true;
    }
}

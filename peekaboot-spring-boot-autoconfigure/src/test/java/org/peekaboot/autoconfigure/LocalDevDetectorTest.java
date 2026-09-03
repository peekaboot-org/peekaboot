package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.autoconfigure.LocalDevDetector.LaunchSignals;

class LocalDevDetectorTest {

    private static final StackTraceElement[] CLEAN_STACK = {
        frame("org.peekaboot.example.ExampleApplication"), frame("org.springframework.boot.SpringApplication")
    };

    /** An IDE run of a Maven project: the module's own target/classes ahead of the local repository jars. */
    private static final LaunchSignals IDE_LAUNCH = signals(
            "/home/dev/app/target/classes", "/home/dev/.m2/repository/org/springframework/spring-core/7.0.9/x.jar");

    /** Jib's default entrypoint: {@code java -cp /app/resources:/app/classes:/app/libs/* Main}. */
    private static final LaunchSignals JIB_LAUNCH =
            signals("/app/resources", "/app/classes", "/app/libs/spring-core.jar");

    @Test
    void detectsLocalDevForMainThreadWithAppClassLoaderAndCleanStack() {
        assertThat(LocalDevDetector.isLocalDevelopment(mainThreadWithAppClassLoader(), CLEAN_STACK, IDE_LAUNCH))
                .isTrue();
    }

    @Test
    void detectsLocalDevForASpringBootRunLaunch() {
        // spring-boot:run forks a JVM whose classpath is target/classes plus the resolved dependencies
        LaunchSignals signals = signals("/home/dev/app/target/classes", "/home/dev/.m2/repository/a.jar");

        assertThat(LocalDevDetector.isLocalDevelopment(mainThreadWithAppClassLoader(), CLEAN_STACK, signals))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/home/dev/app/build/classes/java/main",
                "/home/dev/app/build/classes/kotlin/main",
                "/home/dev/app/build/classes/groovy/main",
                "/home/dev/app/build/classes/scala/main",
                "/home/dev/app/out/production/app",
                "/home/dev/app/bin/main",
                "target/classes"
            })
    void detectsLocalDevForEveryBuildToolOutputDirectory(String outputDirectory) {
        // Gradle's bootRun and IDE delegation, IntelliJ's own builder, Eclipse/Buildship, and a
        // relative classpath as some IDE launchers pass it
        LaunchSignals signals = signals(outputDirectory, "/home/dev/.gradle/caches/a.jar");

        assertThat(LocalDevDetector.isLocalDevelopment(mainThreadWithAppClassLoader(), CLEAN_STACK, signals))
                .isTrue();
    }

    @Test
    void detectsLocalDevForAWindowsBuildOutputDirectory() {
        LaunchSignals signals = new LaunchSignals("C:\\dev\\app\\target\\classes", false);

        assertThat(LocalDevDetector.isLocalDevelopment(mainThreadWithAppClassLoader(), CLEAN_STACK, signals))
                .isTrue();
    }

    @Test
    void rejectsAJibShapedLaunch() {
        // same thread name and class loader as an IDE run - only the classpath tells them apart
        assertThat(LocalDevDetector.isLocalDevelopment(mainThreadWithAppClassLoader(), CLEAN_STACK, JIB_LAUNCH))
                .isFalse();
    }

    @Test
    void rejectsTheExtractedJarLayout() {
        // java -Djarmode=tools -jar app.jar extract, then java -jar app/app.jar: the thin jar's
        // Class-Path manifest puts everything on the application class loader
        LaunchSignals signals = signals("/app/app.jar");

        assertThat(LocalDevDetector.isLocalDevelopment(mainThreadWithAppClassLoader(), CLEAN_STACK, signals))
                .isFalse();
    }

    @Test
    void rejectsAnIdeShapedLaunchInsideAContainer() {
        LaunchSignals signals = new LaunchSignals(IDE_LAUNCH.classPath(), true);

        assertThat(LocalDevDetector.isLocalDevelopment(mainThreadWithAppClassLoader(), CLEAN_STACK, signals))
                .isFalse();
    }

    @Test
    void detectsLocalDevForDevToolsRestartedThread() {
        // DevTools relaunches the app on "restartedMain" with its RestartClassLoader and only
        // ever enables itself in a local launch - proof on its own, whatever the classpath
        // says, unless the process shows a container marker
        assertThat(LocalDevDetector.isLocalDevelopment(devToolsRestartedThread(), CLEAN_STACK, JIB_LAUNCH))
                .isTrue();
    }

    /**
     * DevTools has no container check of its own, and an image built by Jib or from Boot's
     * extracted layout ships it whenever it is a runtime dependency - so the restart alone
     * would switch Peekaboot on inside a reachable container.
     */
    @Test
    void rejectsADevToolsRestartInsideAContainer() {
        LaunchSignals signals = new LaunchSignals(JIB_LAUNCH.classPath(), true);

        assertThat(LocalDevDetector.isLocalDevelopment(devToolsRestartedThread(), CLEAN_STACK, signals))
                .isFalse();
    }

    @Test
    void rejectsThreadNotNamedMain() {
        Thread thread = new Thread(() -> {}, "worker-1");
        thread.setContextClassLoader(new FakeAppClassLoader());

        assertThat(LocalDevDetector.isLocalDevelopment(thread, CLEAN_STACK, IDE_LAUNCH))
                .isFalse();
    }

    @Test
    void rejectsNonAppClassLoader() {
        // packaged jars run under Boot's LaunchedClassLoader, wars under the
        // container's webapp loader - neither name contains "AppClassLoader"
        Thread thread = new Thread(() -> {}, "main");
        thread.setContextClassLoader(new PackagedArchiveClassLoader());

        assertThat(LocalDevDetector.isLocalDevelopment(thread, CLEAN_STACK, IDE_LAUNCH))
                .isFalse();
    }

    @Test
    void rejectsMissingContextClassLoader() {
        Thread thread = new Thread(() -> {}, "main");
        thread.setContextClassLoader(null);

        assertThat(LocalDevDetector.isLocalDevelopment(thread, CLEAN_STACK, IDE_LAUNCH))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "org.junit.platform.launcher.core.DefaultLauncher",
                "org.junit.runners.ParentRunner",
                "org.springframework.boot.test.context.SpringBootContextLoader",
                "cucumber.runtime.Runtime",
                "org.springframework.boot.SpringApplicationAotProcessor"
            })
    void rejectsTestAndAotFramesOnTheStack(String frameClassName) {
        assertThat(LocalDevDetector.isLocalDevelopment(
                        mainThreadWithAppClassLoader(), stackWith(frameClassName), IDE_LAUNCH))
                .isFalse();
    }

    @Test
    void oneArgOverloadRejectsJUnitTestInvocation() {
        // called from a JUnit-launched test, the real stack contains JUnit frames
        assertThat(LocalDevDetector.isLocalDevelopment(Thread.currentThread())).isFalse();
    }

    @Test
    void aSpawnedMainThreadHasACleanStackOfItsOwn() throws InterruptedException {
        // a thread spawned from a test has no JUnit frames on its own stack, so with the
        // right name, class loader and launch signals it must count as local dev
        AtomicBoolean result = new AtomicBoolean(false);
        Thread thread = new Thread(
                () -> result.set(LocalDevDetector.isLocalDevelopment(
                        Thread.currentThread(), Thread.currentThread().getStackTrace(), IDE_LAUNCH)),
                "main");
        thread.setContextClassLoader(new FakeAppClassLoader());
        thread.start();
        thread.join();

        assertThat(result).isTrue();
    }

    private static LaunchSignals signals(String... classPathEntries) {
        return new LaunchSignals(String.join(File.pathSeparator, classPathEntries), false);
    }

    private static Thread mainThreadWithAppClassLoader() {
        Thread thread = new Thread(() -> {}, "main");
        thread.setContextClassLoader(new FakeAppClassLoader());
        return thread;
    }

    private static Thread devToolsRestartedThread() {
        Thread thread = new Thread(() -> {}, "restartedMain");
        thread.setContextClassLoader(new FakeRestartClassLoader());
        return thread;
    }

    private static StackTraceElement[] stackWith(String className) {
        return new StackTraceElement[] {
            frame("org.peekaboot.example.ExampleApplication"),
            frame(className),
            frame("org.springframework.boot.SpringApplication")
        };
    }

    private static StackTraceElement frame(String className) {
        return new StackTraceElement(className, "run", className + ".java", 1);
    }

    /** Class name intentionally contains "AppClassLoader", like jdk.internal.loader.ClassLoaders$AppClassLoader. */
    private static final class FakeAppClassLoader extends ClassLoader {}

    /** Class name intentionally contains "RestartClassLoader", like DevTools' restart.classloader.RestartClassLoader. */
    private static final class FakeRestartClassLoader extends ClassLoader {}

    private static final class PackagedArchiveClassLoader extends ClassLoader {}
}

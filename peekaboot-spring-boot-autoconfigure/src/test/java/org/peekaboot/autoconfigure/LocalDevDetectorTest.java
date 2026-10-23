package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.autoconfigure.LocalDevDetector.LaunchKind;
import org.peekaboot.autoconfigure.LocalDevDetector.LaunchSignals;

class LocalDevDetectorTest {

    private static final StackTraceElement[] CLEAN_STACK =
            stackOf("org.peekaboot.example.ExampleApplication", "org.springframework.boot.SpringApplication");

    /** An IDE run of a Maven project: the module's own target/classes ahead of the local repository jars. */
    private static final LaunchSignals IDE_LAUNCH = signals(
            "/home/dev/app/target/classes", "/home/dev/.m2/repository/org/springframework/spring-core/7.0.9/x.jar");

    /** Jib's default entrypoint: {@code java -cp /app/resources:/app/classes:/app/libs/* Main}. */
    private static final LaunchSignals JIB_LAUNCH =
            signals("/app/resources", "/app/classes", "/app/libs/spring-core.jar");

    @Test
    void detectsLocalDevForMainThreadWithAppClassLoaderAndCleanStack() {
        assertThat(LocalDevDetector.launchKind(mainThreadWithAppClassLoader(), CLEAN_STACK, IDE_LAUNCH))
                .isEqualTo(LaunchKind.LOCAL_DEV);
    }

    @Test
    void detectsLocalDevForASpringBootRunLaunch() {
        // spring-boot:run forks a JVM whose classpath is target/classes plus the resolved dependencies
        LaunchSignals signals = signals("/home/dev/app/target/classes", "/home/dev/.m2/repository/a.jar");

        assertThat(LocalDevDetector.launchKind(mainThreadWithAppClassLoader(), CLEAN_STACK, signals))
                .isEqualTo(LaunchKind.LOCAL_DEV);
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

        assertThat(LocalDevDetector.launchKind(mainThreadWithAppClassLoader(), CLEAN_STACK, signals))
                .isEqualTo(LaunchKind.LOCAL_DEV);
    }

    @Test
    void detectsLocalDevForAWindowsBuildOutputDirectory() {
        LaunchSignals signals = signals("C:\\dev\\app\\target\\classes");

        assertThat(LocalDevDetector.launchKind(mainThreadWithAppClassLoader(), CLEAN_STACK, signals))
                .isEqualTo(LaunchKind.LOCAL_DEV);
    }

    @Test
    void detectsLocalDevForABuildOutputDirectoryWithATrailingSlash() {
        LaunchSignals signals = signals("/home/dev/app/target/classes/", "/home/dev/.m2/repository/a.jar");

        assertThat(LocalDevDetector.launchKind(mainThreadWithAppClassLoader(), CLEAN_STACK, signals))
                .isEqualTo(LaunchKind.LOCAL_DEV);
    }

    /**
     * IntelliJ's "JAR manifest" command-line shortening (the usual choice on Windows once a
     * Boot class path exceeds the 32K limit) leaves a single temp jar on {@code java.class.path}
     * and moves the real entries, {@code target/classes} included, into its manifest as
     * absolute {@code file:} URLs.
     */
    @Test
    void detectsLocalDevBehindAManifestClassPath(@TempDir Path tempDir) throws IOException {
        Path classpathJar = jarWithClassPath(
                tempDir,
                tempDir.resolve("app/target/classes").toUri() + "/",
                tempDir.resolve("lib/spring-core.jar").toUri().toString());
        LaunchSignals signals = signals(classpathJar.toString());

        assertThat(LocalDevDetector.launchKind(mainThreadWithAppClassLoader(), CLEAN_STACK, signals))
                .isEqualTo(LaunchKind.LOCAL_DEV);
    }

    /**
     * The JAR spec makes Class-Path entries relative to the jar's own location. The entry
     * here is {@code classes/} alone, which matches no output-directory suffix by itself;
     * only resolving it against the jar's {@code app/target} directory makes it one.
     */
    @Test
    void resolvesRelativeManifestEntriesAgainstTheJarDirectory(@TempDir Path tempDir) throws IOException {
        Path classpathJar = jarWithClassPath(tempDir.resolve("app/target"), "classes/", "lib/spring-core.jar");
        LaunchSignals signals = signals(classpathJar.toString());

        assertThat(LocalDevDetector.launchKind(mainThreadWithAppClassLoader(), CLEAN_STACK, signals))
                .isEqualTo(LaunchKind.LOCAL_DEV);
    }

    /** One entry that is not a valid URI must not hide the jar's other entries. */
    @Test
    void skipsAMalformedManifestEntryAndReadsTheRest(@TempDir Path tempDir) throws IOException {
        Path classpathJar = jarWithClassPath(tempDir.resolve("app/target"), "%zz", "classes/");
        LaunchSignals signals = signals(classpathJar.toString());

        assertThat(LocalDevDetector.launchKind(mainThreadWithAppClassLoader(), CLEAN_STACK, signals))
                .isEqualTo(LaunchKind.LOCAL_DEV);
    }

    /**
     * An empty Class-Path names no entry. Resolved as one, it would be the jar's own
     * directory, here a build output directory.
     */
    @Test
    void readsAnEmptyManifestClassPathAsNoEntries(@TempDir Path tempDir) throws IOException {
        Path classpathJar = jarWithClassPath(tempDir.resolve("app/target/classes"));
        LaunchSignals signals = signals(classpathJar.toString());

        assertThat(LocalDevDetector.launchKind(mainThreadWithAppClassLoader(), CLEAN_STACK, signals))
                .isEqualTo(LaunchKind.DEPLOYMENT);
    }

    @Test
    void rejectsAJibShapedLaunch() {
        // same thread name and class loader as an IDE run - only the classpath tells them apart
        assertThat(LocalDevDetector.launchKind(mainThreadWithAppClassLoader(), CLEAN_STACK, JIB_LAUNCH))
                .isEqualTo(LaunchKind.DEPLOYMENT);
    }

    @Test
    void rejectsTheExtractedJarLayout(@TempDir Path tempDir) throws IOException {
        // java -Djarmode=tools -jar app.jar extract, then java -jar app/app.jar: the thin jar's
        // Class-Path manifest puts everything on the application class loader, jars only
        Path thinJar = jarWithClassPath(tempDir, "lib/spring-core.jar", "lib/spring-boot.jar");
        LaunchSignals signals = signals(thinJar.toString());

        assertThat(LocalDevDetector.launchKind(mainThreadWithAppClassLoader(), CLEAN_STACK, signals))
                .isEqualTo(LaunchKind.DEPLOYMENT);
    }

    @Test
    void rejectsAnIdeShapedLaunchInsideAContainer() {
        LaunchSignals signals = new LaunchSignals(IDE_LAUNCH.classPath(), true);

        assertThat(LocalDevDetector.launchKind(mainThreadWithAppClassLoader(), CLEAN_STACK, signals))
                .isEqualTo(LaunchKind.DEPLOYMENT);
    }

    @Test
    void detectsLocalDevForDevToolsRestartedThread() {
        // DevTools only reaches the restartedMain/RestartClassLoader relaunch after its own
        // gate required a "main" thread, an AppClassLoader and a clean stack, so this branch
        // does not repeat those - but its class-path signal (any directory URL) is looser
        // than Peekaboot's, so an IDE-shaped launch is what proves this true
        assertThat(LocalDevDetector.launchKind(devToolsRestartedThread(), CLEAN_STACK, IDE_LAUNCH))
                .isEqualTo(LaunchKind.LOCAL_DEV);
    }

    /**
     * DevTools' own class-path signal ({@code ChangeableUrls}) treats every directory URL as
     * reloadable, not just a build tool's output directory - the same gap the base branch
     * closes with {@code buildOutputOnClassPath()} for a Jib image or an extracted layout
     * that ships DevTools by accident. The restart branch must close it too.
     */
    @Test
    void rejectsADevToolsRestartWithoutBuildOutputOnClassPath() {
        assertThat(LocalDevDetector.launchKind(devToolsRestartedThread(), CLEAN_STACK, JIB_LAUNCH))
                .isEqualTo(LaunchKind.DEPLOYMENT);
    }

    /**
     * DevTools has no container check of its own, and an image built by Jib or from Boot's
     * extracted layout ships it whenever it is a runtime dependency - so the restart alone
     * would switch Peekaboot on inside a reachable container.
     */
    @Test
    void rejectsADevToolsRestartInsideAContainer() {
        // build output present, so only the container marker can produce the rejection -
        // a Jib-shaped class path would pass through buildOutputOnClassPath() alone
        LaunchSignals signals = new LaunchSignals(IDE_LAUNCH.classPath(), true);

        assertThat(LocalDevDetector.launchKind(devToolsRestartedThread(), CLEAN_STACK, signals))
                .isEqualTo(LaunchKind.DEPLOYMENT);
    }

    @Test
    void rejectsThreadNotNamedMain() {
        Thread thread = new Thread(() -> {}, "worker-1");
        thread.setContextClassLoader(new FakeAppClassLoader());

        assertThat(LocalDevDetector.launchKind(thread, CLEAN_STACK, IDE_LAUNCH)).isEqualTo(LaunchKind.DEPLOYMENT);
    }

    @Test
    void rejectsNonAppClassLoader() {
        // packaged jars run under Boot's LaunchedClassLoader, wars under the
        // container's webapp loader - neither name contains "AppClassLoader"
        Thread thread = new Thread(() -> {}, "main");
        thread.setContextClassLoader(new PackagedArchiveClassLoader());

        assertThat(LocalDevDetector.launchKind(thread, CLEAN_STACK, IDE_LAUNCH)).isEqualTo(LaunchKind.DEPLOYMENT);
    }

    @Test
    void rejectsMissingContextClassLoader() {
        Thread thread = new Thread(() -> {}, "main");
        thread.setContextClassLoader(null);

        assertThat(LocalDevDetector.launchKind(thread, CLEAN_STACK, IDE_LAUNCH)).isEqualTo(LaunchKind.DEPLOYMENT);
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
    void readsTestAndAotFramesOnTheStackAsATest(String frameClassName) {
        assertThat(LocalDevDetector.launchKind(
                        mainThreadWithAppClassLoader(),
                        stackOf(
                                "org.peekaboot.example.ExampleApplication",
                                frameClassName,
                                "org.springframework.boot.SpringApplication"),
                        IDE_LAUNCH))
                .isEqualTo(LaunchKind.TEST);
    }

    @Test
    void readsTheRealStackOfAJUnitLaunchAsATest() {
        // called from a JUnit-launched test, the real stack contains JUnit frames
        assertThat(LocalDevDetector.launchKind(Thread.currentThread())).isEqualTo(LaunchKind.TEST);
    }

    @Test
    void aSpawnedMainThreadHasACleanStackOfItsOwn() throws InterruptedException {
        // a thread spawned from a test has no JUnit frames on its own stack, so with the
        // right name, class loader and launch signals it must count as local dev
        AtomicReference<LaunchKind> result = new AtomicReference<>();
        Thread thread = new Thread(
                () -> result.set(LocalDevDetector.launchKind(
                        Thread.currentThread(), Thread.currentThread().getStackTrace(), IDE_LAUNCH)),
                "main");
        thread.setContextClassLoader(new FakeAppClassLoader());
        thread.start();
        thread.join();

        assertThat(result).hasValue(LaunchKind.LOCAL_DEV);
    }

    @Test
    void isATestLaunchWhenAJUnitFrameIsOnTheStack() {
        var stack = stackOf("org.junit.platform.launcher.core.DefaultLauncher", "com.acme.App");
        var signals = signals("/home/dev/app/target/classes");

        var kind = LocalDevDetector.launchKind(mainThreadWithAppClassLoader(), stack, signals);

        assertThat(kind).isEqualTo(LaunchKind.TEST);
    }

    /** A test is a test even where the local-dev branch would reject the class path. */
    @Test
    void isATestLaunchEvenWithoutBuildOutputOnTheClassPath() {
        var stack = stackOf("org.springframework.boot.test.context.SpringBootContextLoader", "com.acme.App");
        var signals = signals("/opt/app/app.jar");

        var kind = LocalDevDetector.launchKind(mainThreadWithAppClassLoader(), stack, signals);

        assertThat(kind).isEqualTo(LaunchKind.TEST);
    }

    @Test
    void isALocalLaunchFromAnIde() {
        var stack = stackOf("com.acme.App");
        var signals = signals("/home/dev/app/target/classes");

        var kind = LocalDevDetector.launchKind(mainThreadWithAppClassLoader(), stack, signals);

        assertThat(kind).isEqualTo(LaunchKind.LOCAL_DEV);
    }

    @Test
    void isADeploymentForAPackagedJar() {
        var stack = stackOf("com.acme.App");
        var signals = signals("/opt/app/app.jar");

        var kind = LocalDevDetector.launchKind(mainThreadWithAppClassLoader(), stack, signals);

        assertThat(kind).isEqualTo(LaunchKind.DEPLOYMENT);
    }

    @Test
    void isADeploymentInAContainerDespiteBuildOutput() {
        var stack = stackOf("com.acme.App");
        var signals = new LaunchSignals("/app/target/classes", true);

        var kind = LocalDevDetector.launchKind(mainThreadWithAppClassLoader(), stack, signals);

        assertThat(kind).isEqualTo(LaunchKind.DEPLOYMENT);
    }

    private static StackTraceElement[] stackOf(String... classNames) {
        return Arrays.stream(classNames)
                .map(name -> new StackTraceElement(name, "run", null, 1))
                .toArray(StackTraceElement[]::new);
    }

    private static LaunchSignals signals(String... classPathEntries) {
        return new LaunchSignals(String.join(File.pathSeparator, classPathEntries), false);
    }

    /** An empty jar whose manifest carries the given space-separated Class-Path entries. */
    private static Path jarWithClassPath(Path directory, String... classPathEntries) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, String.join(" ", classPathEntries));
        Path jar = Files.createDirectories(directory).resolve("classpath1.jar");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream ignored = new JarOutputStream(out, manifest)) {
            // the manifest is all this jar carries
        }
        return jar;
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

    /** Class name intentionally contains "AppClassLoader", like jdk.internal.loader.ClassLoaders$AppClassLoader. */
    private static final class FakeAppClassLoader extends ClassLoader {}

    /** Class name intentionally contains "RestartClassLoader", like DevTools' restart.classloader.RestartClassLoader. */
    private static final class FakeRestartClassLoader extends ClassLoader {}

    private static final class PackagedArchiveClassLoader extends ClassLoader {}
}

package org.peekaboot.autoconfigure;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDevDetectorTest {

    private static final StackTraceElement[] CLEAN_STACK = {
            frame("org.peekaboot.example.ExampleApplication"),
            frame("org.springframework.boot.SpringApplication")
    };

    @Test
    void detectsLocalDevForMainThreadWithAppClassLoaderAndCleanStack() {
        assertThat(LocalDevDetector.isLocalDevelopment(mainThreadWithAppClassLoader(), CLEAN_STACK)).isTrue();
    }

    @Test
    void rejectsThreadNotNamedMain() {
        Thread thread = new Thread(() -> {
        }, "worker-1");
        thread.setContextClassLoader(new FakeAppClassLoader());

        assertThat(LocalDevDetector.isLocalDevelopment(thread, CLEAN_STACK)).isFalse();
    }

    @Test
    void rejectsNonAppClassLoader() {
        // packaged jars run under Boot's LaunchedClassLoader, wars under the
        // container's webapp loader - neither name contains "AppClassLoader"
        Thread thread = new Thread(() -> {
        }, "main");
        thread.setContextClassLoader(new PackagedArchiveClassLoader());

        assertThat(LocalDevDetector.isLocalDevelopment(thread, CLEAN_STACK)).isFalse();
    }

    @Test
    void rejectsMissingContextClassLoader() {
        Thread thread = new Thread(() -> {
        }, "main");
        thread.setContextClassLoader(null);

        assertThat(LocalDevDetector.isLocalDevelopment(thread, CLEAN_STACK)).isFalse();
    }

    @Test
    void rejectsJUnitPlatformOnStack() {
        assertThat(LocalDevDetector.isLocalDevelopment(mainThreadWithAppClassLoader(),
                stackWith("org.junit.platform.launcher.core.DefaultLauncher"))).isFalse();
    }

    @Test
    void rejectsJUnitRunnersOnStack() {
        assertThat(LocalDevDetector.isLocalDevelopment(mainThreadWithAppClassLoader(),
                stackWith("org.junit.runners.ParentRunner"))).isFalse();
    }

    @Test
    void rejectsSpringBootTestOnStack() {
        assertThat(LocalDevDetector.isLocalDevelopment(mainThreadWithAppClassLoader(),
                stackWith("org.springframework.boot.test.context.SpringBootContextLoader"))).isFalse();
    }

    @Test
    void rejectsCucumberOnStack() {
        assertThat(LocalDevDetector.isLocalDevelopment(mainThreadWithAppClassLoader(),
                stackWith("cucumber.runtime.Runtime"))).isFalse();
    }

    @Test
    void rejectsAotProcessorOnStack() {
        assertThat(LocalDevDetector.isLocalDevelopment(mainThreadWithAppClassLoader(),
                stackWith("org.springframework.boot.SpringApplicationAotProcessor"))).isFalse();
    }

    @Test
    void oneArgOverloadRejectsJUnitTestInvocation() {
        // called from a JUnit-launched test, the real stack contains JUnit frames
        assertThat(LocalDevDetector.isLocalDevelopment(Thread.currentThread())).isFalse();
    }

    @Test
    void oneArgOverloadDetectsCleanMainThread() throws InterruptedException {
        // a thread spawned from a test has no JUnit frames on its own stack, so
        // with the right name and classloader it must count as local dev
        AtomicBoolean result = new AtomicBoolean(false);
        Thread thread = new Thread(
                () -> result.set(LocalDevDetector.isLocalDevelopment(Thread.currentThread())), "main");
        thread.setContextClassLoader(new FakeAppClassLoader());
        thread.start();
        thread.join();

        assertThat(result).isTrue();
    }

    private static Thread mainThreadWithAppClassLoader() {
        Thread thread = new Thread(() -> {
        }, "main");
        thread.setContextClassLoader(new FakeAppClassLoader());
        return thread;
    }

    private static StackTraceElement[] stackWith(String className) {
        return new StackTraceElement[]{
                frame("org.peekaboot.example.ExampleApplication"),
                frame(className),
                frame("org.springframework.boot.SpringApplication")
        };
    }

    private static StackTraceElement frame(String className) {
        return new StackTraceElement(className, "run", className + ".java", 1);
    }

    /** Class name intentionally contains "AppClassLoader", like jdk.internal.loader.ClassLoaders$AppClassLoader. */
    private static final class FakeAppClassLoader extends ClassLoader {
    }

    private static final class PackagedArchiveClassLoader extends ClassLoader {
    }
}

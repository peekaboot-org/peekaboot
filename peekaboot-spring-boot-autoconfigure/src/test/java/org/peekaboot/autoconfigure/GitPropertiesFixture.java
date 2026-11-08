package org.peekaboot.autoconfigure;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;

/** A dependency jar whose root holds a git.properties, and the start of an application that carries it. */
final class GitPropertiesFixture {

    static final String DEPENDENCY_JAR = "dependency.jar";

    private GitPropertiesFixture() {}

    /** The test classpath plus a dependency jar whose root holds a git.properties, so Boot's bean has one to read. */
    static URLClassLoader withDependencyShippingGitProperties(Path directory) throws IOException {
        Path jar = directory.resolve(DEPENDENCY_JAR);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("git.properties"));
            out.write("git.commit.id.abbrev=dep1234\ngit.build.version=9.9.9\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        return new URLClassLoader(new URL[] {jar.toUri().toURL()}, GitPropertiesFixture.class.getClassLoader());
    }

    /**
     * Publishes the ApplicationStartedEvent of an application whose classpath is {@code entries}
     * alone, so a git.properties on the test JVM's own classpath cannot answer for it.
     */
    static void publishStarted(ConfigurableApplicationContext context, Path... entries) throws IOException {
        URL[] urls = new URL[entries.length];
        for (int i = 0; i < entries.length; i++) {
            urls[i] = entries[i].toUri().toURL();
        }
        try (URLClassLoader classpath = new URLClassLoader(urls, null)) {
            SpringApplication application = new SpringApplication(new DefaultResourceLoader(classpath));
            application.setMainApplicationClass(null);
            context.publishEvent(new ApplicationStartedEvent(application, new String[0], context, Duration.ZERO));
        }
    }
}

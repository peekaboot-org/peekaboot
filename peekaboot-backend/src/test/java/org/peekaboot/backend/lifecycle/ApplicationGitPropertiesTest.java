package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.util.ClassUtils;

/**
 * Every classpath here is a class loader without a parent, so the test's own classpath, which
 * carries jdbc-url-parser's git.properties, never answers.
 */
class ApplicationGitPropertiesTest {

    private static final String MAIN_CLASS_FILE =
            ClassUtils.convertClassNameToResourcePath(FixtureMain.class.getName()) + ClassUtils.CLASS_FILE_SUFFIX;

    private static final String DEPENDENCY_GIT = "git.commit.id.abbrev=dep1234\ngit.build.version=9.9.9\n";

    private static final String OWN_GIT = "git.commit.id.abbrev=own1234\ngit.branch=résumé\n";

    @TempDir
    private Path root;

    /** The application's main class, loaded from wherever a test puts its bytes. */
    static final class FixtureMain {}

    @Test
    void aDependencysGitPropertiesIsNotTheApplications() throws Exception {
        try (URLClassLoader classpath = classpath(
                jar("dependency.jar", false, Map.of("git.properties", DEPENDENCY_GIT)),
                directory("classes", true, Map.of()))) {
            assertThat(read(classpath, true)).isNull();
        }
    }

    /** Listed first, the dependency's is the one Boot reads. The file is UTF-8, as Boot reads it. */
    @Test
    void theApplicationsJarHoldsItsOwnBehindADependencyListedFirst() throws Exception {
        try (URLClassLoader classpath = classpath(
                jar("dependency.jar", false, Map.of("git.properties", DEPENDENCY_GIT)),
                jar("application.jar", true, Map.of("git.properties", OWN_GIT)))) {
            GitProperties git = read(classpath, true);

            assertThat(git).isNotNull();
            assertThat(git.getShortCommitId()).isEqualTo("own1234");
            assertThat(git.getBranch()).isEqualTo("résumé");
        }
    }

    /** Gradle puts resources in a directory apart from the classes, so git.properties sits beside the main class's root. */
    @Test
    void aGitPropertiesInADirectoryIsTheApplications() throws Exception {
        try (URLClassLoader classpath = classpath(
                jar("dependency.jar", false, Map.of("git.properties", DEPENDENCY_GIT)),
                directory("classes", true, Map.of()),
                directory("resources", false, Map.of("git.properties", OWN_GIT)))) {
            GitProperties git = read(classpath, true);

            assertThat(git).isNotNull();
            assertThat(git.getShortCommitId()).isEqualTo("own1234");
        }
    }

    /** Without a main class no jar is known to be the application's, while a directory still is. */
    @Test
    void withoutAMainClassOnlyADirectoryCounts() throws Exception {
        try (URLClassLoader classpath = classpath(
                jar("application.jar", true, Map.of("git.properties", DEPENDENCY_GIT)),
                directory("resources", false, Map.of("git.properties", OWN_GIT)))) {
            GitProperties git = read(classpath, false);

            assertThat(git).isNotNull();
            assertThat(git.getShortCommitId()).isEqualTo("own1234");
        }
    }

    /** A ready listener that throws stops the application, so an unreadable file costs the git facts alone. */
    @Test
    void anUnreadableGitPropertiesIsSkippedWithAWarning() throws Exception {
        URL missing = root.resolve("missing").resolve("git.properties").toUri().toURL();
        ClassLoader classpath = new ClassLoader(null) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return "git.properties".equals(name)
                        ? Collections.enumeration(List.of(missing))
                        : super.getResources(name);
            }
        };
        SpringApplication application = new SpringApplication(new DefaultResourceLoader(classpath));
        application.setMainApplicationClass(null);

        try (LogCapture logs = LogCapture.attach(ApplicationGitProperties.class)) {
            assertThat(ApplicationGitProperties.of(application)).isNull();

            assertThat(logs.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .startsWith(
                                "Peekaboot could not read the application's git.properties: java.io.FileNotFoundException: ");
            });
        }
    }

    @Test
    void theApplicationsOwnIsReadOnceItHasStarted() throws Exception {
        StaticApplicationContext context = new StaticApplicationContext();
        ApplicationGitProperties gitProperties = new ApplicationGitProperties(null, context);
        try (URLClassLoader classpath = classpath(directory("resources", false, Map.of("git.properties", OWN_GIT)))) {
            assertThat(gitProperties.get()).as("before the start").isNull();

            gitProperties.onStarted(started(context, classpath));
        }
        assertThat(gitProperties.get()).isNotNull();
        assertThat(gitProperties.get().getShortCommitId()).isEqualTo("own1234");
    }

    /** A child context's start reaches the parent's listeners too. */
    @Test
    void anotherContextsStartIsNotRead() throws Exception {
        ApplicationGitProperties gitProperties = new ApplicationGitProperties(null, new StaticApplicationContext());
        try (URLClassLoader classpath = classpath(directory("resources", false, Map.of("git.properties", OWN_GIT)))) {
            gitProperties.onStarted(started(new StaticApplicationContext(), classpath));
        }
        assertThat(gitProperties.get()).isNull();
    }

    /** spring.info.git.location is the host naming its build's file, so the application's own never replaces it. */
    @Test
    void theFileTheHostLocatesIsServedFromTheOutset() throws Exception {
        StaticApplicationContext context = new StaticApplicationContext();
        Properties entries = new Properties();
        entries.setProperty("commit.id.abbrev", "host123");
        GitProperties hostLocated = new GitProperties(entries);
        ApplicationGitProperties gitProperties = new ApplicationGitProperties(hostLocated, context);
        assertThat(gitProperties.get()).as("before the start").isSameAs(hostLocated);

        try (URLClassLoader classpath = classpath(directory("resources", false, Map.of("git.properties", OWN_GIT)))) {
            gitProperties.onStarted(started(context, classpath));
        }
        assertThat(gitProperties.get()).isSameAs(hostLocated);
    }

    private static ApplicationStartedEvent started(ConfigurableApplicationContext context, URLClassLoader classpath) {
        SpringApplication application = new SpringApplication(new DefaultResourceLoader(classpath));
        application.setMainApplicationClass(null);
        return new ApplicationStartedEvent(application, new String[0], context, Duration.ZERO);
    }

    private static @Nullable GitProperties read(URLClassLoader classpath, boolean withMainClass)
            throws ClassNotFoundException {
        SpringApplication application = new SpringApplication(new DefaultResourceLoader(classpath));
        application.setMainApplicationClass(withMainClass ? classpath.loadClass(FixtureMain.class.getName()) : null);
        return ApplicationGitProperties.of(application);
    }

    private static URLClassLoader classpath(Path... entries) throws IOException {
        URL[] urls = new URL[entries.length];
        for (int i = 0; i < entries.length; i++) {
            urls[i] = entries[i].toUri().toURL();
        }
        return new URLClassLoader(urls, null);
    }

    /** A jar of {@code files}, plus the main class where {@code withMainClass}. */
    private Path jar(String name, boolean withMainClass, Map<String, String> files) throws IOException {
        Path jar = root.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries(withMainClass, files).entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
            }
        }
        return jar;
    }

    /** A directory of {@code files}, plus the main class where {@code withMainClass}. */
    private Path directory(String name, boolean withMainClass, Map<String, String> files) throws IOException {
        Path directory = root.resolve(name);
        for (Map.Entry<String, byte[]> entry : entries(withMainClass, files).entrySet()) {
            Path file = directory.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
        Files.createDirectories(directory);
        return directory;
    }

    private static Map<String, byte[]> entries(boolean withMainClass, Map<String, String> files) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        if (withMainClass) {
            try (InputStream in =
                    ApplicationGitPropertiesTest.class.getClassLoader().getResourceAsStream(MAIN_CLASS_FILE)) {
                entries.put(MAIN_CLASS_FILE, in.readAllBytes());
            }
        }
        files.forEach((path, content) -> entries.put(path, content.getBytes(StandardCharsets.UTF_8)));
        return entries;
    }
}

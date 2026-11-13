package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The starter does not bring Spring MVC, and a servlet application can serve its requests with
 * something else. A {@code FilteredClassLoader} cannot stand in for that application, since the
 * classes it hides still link through its parent, so the launch here runs on a class path the
 * Spring MVC jars are really missing from.
 */
class SpringMvcAbsentTest {

    private static final List<String> SPRING_MVC_JARS = List.of("spring-webmvc-", "spring-boot-webmvc-");

    @Test
    void aServletApplicationWithoutSpringMvcStartsWithPeekabootOn() throws IOException {
        try (URLClassLoader withoutSpringMvc =
                new URLClassLoader(classPathWithoutSpringMvc(), ClassLoader.getPlatformClassLoader())) {
            assertThat(ClassUtils.isPresent(WebMvcConfigurer.class.getName(), withoutSpringMvc))
                    .as("the launch must run without Spring MVC, or it shows nothing")
                    .isFalse();

            assertThatCode(() -> launchOn(withoutSpringMvc)).doesNotThrowAnyException();
        }
    }

    private static void launchOn(ClassLoader classLoader) throws Throwable {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(classLoader);
        try {
            classLoader
                    .loadClass(SpringMvcAbsentLaunch.class.getName())
                    .getMethod("run")
                    .invoke(null);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    private static URL[] classPathWithoutSpringMvc() throws IOException {
        List<URL> entries = new ArrayList<>();
        for (Path entry : classPath()) {
            String fileName = entry.getFileName().toString();
            if (SPRING_MVC_JARS.stream().noneMatch(fileName::startsWith)) {
                entries.add(entry.toUri().toURL());
            }
        }
        return entries.toArray(URL[]::new);
    }

    /** Surefire passes the class path as the {@code Class-Path} attribute of a single jar's manifest. */
    private static List<Path> classPath() throws IOException {
        String[] entries = System.getProperty("java.class.path").split(File.pathSeparator);
        if (entries.length == 1 && entries[0].endsWith(".jar")) {
            Path jar = Path.of(entries[0]).toAbsolutePath();
            try (JarFile jarFile = new JarFile(jar.toFile())) {
                String manifestClassPath =
                        jarFile.getManifest().getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
                if (manifestClassPath != null) {
                    return Arrays.stream(manifestClassPath.trim().split("\\s+"))
                            .map(entry -> Path.of(jar.toUri().resolve(entry)))
                            .toList();
                }
            }
        }
        return Arrays.stream(entries).map(Path::of).toList();
    }
}

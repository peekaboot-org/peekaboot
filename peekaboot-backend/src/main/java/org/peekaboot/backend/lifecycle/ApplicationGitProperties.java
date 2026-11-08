package org.peekaboot.backend.lifecycle;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.util.ClassUtils;

/**
 * The application's own git.properties. Boot's {@link GitProperties} bean reads the first one on
 * the classpath. For an application without one, that is whichever library ships one.
 * jdbc-url-parser, which Peekaboot brings, does. One in a directory belongs to a project being
 * built: the application, a module of it, or the resources directory Gradle keeps apart from its
 * classes. One inside a jar counts only at the classpath root the main application class came from.
 * The Overview and the lifecycle log both show it.
 */
public final class ApplicationGitProperties {

    private static final Logger log = LoggerFactory.getLogger(ApplicationGitProperties.class);

    private static final String LOCATION = "git.properties";

    private static final String PREFIX = "git.";

    private final @Nullable GitProperties hostLocated;

    private final ApplicationContext ownContext;

    // set on the starting thread, read by request threads
    private volatile @Nullable GitProperties own;

    /**
     * Reads the application's own git.properties once it has started, the first moment its main class is known.
     *
     * @param hostLocated Boot's, where the host names its git.properties with {@code spring.info.git.location}; null has this read the application's own
     */
    public ApplicationGitProperties(@Nullable GitProperties hostLocated, ApplicationContext ownContext) {
        this.hostLocated = hostLocated;
        this.ownContext = ownContext;
    }

    /** Null where the application has no git.properties of its own, or has not started yet. */
    public @Nullable GitProperties get() {
        return hostLocated != null ? hostLocated : own;
    }

    @EventListener
    public void onStarted(ApplicationStartedEvent event) {
        if (hostLocated == null && ContextEvents.fromOwnContext(event, ownContext)) {
            own = of(event.getSpringApplication());
        }
    }

    /** Null where the application has no git.properties of its own, or where it cannot be read. */
    static @Nullable GitProperties of(SpringApplication application) {
        String ownRoot = rootOf(application.getMainApplicationClass());
        try {
            for (URL candidate : Collections.list(application.getClassLoader().getResources(LOCATION))) {
                if (isOwn(candidate, ownRoot)) {
                    return load(candidate);
                }
            }
        } catch (IOException ex) {
            log.warn("Peekaboot could not read the application's {}: {}", LOCATION, ex.toString());
        }
        return null;
    }

    /** A directory belongs to a project being built; a jar only where the main application class came from it. */
    private static boolean isOwn(URL candidate, @Nullable String ownRoot) {
        return "file".equals(candidate.getProtocol())
                || (ownRoot != null && candidate.toString().equals(ownRoot + LOCATION));
    }

    /** The classpath root {@code mainApplicationClass} was loaded from, ending in a slash, or null where unknown. */
    private static @Nullable String rootOf(@Nullable Class<?> mainApplicationClass) {
        if (mainApplicationClass == null) {
            return null;
        }
        URL classFile = mainApplicationClass.getResource(ClassUtils.getClassFileName(mainApplicationClass));
        String path = ClassUtils.convertClassNameToResourcePath(mainApplicationClass.getName())
                + ClassUtils.CLASS_FILE_SUFFIX;
        if (classFile == null || !classFile.toString().endsWith(path)) {
            return null;
        }
        String location = classFile.toString();
        return location.substring(0, location.length() - path.length());
    }

    /** Read as UTF-8 and stripped of its prefix, as Boot's {@code ProjectInfoAutoConfiguration} reads it by default, so the entries read alike. */
    private static GitProperties load(URL location) throws IOException {
        Properties source = PropertiesLoaderUtils.loadProperties(
                new EncodedResource(new UrlResource(location), StandardCharsets.UTF_8));
        Properties entries = new Properties();
        for (String key : source.stringPropertyNames()) {
            if (key.startsWith(PREFIX)) {
                entries.setProperty(key.substring(PREFIX.length()), source.getProperty(key));
            }
        }
        return new GitProperties(entries);
    }
}

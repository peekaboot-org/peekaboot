package org.peekaboot.autoconfigure;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Derives the defaults for {@code peekaboot.enabled}, {@code peekaboot.dev-toolbar} and
 * {@code peekaboot.storage.enabled} from the launch context (on only when running locally
 * in an IDE or via spring-boot:run/bootRun) and applies Peekaboot's defaults at the lowest
 * precedence, so any application property wins - {@code SpringApplication.setDefaultProperties}
 * included. An explicit setting for any of the three always overrides the detection.
 *
 * <p>All defaults live in yml resources. {@code peekaboot-no-push-defaults.yml} is applied
 * unconditionally, so the starter never pushes telemetry anywhere unless the application
 * explicitly opts in. The observability defaults in {@code peekaboot-defaults.yml} apply only
 * when Peekaboot resolves enabled in a servlet web application - everything that would read
 * them (the dashboard, the filters, the trace store) is servlet-only.
 * {@code peekaboot-dev-toolbar-defaults.yml} applies only when the dev toolbar resolves on;
 * it shortens the span export delay so a trace is readable while the developer is still
 * looking at the page.
 */
public class PeekabootDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "peekabootDefaults";
    private static final String DETECTION_PROPERTY_SOURCE_NAME = "peekabootDetection";
    private static final String NO_PUSH_PROPERTY_SOURCE_NAME = "peekabootNoPushDefaults";
    private static final String DEV_TOOLBAR_PROPERTY_SOURCE_NAME = "peekabootDevToolbarDefaults";
    private static final String ENABLED_PROPERTY = PeekabootPropertyKeys.ENABLED;
    private static final String DEV_TOOLBAR_PROPERTY = PeekabootPropertyKeys.DEV_TOOLBAR;
    private static final String STORAGE_ENABLED_PROPERTY = PeekabootPropertyKeys.STORAGE_ENABLED;
    private static final String ENV_SHOW_VALUES_PROPERTY = "management.endpoint.env.show-values";
    private static final String CONFIGPROPS_SHOW_VALUES_PROPERTY = "management.endpoint.configprops.show-values";
    private static final String WEB_APPLICATION_TYPE_PROPERTY = "spring.main.web-application-type";
    private static final String DEFAULTS_RESOURCE = "peekaboot-defaults.yml";
    private static final String NO_PUSH_DEFAULTS_RESOURCE = "peekaboot-no-push-defaults.yml";
    private static final String DEV_TOOLBAR_DEFAULTS_RESOURCE = "peekaboot-dev-toolbar-defaults.yml";

    // ProperLogger: deliberately an instance field - post-processors run before the
    // logging system is initialized, so Spring Boot hands each instance a DeferredLog
    @SuppressWarnings("PMD.ProperLogger")
    private final Log log;

    public PeekabootDefaultsEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(PeekabootDefaultsEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean localDevelopment = localDevelopment();
        boolean enabled = environment.getProperty(ENABLED_PROPERTY, Boolean.class, localDevelopment);
        boolean servlet = webApplicationType(environment, application) == WebApplicationType.SERVLET;

        // The toolbar, persistence and actuator value visibility follow the launch context
        // rather than peekaboot.enabled, so switching Peekaboot on deliberately in a shared
        // environment neither injects a toolbar into every page, nor writes files into that
        // host's home directory, nor widens the application's own /actuator/env.
        Map<String, Object> detected = new HashMap<>();
        detected.put(ENABLED_PROPERTY, localDevelopment);
        detected.put(DEV_TOOLBAR_PROPERTY, localDevelopment);
        detected.put(STORAGE_ENABLED_PROPERTY, localDevelopment);
        if (localDevelopment && enabled && servlet) {
            // Absent rather than an explicit "never" off-local: Peekaboot must not pin Spring's
            // own default into an application that is not using it. Servlet-gated like the
            // defaults yml, because the dashboard is the only reader of the widened values.
            detected.put(ENV_SHOW_VALUES_PROPERTY, "always");
            detected.put(CONFIGPROPS_SHOW_VALUES_PROPERTY, "always");
        }
        contribute(environment, new MapPropertySource(DETECTION_PROPERTY_SOURCE_NAME, detected));
        log.debug("Local development " + (localDevelopment ? "detected" : "not detected") + " - peekaboot, the"
                + " dev toolbar and storage " + (localDevelopment ? "enabled" : "disabled") + " by default");

        applyDefaults(environment, NO_PUSH_PROPERTY_SOURCE_NAME, NO_PUSH_DEFAULTS_RESOURCE);

        if (!enabled) {
            log.debug("Peekaboot is disabled - skipping peekaboot defaults");
            return;
        }

        if (!servlet) {
            log.debug("Not a servlet web application - skipping peekaboot defaults");
            return;
        }

        applyDefaults(environment, PROPERTY_SOURCE_NAME, DEFAULTS_RESOURCE);

        // Read back rather than reusing localDevelopment: an application that sets
        // peekaboot.dev-toolbar explicitly, in either direction, decides this.
        if (!environment.getProperty(DEV_TOOLBAR_PROPERTY, Boolean.class, false)) {
            log.debug("Dev toolbar is off - skipping peekaboot dev toolbar defaults");
            return;
        }

        applyDefaults(environment, DEV_TOOLBAR_PROPERTY_SOURCE_NAME, DEV_TOOLBAR_DEFAULTS_RESOURCE);
    }

    /** Overridable for tests: real detection reads the launch context of the current thread. */
    boolean localDevelopment() {
        return LocalDevDetector.isLocalDevelopment(Thread.currentThread());
    }

    /**
     * Boot binds {@code spring.main.*} onto the application only after the post-processors
     * have run, so the deduced type is all the application object knows at this point.
     */
    private static WebApplicationType webApplicationType(
            ConfigurableEnvironment environment, SpringApplication application) {
        return Binder.get(environment)
                .bind(WEB_APPLICATION_TYPE_PROPERTY, WebApplicationType.class)
                .orElse(application.getWebApplicationType());
    }

    private void applyDefaults(ConfigurableEnvironment environment, String propertySourceName, String resourceName) {
        Resource resource = new ClassPathResource(resourceName);
        if (!resource.exists()) {
            log.warn("Peekaboot defaults resource not found: " + resourceName);
            return;
        }

        try {
            contribute(environment, loadYaml(propertySourceName, resource, resourceName));
            log.debug("Loaded peekaboot defaults from " + resourceName);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load peekaboot defaults from " + resourceName, e);
        }
    }

    private EnumerablePropertySource<?> loadYaml(String propertySourceName, Resource resource, String resourceName)
            throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> propertySources = loader.load(propertySourceName, resource);
        if (propertySources.isEmpty()) {
            throw new IllegalStateException("No property sources loaded from " + resourceName);
        }
        // the yml loader yields map-backed sources, which contribute() has to enumerate
        return (EnumerablePropertySource<?>) propertySources.getFirst();
    }

    /**
     * Boot moves {@code defaultProperties} ({@code SpringApplication.setDefaultProperties})
     * below every other source once the post-processors have run, so a source appended after
     * it would outrank the application's own defaults. Peekaboot's entries go inside that
     * source instead, underneath the application's, which win on overlap.
     */
    private static void contribute(ConfigurableEnvironment environment, EnumerablePropertySource<?> propertySource) {
        MutablePropertySources sources = environment.getPropertySources();
        if (!(sources.get(DefaultPropertiesPropertySource.NAME)
                instanceof EnumerablePropertySource<?> applicationDefaults)) {
            sources.addLast(propertySource);
            return;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        copyInto(merged, propertySource);
        copyInto(merged, applicationDefaults);
        sources.replace(DefaultPropertiesPropertySource.NAME, new DefaultPropertiesPropertySource(merged));
    }

    private static void copyInto(Map<String, Object> target, EnumerablePropertySource<?> source) {
        for (String name : source.getPropertyNames()) {
            target.put(name, source.getProperty(name));
        }
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE;
    }
}

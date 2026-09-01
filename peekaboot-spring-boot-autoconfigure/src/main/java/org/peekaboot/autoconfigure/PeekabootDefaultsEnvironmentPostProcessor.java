package org.peekaboot.autoconfigure;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Derives the defaults for {@code peekaboot.enabled}, {@code peekaboot.dev-toolbar} and
 * {@code peekaboot.storage.enabled} from the launch context (on only when running locally
 * in an IDE or via spring-boot:run/bootRun) and applies Peekaboot's defaults (lowest
 * precedence, so any application property wins). An explicit setting for any of the three
 * always overrides the detection.
 * <p>
 * All defaults live in yml resources: {@code peekaboot-no-push-defaults.yml}
 * is applied unconditionally so the starter never pushes telemetry anywhere
 * unless the application explicitly opts in, the observability defaults in
 * {@code peekaboot-defaults.yml} are skipped entirely when Peekaboot ends up
 * disabled or the application is not a servlet web application (everything
 * that would read them - the dashboard, the filters, the trace store - is
 * servlet-only), and {@code peekaboot-dev-toolbar-defaults.yml} is applied only
 * when the dev toolbar resolves on, shortening the span export delay so a
 * trace is readable while the developer is still looking at the page.
 */
public class PeekabootDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "peekabootDefaults";
    private static final String DETECTION_PROPERTY_SOURCE_NAME = "peekabootDetection";
    private static final String NO_PUSH_PROPERTY_SOURCE_NAME = "peekabootNoPushDefaults";
    private static final String DEV_TOOLBAR_PROPERTY_SOURCE_NAME = "peekabootDevToolbarDefaults";
    private static final String ENABLED_PROPERTY = PeekabootPropertyKeys.ENABLED;
    private static final String DEV_TOOLBAR_PROPERTY = "peekaboot.dev-toolbar";
    private static final String STORAGE_ENABLED_PROPERTY = "peekaboot.storage.enabled";
    private static final String ENV_SHOW_VALUES_PROPERTY = "management.endpoint.env.show-values";
    private static final String CONFIGPROPS_SHOW_VALUES_PROPERTY = "management.endpoint.configprops.show-values";
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
        // lowest precedence: any explicit setting in the application wins over the detection.
        // Peekaboot's activation, the toolbar, persistence and actuator value visibility all
        // follow the launch context rather than peekaboot.enabled, so switching Peekaboot on
        // deliberately in a shared environment neither injects a toolbar into every page, nor
        // writes files into that host's home directory, nor widens the application's own
        // /actuator/env.
        Map<String, Object> detected = new HashMap<>();
        detected.put(ENABLED_PROPERTY, localDevelopment);
        detected.put(DEV_TOOLBAR_PROPERTY, localDevelopment);
        detected.put(STORAGE_ENABLED_PROPERTY, localDevelopment);
        if (localDevelopment && application.getWebApplicationType() == WebApplicationType.SERVLET) {
            // Absent rather than an explicit "never" off-local: Peekaboot must not pin Spring's
            // own default into an application that is not using it. Servlet-gated like the
            // defaults yml, because the dashboard is the only reader of the widened values.
            detected.put(ENV_SHOW_VALUES_PROPERTY, "always");
            detected.put(CONFIGPROPS_SHOW_VALUES_PROPERTY, "always");
        }
        environment.getPropertySources().addLast(new MapPropertySource(DETECTION_PROPERTY_SOURCE_NAME, detected));
        log.debug("Local development " + (localDevelopment ? "detected" : "not detected") + " - peekaboot, the"
                + " dev toolbar and storage " + (localDevelopment ? "enabled" : "disabled") + " by default");

        applyDefaults(environment, NO_PUSH_PROPERTY_SOURCE_NAME, NO_PUSH_DEFAULTS_RESOURCE);

        if (!environment.getProperty(ENABLED_PROPERTY, Boolean.class, false)) {
            log.debug("Peekaboot is disabled - skipping peekaboot defaults");
            return;
        }

        if (application.getWebApplicationType() != WebApplicationType.SERVLET) {
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

    private void applyDefaults(ConfigurableEnvironment environment, String propertySourceName, String resourceName) {
        Resource resource = new ClassPathResource(resourceName);
        if (!resource.exists()) {
            log.warn("Peekaboot defaults resource not found: " + resourceName);
            return;
        }

        try {
            PropertySource<?> propertySource = loadYaml(propertySourceName, resource, resourceName);
            environment.getPropertySources().addLast(propertySource);
            log.debug("Loaded peekaboot defaults from " + resourceName);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load peekaboot defaults from " + resourceName, e);
        }
    }

    private PropertySource<?> loadYaml(String propertySourceName, Resource resource, String resourceName)
            throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> propertySources = loader.load(propertySourceName, resource);
        if (propertySources.isEmpty()) {
            throw new IllegalStateException("No property sources loaded from " + resourceName);
        }
        return propertySources.get(0);
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE;
    }
}

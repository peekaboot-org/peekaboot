package org.peekaboot.autoconfigure;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Derives the default for {@code peekaboot.enabled} from the launch context
 * (on only when running locally in an IDE or via spring-boot:run/bootRun) and
 * applies Peekaboot's defaults (lowest precedence, so any application property
 * wins). An explicit {@code peekaboot.enabled} setting always overrides the
 * detection.
 * <p>
 * All defaults live in yml resources: {@code peekaboot-no-push-defaults.yml}
 * is applied unconditionally so the starter never pushes telemetry anywhere
 * unless the application explicitly opts in, while the observability defaults
 * in {@code peekaboot-defaults.yml} are skipped entirely when Peekaboot ends
 * up disabled.
 */
public class PeekabootDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "peekabootDefaults";
    private static final String DETECTION_PROPERTY_SOURCE_NAME = "peekabootDetection";
    private static final String NO_PUSH_PROPERTY_SOURCE_NAME = "peekabootNoPushDefaults";
    private static final String ENABLED_PROPERTY = "peekaboot.enabled";
    private static final String DEFAULTS_RESOURCE = "peekaboot-defaults.yml";
    private static final String NO_PUSH_DEFAULTS_RESOURCE = "peekaboot-no-push-defaults.yml";

    // ProperLogger: deliberately an instance field - post-processors run before the
    // logging system is initialized, so Spring Boot hands each instance a DeferredLog
    @SuppressWarnings("PMD.ProperLogger")
    private final Log log;

    public PeekabootDefaultsEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        // post-processors run before the logging system is initialized
        this.log = logFactory.getLog(PeekabootDefaultsEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean localDevelopment = localDevelopment();
        // lowest precedence: any explicit peekaboot.enabled setting wins over the detection
        environment
                .getPropertySources()
                .addLast(new MapPropertySource(
                        DETECTION_PROPERTY_SOURCE_NAME, Map.of(ENABLED_PROPERTY, localDevelopment)));
        log.debug("Local development " + (localDevelopment ? "detected" : "not detected") + " - peekaboot "
                + (localDevelopment ? "enabled" : "disabled") + " by default");

        applyDefaults(environment, NO_PUSH_PROPERTY_SOURCE_NAME, NO_PUSH_DEFAULTS_RESOURCE);

        if (!environment.getProperty(ENABLED_PROPERTY, Boolean.class, false)) {
            log.debug("Peekaboot is disabled - skipping peekaboot defaults");
            return;
        }

        applyDefaults(environment, PROPERTY_SOURCE_NAME, DEFAULTS_RESOURCE);
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

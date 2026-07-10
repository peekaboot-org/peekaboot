package net.osslabz.peekaboot.autoconfigure;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.List;

/**
 * Applies Peekaboot's observability defaults (lowest precedence, so any
 * application property wins). Skipped entirely when peekaboot.enabled=false.
 */
public class PeekabootDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "peekabootDefaults";
    private static final String DEFAULTS_RESOURCE = "peekaboot-defaults.yml";

    private final Log log;

    public PeekabootDefaultsEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        // post-processors run before the logging system is initialized
        this.log = logFactory.getLog(PeekabootDefaultsEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("peekaboot.enabled", Boolean.class, true)) {
            log.debug("Peekaboot is disabled - skipping peekaboot defaults");
            return;
        }

        Resource resource = new ClassPathResource(DEFAULTS_RESOURCE);
        if (!resource.exists()) {
            log.warn("Peekaboot defaults resource not found: " + DEFAULTS_RESOURCE);
            return;
        }

        try {
            PropertySource<?> propertySource = loadYaml(resource);
            environment.getPropertySources().addLast(propertySource);
            log.debug("Loaded peekaboot defaults from " + DEFAULTS_RESOURCE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load peekaboot defaults from " + DEFAULTS_RESOURCE, e);
        }
    }

    private PropertySource<?> loadYaml(Resource resource) throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> propertySources = loader.load(PROPERTY_SOURCE_NAME, resource);
        if (propertySources.isEmpty()) {
            throw new IllegalStateException("No property sources loaded from " + DEFAULTS_RESOURCE);
        }
        return propertySources.get(0);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}

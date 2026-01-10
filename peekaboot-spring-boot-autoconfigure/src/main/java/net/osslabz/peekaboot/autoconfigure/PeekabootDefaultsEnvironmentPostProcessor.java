package net.osslabz.peekaboot.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.List;

public class PeekabootDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(PeekabootDefaultsEnvironmentPostProcessor.class);
    private static final String PROPERTY_SOURCE_NAME = "peekabootDefaults";
    private static final String DEFAULTS_RESOURCE = "peekaboot-defaults.yml";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Resource resource = new ClassPathResource(DEFAULTS_RESOURCE);
        if (!resource.exists()) {
            LOG.warn("Peekaboot defaults resource not found: {}", DEFAULTS_RESOURCE);
            return;
        }

        try {
            PropertySource<?> propertySource = loadYaml(resource);
            environment.getPropertySources().addLast(propertySource);
            LOG.debug("Loaded peekaboot defaults from {}", DEFAULTS_RESOURCE);
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

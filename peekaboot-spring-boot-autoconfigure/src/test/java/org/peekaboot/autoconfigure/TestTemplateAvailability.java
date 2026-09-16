package org.peekaboot.autoconfigure;

import org.springframework.boot.autoconfigure.template.TemplateAvailabilityProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;

/** Registered through Boot's own SPI so {@link ErrorPageAutoConfigurationTest} can simulate an application's own error template. */
public class TestTemplateAvailability implements TemplateAvailabilityProvider {

    @Override
    public boolean isTemplateAvailable(
            String view, Environment environment, ClassLoader classLoader, ResourceLoader resourceLoader) {
        return "error".equals(view) && environment.getProperty("test.error-template", Boolean.class, false);
    }
}

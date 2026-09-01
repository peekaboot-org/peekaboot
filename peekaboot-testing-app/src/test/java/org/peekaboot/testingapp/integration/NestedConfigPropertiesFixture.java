package org.peekaboot.testingapp.integration;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A {@code @ConfigurationProperties} fixture with a nested {@code Map<String, Object>}
 * value, purely so {@link ActuatorMaskingIT} can prove {@code ConfigMapper}
 * masks a sensitive key nested inside a property tree - the shape a
 * real {@code spring.security.oauth2.client.registration} bean has. That starter isn't on
 * this app's classpath, so this fixture reproduces the same nesting without it: see
 * {@code application-test.yml}'s {@code nested-fixture} block for the bound values.
 */
@ConfigurationProperties(prefix = "nested-fixture")
public record NestedConfigPropertiesFixture(Map<String, Object> registration) {}

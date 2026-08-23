package org.peekaboot.testingapp.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * A {@code @ConfigurationProperties} fixture with a nested {@code Map<String, Object>}
 * value, purely so {@link ActuatorMaskingIntegrationTest} can prove {@code ConfigMapper}
 * masks a sensitive key nested inside a property tree (Known Defect C1) - the shape a
 * real {@code spring.security.oauth2.client.registration} bean has. That starter isn't on
 * this app's classpath, so this fixture reproduces the same nesting without it: see
 * {@code application-test.yml}'s {@code nested-fixture} block for the bound values.
 */
@ConfigurationProperties(prefix = "nested-fixture")
public record NestedConfigPropertiesFixture(Map<String, Object> registration) {
}

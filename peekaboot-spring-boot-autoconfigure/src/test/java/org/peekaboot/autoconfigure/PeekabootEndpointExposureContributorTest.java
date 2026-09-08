package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.env.EnvironmentEndpointAutoConfiguration;
import org.springframework.boot.actuate.env.EnvironmentEndpoint;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Peekaboot invokes the health endpoint in-process, so its bean must be created even when
 * the application exposes nothing over the web or JMX. The contributor registered in
 * spring.factories makes @ConditionalOnAvailableEndpoint match for health while peekaboot is
 * enabled, and for health alone.
 */
class PeekabootEndpointExposureContributorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    HealthContributorRegistryAutoConfiguration.class,
                    HealthEndpointAutoConfiguration.class,
                    EnvironmentEndpointAutoConfiguration.class));

    @Test
    void createsTheHealthEndpointBeanWithoutWebExposureWhenPeekabootEnabled() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=true", "management.endpoints.web.exposure.exclude=health")
                .run(context -> assertThat(context).hasSingleBean(HealthEndpoint.class));
    }

    /**
     * Peekaboot builds every other endpoint it reads itself, so forcing the application's
     * beans into existence would widen the application's actuator for nothing.
     */
    @Test
    void leavesEveryOtherEndpointToTheApplicationsOwnExposureSettings() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=true", "management.endpoints.web.exposure.exclude=health")
                .run(context -> assertThat(context).doesNotHaveBean(EnvironmentEndpoint.class));
    }

    @Test
    void doesNotCreateTheHealthEndpointBeanWhenPeekabootDisabled() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=false", "management.endpoints.web.exposure.exclude=health")
                .run(context -> assertThat(context).doesNotHaveBean(HealthEndpoint.class));
    }

    @Test
    void normalWebExposureStillWorksWhenPeekabootDisabled() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=false", "management.endpoints.web.exposure.include=env")
                .run(context -> assertThat(context).hasSingleBean(EnvironmentEndpoint.class));
    }
}

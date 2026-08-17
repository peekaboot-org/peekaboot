package org.peekaboot.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.context.properties.ConfigurationPropertiesReportEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.env.EnvironmentEndpointAutoConfiguration;
import org.springframework.boot.actuate.context.properties.ConfigurationPropertiesReportEndpoint;
import org.springframework.boot.actuate.env.EnvironmentEndpoint;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Peekaboot invokes actuator endpoints in-process, so the endpoint beans must be
 * created even when no endpoint is exposed over the web or JMX. The contributor
 * registered in spring.factories makes @ConditionalOnAvailableEndpoint match for
 * any endpoint while peekaboot is enabled.
 */
class PeekabootEndpointExposureContributorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    EnvironmentEndpointAutoConfiguration.class,
                    ConfigurationPropertiesReportEndpointAutoConfiguration.class));

    @Test
    void createsEndpointBeansWithoutWebExposureWhenPeekabootEnabled() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(EnvironmentEndpoint.class);
                    assertThat(context).hasSingleBean(ConfigurationPropertiesReportEndpoint.class);
                });
    }

    @Test
    void doesNotCreateEndpointBeansWhenPeekabootDisabled() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(EnvironmentEndpoint.class);
                    assertThat(context).doesNotHaveBean(ConfigurationPropertiesReportEndpoint.class);
                });
    }

    @Test
    void doesNotCreateEndpointBeansWhenPeekabootPropertyMissing() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(EnvironmentEndpoint.class);
            assertThat(context).doesNotHaveBean(ConfigurationPropertiesReportEndpoint.class);
        });
    }

    @Test
    void normalWebExposureStillWorksWhenPeekabootDisabled() {
        contextRunner
                .withPropertyValues(
                        "peekaboot.enabled=false",
                        "management.endpoints.web.exposure.include=env")
                .run(context -> assertThat(context).hasSingleBean(EnvironmentEndpoint.class));
    }
}

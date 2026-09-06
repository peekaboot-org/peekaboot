package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.actuator.InsightsSource;
import org.springframework.boot.actuate.context.properties.ConfigurationPropertiesReportEndpoint.ConfigurationPropertiesDescriptor;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.env.EnvironmentEndpoint.EnvironmentDescriptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.DefaultHealthContributorRegistry;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ActuatorSourcesAutoConfigurationTest {

    /**
     * {@code LoggingSystem} is a bean only because {@code LoggingApplicationListener} puts it
     * there during {@code SpringApplication} startup; {@link WebApplicationContextRunner}
     * never runs that listener, so the loggers source needs a stand-in for what every real
     * Peekaboot-enabled application already has.
     */
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ActuatorSourcesAutoConfiguration.class))
            .withBean(LoggingSystem.class, () -> LoggingSystem.get(getClass().getClassLoader()))
            .withPropertyValues("peekaboot.enabled=true");

    private static Object read(ApplicationContext context, String id) {
        return context.getBeansOfType(InsightsSource.class).values().stream()
                .filter(source -> source.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no source with id " + id))
                .read()
                .get();
    }

    /** The whole point of owning the endpoint: the application's setting must not reach it. */
    @Test
    void envSourceReadsRealValuesWhileTheApplicationHidesThemFromItsOwnActuator() {
        contextRunner
                .withPropertyValues("management.endpoint.env.show-values=never", "fixture.value=readable")
                .run(context -> {
                    Object descriptor = read(context, "env");

                    assertThat(descriptor)
                            .isInstanceOfSatisfying(
                                    EnvironmentDescriptor.class,
                                    env -> assertThat(env.getPropertySources())
                                            .anySatisfy(source -> assertThat(source.getProperties())
                                                    .extractingByKey("fixture.value")
                                                    .satisfies(value -> assertThat(value.getValue())
                                                            .isEqualTo("readable"))));
                });
    }

    @Test
    void configpropsSourceReadsRealValuesWhileTheApplicationHidesThemFromItsOwnActuator() {
        contextRunner
                .withPropertyValues("management.endpoint.configprops.show-values=never")
                .run(context ->
                        assertThat(read(context, "configprops")).isInstanceOf(ConfigurationPropertiesDescriptor.class));
    }

    /**
     * Answers like Spring's default group does for an anonymous caller of
     * {@code /actuator/health}: neither components nor details. A descriptor that still
     * carries them can only have come from the endpoint bean's own {@code health()}.
     */
    @Test
    void healthSourceReadsTheEndpointBeanWithComponentsAndDetails() {
        contextRunner.withUserConfiguration(HealthEndpointConfig.class).run(context -> {
            Object descriptor = read(context, "health");

            assertThat(descriptor).isInstanceOfSatisfying(CompositeHealthDescriptor.class, health -> {
                assertThat(health.getStatus()).isEqualTo(Status.UP);
                assertThat(health.getComponents().get("db"))
                        .isInstanceOfSatisfying(
                                IndicatedHealthDescriptor.class,
                                db -> assertThat(db.getDetails()).containsEntry("database", "H2"));
            });
        });
    }

    @Test
    void healthSourceReadsNullWhenTheApplicationHasNoHealthEndpointBean() {
        contextRunner.run(context -> assertThat(read(context, "health")).isNull());
    }

    /** The sources with no visibility gate of their own: present, and reading something. */
    @ParameterizedTest
    @ValueSource(strings = {"spring", "info", "loggers", "scheduledtasks"})
    void readsEverySourceThatNeedsNoBackingBean(String id) {
        contextRunner.run(context -> assertThat(read(context, id)).isNotNull());
    }

    @Test
    void contributesNoSourcesWhenPeekabootIsDisabled() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(InsightsSource.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class HealthEndpointConfig {
        @Bean
        HealthEndpoint healthEndpoint() {
            DefaultHealthContributorRegistry registry = new DefaultHealthContributorRegistry();
            registry.registerContributor("db", (HealthIndicator)
                    () -> Health.up().withDetail("database", "H2").build());
            return new HealthEndpoint(registry, null, HealthEndpointGroups.of(new HidingGroup(), Map.of()), null);
        }
    }

    private static final class HidingGroup implements HealthEndpointGroup {

        @Override
        public boolean isMember(String name) {
            return true;
        }

        @Override
        public boolean showComponents(SecurityContext securityContext) {
            return false;
        }

        @Override
        public boolean showDetails(SecurityContext securityContext) {
            return false;
        }

        @Override
        public StatusAggregator getStatusAggregator() {
            return StatusAggregator.getDefault();
        }

        @Override
        public HttpCodeStatusMapper getHttpCodeStatusMapper() {
            return HttpCodeStatusMapper.getDefault();
        }

        @Override
        public AdditionalHealthEndpointPath getAdditionalPath() {
            return null;
        }
    }
}

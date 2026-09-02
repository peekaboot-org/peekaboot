package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.invoke.OperationInvokerAdvisor;
import org.springframework.boot.actuate.endpoint.invoke.convert.ConversionServiceParameterValueMapper;
import org.springframework.boot.actuate.endpoint.web.AdditionalPathsMapper;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.boot.actuate.endpoint.web.PathMapper;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class PeekabootActuatorServiceTest {

    @Endpoint(id = "info")
    static class InfoEndpointStub {
        @ReadOperation
        public Map<String, Object> info() {
            return Map.of("app", "demo");
        }
    }

    @Endpoint(id = "beans")
    static class BeansEndpointStub {
        @ReadOperation
        public Map<String, Object> beans() {
            return Map.of();
        }
    }

    @Endpoint(id = "loggers")
    static class FailingLoggersEndpoint {
        @ReadOperation
        public Map<String, Object> loggers() {
            throw new IllegalStateException("boom");
        }
    }

    @Configuration
    static class HealthEndpointConfig {
        @Bean
        HealthEndpoint healthEndpoint() {
            DefaultHealthContributorRegistry registry = new DefaultHealthContributorRegistry();
            registry.registerContributor("db", (HealthIndicator)
                    () -> Health.up().withDetail("database", "H2").build());
            return new HealthEndpoint(registry, null, HealthEndpointGroups.of(new HidingGroup(), Map.of()), null);
        }
    }

    /**
     * Answers like Spring's default group does for an anonymous caller of
     * {@code /actuator/health}: neither components nor details. The endpoint bean's own
     * {@code health()} ignores both answers, which is exactly why the service reads it.
     */
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

    @Test
    void getInsightsData_collectsTheInsightsEndpointsAndTheSpringVersions() {
        try (var context = new AnnotationConfigApplicationContext(InfoEndpointStub.class, BeansEndpointStub.class)) {
            Map<String, Object> data = service(context).getInsightsData();

            assertThat(data)
                    .containsKey("spring")
                    .containsEntry("info", Map.of("app", "demo"))
                    .doesNotContainKey("beans")
                    .doesNotContainKey("health");
        }
    }

    /**
     * Through the discovered web operation the hiding group would strip the components
     * and details, so a bare descriptor that still carries them can only have come from
     * the endpoint bean's own {@code health()}.
     */
    @Test
    void getInsightsData_readsHealthFromTheEndpointBeanWithComponentsAndDetails() {
        try (var context = new AnnotationConfigApplicationContext(HealthEndpointConfig.class);
                LogCapture capture = LogCapture.attach(PeekabootActuatorService.class)) {
            Map<String, Object> data = service(context).getInsightsData();

            assertThat(data.get("health")).isInstanceOfSatisfying(CompositeHealthDescriptor.class, health -> {
                assertThat(health.getStatus()).isEqualTo(Status.UP);
                assertThat(health.getComponents()).containsOnlyKeys("db");
                assertThat(health.getComponents().get("db"))
                        .isInstanceOfSatisfying(
                                IndicatedHealthDescriptor.class,
                                db -> assertThat(db.getDetails()).containsEntry("database", "H2"));
            });
            assertThat(capture.appender().list).isEmpty();
        }
    }

    @Test
    void getInsightsData_logsAndLeavesOutAnEndpointThatFails() {
        try (var context =
                        new AnnotationConfigApplicationContext(FailingLoggersEndpoint.class, InfoEndpointStub.class);
                LogCapture capture = LogCapture.attach(PeekabootActuatorService.class)) {
            Map<String, Object> data = service(context).getInsightsData();

            assertThat(data).doesNotContainKey("loggers").containsKey("info");
            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Actuator endpoint 'loggers' failed: java.lang.IllegalStateException: boom");
            });
        }
    }

    private static PeekabootActuatorService service(ApplicationContext context) {
        return new PeekabootActuatorService(
                context,
                context.getBeanProvider(HealthEndpoint.class),
                new ConversionServiceParameterValueMapper(),
                EndpointMediaTypes.DEFAULT,
                context.getBeanProvider(PathMapper.class),
                context.getBeanProvider(AdditionalPathsMapper.class),
                context.getBeanProvider(OperationInvokerAdvisor.class));
    }
}

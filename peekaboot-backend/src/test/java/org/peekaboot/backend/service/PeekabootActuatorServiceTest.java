package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.testsupport.LogCapture;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.invoke.OperationInvokerAdvisor;
import org.springframework.boot.actuate.endpoint.invoke.convert.ConversionServiceParameterValueMapper;
import org.springframework.boot.actuate.endpoint.web.AdditionalPathsMapper;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.boot.actuate.endpoint.web.PathMapper;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

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

    @Endpoint(id = "health")
    static class FailingHealthEndpoint {
        @ReadOperation
        public Map<String, Object> health() {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    void getInsightsData_collectsTheInsightsEndpointsAndTheSpringVersions() {
        try (var context = new AnnotationConfigApplicationContext(InfoEndpointStub.class, BeansEndpointStub.class)) {
            Map<String, Object> data = service(context).getInsightsData();

            assertThat(data)
                    .containsKey("spring")
                    .containsEntry("info", Map.of("app", "demo"))
                    .doesNotContainKey("beans");
        }
    }

    @Test
    void getInsightsData_logsAndLeavesOutAnEndpointThatFails() {
        try (var context = new AnnotationConfigApplicationContext(FailingHealthEndpoint.class, InfoEndpointStub.class);
                LogCapture capture = LogCapture.attach(PeekabootActuatorService.class)) {
            Map<String, Object> data = service(context).getInsightsData();

            assertThat(data).doesNotContainKey("health").containsKey("info");
            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Actuator endpoint 'health' failed: java.lang.IllegalStateException: boom");
            });
        }
    }

    private static PeekabootActuatorService service(ApplicationContext context) {
        return new PeekabootActuatorService(
                context,
                new ConversionServiceParameterValueMapper(),
                EndpointMediaTypes.DEFAULT,
                context.getBeanProvider(PathMapper.class),
                context.getBeanProvider(AdditionalPathsMapper.class),
                context.getBeanProvider(OperationInvokerAdvisor.class));
    }
}

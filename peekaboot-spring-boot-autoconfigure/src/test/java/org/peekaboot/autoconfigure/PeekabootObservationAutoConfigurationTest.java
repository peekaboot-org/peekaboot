package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootRequestObservationPredicate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The predicate has to reach the application's real registry, where Boot applies every predicate bean. */
class PeekabootObservationAutoConfigurationTest {

    private static final ObservationHandler<Observation.Context> ANY_HANDLER = context -> true;

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ObservationAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class,
                    PeekabootPathsAutoConfiguration.class,
                    PeekabootObservationAutoConfiguration.class))
            // without a handler the registry is a no-op, and every observation would read as rejected
            .withBean("anyHandler", ObservationHandler.class, () -> ANY_HANDLER)
            .withPropertyValues("peekaboot.enabled=true");

    @Test
    void theApplicationsRegistryNoLongerObservesADashboardRequest() {
        contextRunner.run(context -> {
            ObservationRegistry registry = context.getBean(ObservationRegistry.class);

            assertThat(serverObservation(registry, "/peekaboot/api/features").isNoop())
                    .isTrue();
            assertThat(serverObservation(registry, "/persons").isNoop()).isFalse();
        });
    }

    @Test
    void staysOutWhilePeekabootIsOff() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PeekabootRequestObservationPredicate.class));
    }

    private static Observation serverObservation(ObservationRegistry registry, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return Observation.createNotStarted(
                "http.server.requests",
                () -> new ServerRequestObservationContext(request, new MockHttpServletResponse()),
                registry);
    }
}

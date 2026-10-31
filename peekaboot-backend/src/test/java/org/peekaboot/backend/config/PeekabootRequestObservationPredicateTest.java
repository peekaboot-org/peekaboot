package org.peekaboot.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Requests.get;

import io.micrometer.observation.Observation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class PeekabootRequestObservationPredicateTest {

    private final PeekabootRequestObservationPredicate predicate =
            new PeekabootRequestObservationPredicate(PeekabootPaths.defaults());

    @AfterEach
    void forgetTheRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void rejectsTheServerObservationOfADashboardRequest() {
        assertThat(predicate.test("http.server.requests", serverContext(get("/peekaboot/api/features"))))
                .isFalse();
    }

    @Test
    void keepsTheServerObservationOfAnApplicationRequest() {
        assertThat(predicate.test("http.server.requests", serverContext(get("/persons"))))
                .isTrue();
    }

    /** Only the dashboard's own path: the actuator and the static assets are the application's traffic to measure. */
    @Test
    void keepsTheServerObservationOfAnActuatorRequest() {
        assertThat(predicate.test("http.server.requests", serverContext(get("/actuator/health"))))
                .isTrue();
    }

    @Test
    void rejectsWorkStartedWhileADashboardRequestIsServed() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(get("/peekaboot/api/actuator/all/insights")));

        assertThat(predicate.test("jdbc.connection", new Observation.Context())).isFalse();
    }

    @Test
    void keepsWorkStartedWhileAnApplicationRequestIsServed() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(get("/persons")));

        assertThat(predicate.test("jdbc.connection", new Observation.Context())).isTrue();
    }

    @Test
    void keepsWorkStartedOffAnyRequest() {
        assertThat(predicate.test("tasks.scheduled.execution", new Observation.Context()))
                .isTrue();
    }

    @Test
    void judgesADashboardMovedBehindTheDispatcherServletsPrefix() {
        var moved = new PeekabootRequestObservationPredicate(new PeekabootPaths("/actuator", "", "/app"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/peekaboot/api/features");
        request.setServletPath("/app");
        request.setPathInfo("/peekaboot/api/features");

        assertThat(moved.test("http.server.requests", serverContext(request))).isFalse();
    }

    private static ServerRequestObservationContext serverContext(MockHttpServletRequest request) {
        return new ServerRequestObservationContext(request, new MockHttpServletResponse());
    }
}

package org.peekaboot.backend.tracing.interceptor;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.ModelAndView;

class TracingHandlerInterceptorTest {

    private TestObservationRegistry observationRegistry;
    private TracingHandlerInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        observationRegistry = TestObservationRegistry.create();
        interceptor = new TracingHandlerInterceptor(observationRegistry);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @org.junit.jupiter.api.AfterEach
    void closeLeakedScopes() {
        // tests that call preHandle without a matching completion leave the
        // thread-local scope open; drain it so tests stay isolated
        Observation.Scope scope;
        while ((scope = observationRegistry.getCurrentObservationScope()) != null) {
            scope.close();
        }
    }

    @Test
    void preHandle_shouldStartHandlerObservation() {
        request.setRequestURI("/api/users");
        Object handler = new Object();

        interceptor.preHandle(request, response, handler);

        assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("spring.handler")
                .that()
                .hasLowCardinalityKeyValue("handler.type", "Object");
    }

    @Test
    void preHandle_shouldSkipExcludedPaths() {
        request.setRequestURI("/peekaboot/api/traces");
        Object handler = new Object();

        interceptor.preHandle(request, response, handler);

        assertThat(observationRegistry).hasNumberOfObservationsEqualTo(0);
    }

    @Test
    void preHandle_shouldSkipActuatorPaths() {
        request.setRequestURI("/actuator/health");
        Object handler = new Object();

        interceptor.preHandle(request, response, handler);

        assertThat(observationRegistry).hasNumberOfObservationsEqualTo(0);
    }

    @Test
    void postHandle_shouldStopHandlerAndStartViewObservation() {
        request.setRequestURI("/api/users");
        Object handler = new Object();
        ModelAndView modelAndView = new ModelAndView("users/list");

        interceptor.preHandle(request, response, handler);
        interceptor.postHandle(request, response, handler, modelAndView);

        assertThat(observationRegistry)
                .hasNumberOfObservationsWithNameEqualTo("spring.handler", 1)
                .hasNumberOfObservationsWithNameEqualTo("spring.view.render", 1);

        assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("spring.view.render")
                .that()
                .hasHighCardinalityKeyValue("view.name", "users/list");
    }

    @Test
    void postHandle_shouldNotStartViewObservation_whenNoView() {
        request.setRequestURI("/api/users");
        Object handler = new Object();

        interceptor.preHandle(request, response, handler);
        interceptor.postHandle(request, response, handler, null);

        assertThat(observationRegistry)
                .hasNumberOfObservationsWithNameEqualTo("spring.handler", 1)
                .hasNumberOfObservationsWithNameEqualTo("spring.view.render", 0);
    }

    @Test
    void afterCompletion_shouldStopViewObservation() {
        request.setRequestURI("/api/users");
        Object handler = new Object();
        ModelAndView modelAndView = new ModelAndView("users/list");

        interceptor.preHandle(request, response, handler);
        interceptor.postHandle(request, response, handler, modelAndView);
        interceptor.afterCompletion(request, response, handler, null);

        assertThat(observationRegistry)
                .hasNumberOfObservationsWithNameEqualTo("spring.handler", 1)
                .hasNumberOfObservationsWithNameEqualTo("spring.view.render", 1);
    }

    @Test
    void afterCompletion_shouldHandleExceptionInHandler() {
        request.setRequestURI("/api/users");
        Object handler = new Object();
        RuntimeException exception = new RuntimeException("Handler error");

        interceptor.preHandle(request, response, handler);
        // Simulate exception - postHandle is not called when exception occurs
        interceptor.afterCompletion(request, response, handler, exception);

        assertThat(observationRegistry).hasNumberOfObservationsWithNameEqualTo("spring.handler", 1);
    }

    @Test
    void preHandle_shouldResolveHandlerMethodName() throws NoSuchMethodException {
        request.setRequestURI("/api/users");

        HandlerMethod handlerMethod = mock(HandlerMethod.class);
        when(handlerMethod.getBeanType()).thenReturn((Class) TestController.class);
        when(handlerMethod.getMethod()).thenReturn(TestController.class.getMethod("getUsers"));

        interceptor.preHandle(request, response, handlerMethod);

        assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("spring.handler")
                .that()
                .hasHighCardinalityKeyValue("handler.name", "TestController.getUsers");
    }

    @Test
    void preHandle_shouldOpenScopeSoChildObservationsNestUnderHandler() {
        request.setRequestURI("/api/users");
        Object handler = new Object();

        interceptor.preHandle(request, response, handler);

        org.assertj.core.api.Assertions.assertThat(observationRegistry.getCurrentObservation())
                .as("handler observation should be current while the handler executes")
                .isNotNull();
        org.assertj.core.api.Assertions.assertThat(
                        observationRegistry.getCurrentObservation().getContext().getName())
                .isEqualTo("spring.handler");

        interceptor.postHandle(request, response, handler, null);

        org.assertj.core.api.Assertions.assertThat(observationRegistry.getCurrentObservation())
                .as("scope should be closed after postHandle")
                .isNull();
    }

    @Test
    void afterConcurrentHandlingStarted_shouldStopHandlerObservation() {
        request.setRequestURI("/api/async");
        Object handler = new Object();

        interceptor.preHandle(request, response, handler);
        interceptor.afterConcurrentHandlingStarted(request, response, handler);

        assertThat(observationRegistry).hasNumberOfObservationsWithNameEqualTo("spring.handler", 1);
        assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("spring.handler")
                .that()
                .hasBeenStopped();
        org.assertj.core.api.Assertions.assertThat(observationRegistry.getCurrentObservation())
                .isNull();
    }

    @Test
    void preHandle_onAsyncDispatch_shouldNotStartSecondObservation() {
        request.setRequestURI("/api/async");
        Object handler = new Object();

        interceptor.preHandle(request, response, handler);
        interceptor.afterConcurrentHandlingStarted(request, response, handler);

        // the container re-invokes preHandle on the ASYNC dispatch
        request.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);
        interceptor.preHandle(request, response, handler);
        interceptor.postHandle(request, response, handler, null);
        interceptor.afterCompletion(request, response, handler, null);

        assertThat(observationRegistry).hasNumberOfObservationsWithNameEqualTo("spring.handler", 1);
    }

    // Test controller for handler method resolution
    static class TestController {
        public void getUsers() {}
    }
}

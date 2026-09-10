package org.peekaboot.backend.tracing.interceptor;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.tck.TestObservationRegistry;
import jakarta.servlet.DispatcherType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

class TracingHandlerInterceptorTest {

    private TestObservationRegistry observationRegistry;
    private TracingHandlerInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    /**
     * The names of the observations that were stopped, in order. An observation the
     * interceptor starts and never stops produces no span at all, and the registry's own
     * assertions count observations whether or not they ever ended, so they cannot tell
     * that apart from a healthy one.
     */
    private final List<String> stoppedObservations = new ArrayList<>();

    @BeforeEach
    void setUp() {
        observationRegistry = TestObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                stoppedObservations.add(context.getName());
            }
        });
        interceptor = new TracingHandlerInterceptor(observationRegistry);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @AfterEach
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

    /** A handler may return a View instance rather than a name; it renders all the same. */
    @Test
    void postHandle_shouldStartViewObservationForAViewInstance() {
        request.setRequestURI("/api/users");
        Object handler = new Object();
        ModelAndView modelAndView = new ModelAndView(new RedirectView("/users"));

        interceptor.preHandle(request, response, handler);
        interceptor.postHandle(request, response, handler, modelAndView);

        assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("spring.view.render")
                .that()
                .hasHighCardinalityKeyValue("view.name", "RedirectView");
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
        assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("spring.view.render")
                .that()
                .hasBeenStopped();
    }

    /** postHandle is skipped when the handler throws; afterCompletion records the error and still stops the observation. */
    @Test
    void afterCompletion_recordsTheHandlerExceptionAndStopsTheObservation() {
        request.setRequestURI("/api/users");
        Object handler = new Object();
        RuntimeException exception = new RuntimeException("Handler error");

        interceptor.preHandle(request, response, handler);
        interceptor.afterCompletion(request, response, handler, exception);

        assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("spring.handler")
                .that()
                .hasError(exception)
                .hasBeenStopped();
    }

    @Test
    void preHandle_shouldResolveHandlerMethodName() throws NoSuchMethodException {
        request.setRequestURI("/api/users");
        HandlerMethod handlerMethod =
                new HandlerMethod(new TestController(), TestController.class.getMethod("getUsers"));

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

        assertThat(observationRegistry.getCurrentObservation())
                .as("handler observation should be current while the handler executes")
                .isNotNull();
        assertThat(observationRegistry.getCurrentObservation().getContext().getName())
                .isEqualTo("spring.handler");

        interceptor.postHandle(request, response, handler, null);

        assertThat(observationRegistry.getCurrentObservation())
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
        assertThat(observationRegistry.getCurrentObservation()).isNull();
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

    /**
     * A {@code forward:} view runs a second DispatcherServlet dispatch inside the first
     * one's view rendering, so both dispatches call every interceptor callback against the
     * same request. The outer dispatch's view observation must survive that and be stopped
     * by its own afterCompletion: an observation left open keeps its scope on the request
     * thread, and the next request that thread serves inherits the trace context.
     */
    @Test
    void nestedDispatch_shouldLeaveNoScopeOpenOnTheThread() {
        forwardThroughNestedDispatch();

        assertThat(observationRegistry.getCurrentObservationScope())
                .as("the outer dispatch's view scope must not outlive the request")
                .isNull();
    }

    @Test
    void nestedDispatch_shouldStopBothDispatchesObservations() {
        forwardThroughNestedDispatch();

        assertThat(stoppedObservations)
                .as("both dispatches must end their handler span and their view span")
                .containsExactly("spring.handler", "spring.handler", "spring.view.render", "spring.view.render");
    }

    /** The callback sequence DispatcherServlet produces for a handler that returns {@code forward:}. */
    private void forwardThroughNestedDispatch() {
        request.setRequestURI("/");
        Object outerHandler = new Object();
        Object forwardedHandler = new Object();

        interceptor.preHandle(request, response, outerHandler);
        interceptor.postHandle(request, response, outerHandler, new ModelAndView("forward:/users"));

        request.setDispatcherType(DispatcherType.FORWARD);
        interceptor.preHandle(request, response, forwardedHandler);
        interceptor.postHandle(request, response, forwardedHandler, new ModelAndView("users/list"));
        interceptor.afterCompletion(request, response, forwardedHandler, null);

        request.setDispatcherType(DispatcherType.REQUEST);
        interceptor.afterCompletion(request, response, outerHandler, null);
    }

    // Test controller for handler method resolution
    static class TestController {
        public void getUsers() {}
    }
}

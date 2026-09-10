package org.peekaboot.backend.tracing.interceptor;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;

/**
 * Observes controller execution and view rendering as spans of their own, opening an
 * observation scope so spans created inside the handler (JDBC, HTTP clients, ...) nest
 * under the handler span instead of the HTTP server span.
 *
 * <p>Peekaboot's own endpoints and static resources are kept out by the context-relative
 * exclude patterns the interceptor is registered with, not by the interceptor itself.
 */
public class TracingHandlerInterceptor implements AsyncHandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TracingHandlerInterceptor.class);

    private static final String DISPATCH_STACK_ATTR = TracingHandlerInterceptor.class.getName() + ".dispatchStack";

    private final ObservationRegistry observationRegistry;

    public TracingHandlerInterceptor(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    /**
     * What one DispatcherServlet dispatch owns.
     *
     * <p>A handler that returns {@code forward:} - or an include - runs a second dispatch
     * inside the first one's view rendering, and both call every interceptor callback
     * against the same request. Holding the observations in one request attribute each
     * would have the inner dispatch overwrite the outer dispatch's view observation, which
     * is then never stopped: no span for it, and its scope stays open on the request
     * thread, so every later request that thread serves inherits the leftover trace
     * context and is captured as part of that trace.
     */
    private static final class DispatchObservations {
        private Observation handlerObservation;
        private Observation.Scope handlerScope;
        private Observation viewObservation;
        private Observation.Scope viewScope;
    }

    /**
     * Pushes this dispatch's frame - always, so {@link #afterCompletion} can pop exactly one
     * - and starts the handler observation, except on the ASYNC re-dispatch, which the
     * initial dispatch already observed.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        DispatchObservations dispatch = new DispatchObservations();
        stack(request).push(dispatch);

        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }

        String spanName = resolveHandlerName(handler);
        Observation observation = Observation.createNotStarted("spring.handler", observationRegistry)
                .lowCardinalityKeyValue("handler.type", handler.getClass().getSimpleName())
                .highCardinalityKeyValue("handler.name", spanName)
                .start();

        dispatch.handlerObservation = observation;
        dispatch.handlerScope = observation.openScope();
        log.trace("Started handler observation for {}", spanName);

        return true;
    }

    @Override
    public void postHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
        DispatchObservations dispatch = stack(request).peek();
        if (dispatch == null) {
            return;
        }
        stopHandlerObservation(dispatch, null);

        String viewName = viewName(modelAndView);
        if (viewName != null) {
            Observation viewObservation = Observation.createNotStarted("spring.view.render", observationRegistry)
                    .lowCardinalityKeyValue("view.type", "template")
                    .highCardinalityKeyValue("view.name", viewName)
                    .start();

            dispatch.viewObservation = viewObservation;
            dispatch.viewScope = viewObservation.openScope();
            log.trace("Started view observation for {}", viewName);
        }
    }

    @Override
    public void afterConcurrentHandlingStarted(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        // the handler returned but processing continues on another thread; close the scope on
        // this thread and end the handler span here. afterCompletion never runs for this
        // dispatch - the ASYNC re-dispatch gets its own - so the frame is popped here.
        DispatchObservations dispatch = stack(request).poll();
        if (dispatch != null) {
            stopHandlerObservation(dispatch, null);
        }
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        DispatchObservations dispatch = stack(request).poll();
        if (dispatch == null) {
            return;
        }

        if (dispatch.viewObservation != null) {
            closeScope(dispatch.viewScope);
            if (ex != null) {
                dispatch.viewObservation.error(ex);
            }
            dispatch.viewObservation.stop();
            log.trace("Stopped view observation");
        }

        // an exception in the handler skips postHandle
        stopHandlerObservation(dispatch, ex);
    }

    private static void stopHandlerObservation(DispatchObservations dispatch, Exception ex) {
        if (dispatch.handlerObservation == null) {
            return;
        }
        closeScope(dispatch.handlerScope);
        if (ex != null) {
            dispatch.handlerObservation.error(ex);
        }
        dispatch.handlerObservation.stop();
        dispatch.handlerObservation = null;
        dispatch.handlerScope = null;
        log.trace("Stopped handler observation");
    }

    /**
     * The dispatch frames currently open on this request, innermost first. Nested dispatches
     * run to completion inside the outer one on the same thread, so the deque is only ever
     * touched by one thread at a time and needs no synchronisation of its own.
     */
    @SuppressWarnings("unchecked")
    private static Deque<DispatchObservations> stack(HttpServletRequest request) {
        Deque<DispatchObservations> stack = (Deque<DispatchObservations>) request.getAttribute(DISPATCH_STACK_ATTR);
        if (stack == null) {
            stack = new ArrayDeque<>();
            request.setAttribute(DISPATCH_STACK_ATTR, stack);
        }
        return stack;
    }

    private static void closeScope(Observation.Scope scope) {
        if (scope != null) {
            scope.close();
        }
    }

    /** The view to render, by name or - for a handler that returned a View instance - by type; null when there is none. */
    private static String viewName(ModelAndView modelAndView) {
        if (modelAndView == null) {
            return null;
        }
        if (modelAndView.getViewName() != null) {
            return modelAndView.getViewName();
        }
        View view = modelAndView.getView();
        return view == null ? null : view.getClass().getSimpleName();
    }

    private String resolveHandlerName(Object handler) {
        if (handler instanceof HandlerMethod handlerMethod) {
            return handlerMethod.getBeanType().getSimpleName() + "."
                    + handlerMethod.getMethod().getName();
        }
        return handler.getClass().getSimpleName();
    }
}

package org.peekaboot.backend.tracing.interceptor;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.peekaboot.backend.filter.FilterPathMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * Interceptor that creates spans for controller execution and view rendering.
 * Opens an observation scope so spans created inside the handler (JDBC, HTTP
 * clients, ...) nest under the handler span instead of the HTTP server span.
 */
public class TracingHandlerInterceptor implements AsyncHandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TracingHandlerInterceptor.class);

    private static final String HANDLER_OBSERVATION_ATTR = TracingHandlerInterceptor.class.getName() + ".handlerObservation";
    private static final String HANDLER_SCOPE_ATTR = TracingHandlerInterceptor.class.getName() + ".handlerScope";
    private static final String VIEW_OBSERVATION_ATTR = TracingHandlerInterceptor.class.getName() + ".viewObservation";
    private static final String VIEW_SCOPE_ATTR = TracingHandlerInterceptor.class.getName() + ".viewScope";

    private final ObservationRegistry observationRegistry;

    public TracingHandlerInterceptor(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (FilterPathMatcher.shouldSkip(request.getRequestURI())) {
            return true;
        }
        // the initial dispatch already observed this request; don't double-count
        // the ASYNC re-dispatch
        if (request.getDispatcherType() == DispatcherType.ASYNC
                || request.getAttribute(HANDLER_OBSERVATION_ATTR) != null) {
            return true;
        }

        String spanName = resolveHandlerName(handler);
        Observation observation = Observation.createNotStarted("spring.handler", observationRegistry)
                .lowCardinalityKeyValue("handler.type", handler.getClass().getSimpleName())
                .highCardinalityKeyValue("handler.name", spanName)
                .start();

        request.setAttribute(HANDLER_OBSERVATION_ATTR, observation);
        request.setAttribute(HANDLER_SCOPE_ATTR, observation.openScope());
        log.trace("Started handler observation for {}", spanName);

        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
        if (FilterPathMatcher.shouldSkip(request.getRequestURI())) {
            return;
        }

        stopHandlerObservation(request, null);

        // Start view rendering observation only if there's a view to render
        if (modelAndView != null && modelAndView.getViewName() != null) {
            String viewName = modelAndView.getViewName();
            Observation viewObservation = Observation.createNotStarted("spring.view.render", observationRegistry)
                    .lowCardinalityKeyValue("view.type", "template")
                    .highCardinalityKeyValue("view.name", viewName)
                    .start();

            request.setAttribute(VIEW_OBSERVATION_ATTR, viewObservation);
            request.setAttribute(VIEW_SCOPE_ATTR, viewObservation.openScope());
            log.trace("Started view observation for {}", viewName);
        }
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // the handler returned but processing continues on another thread;
        // close the scope on this thread and end the handler span here
        stopHandlerObservation(request, null);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (FilterPathMatcher.shouldSkip(request.getRequestURI())) {
            return;
        }

        // Stop view observation if present
        Observation viewObservation = (Observation) request.getAttribute(VIEW_OBSERVATION_ATTR);
        if (viewObservation != null) {
            closeScope(request, VIEW_SCOPE_ATTR);
            if (ex != null) {
                viewObservation.error(ex);
            }
            viewObservation.stop();
            request.removeAttribute(VIEW_OBSERVATION_ATTR);
            log.trace("Stopped view observation");
        }

        // Safety: stop handler observation if still running (exception during handler)
        stopHandlerObservation(request, ex);
    }

    private void stopHandlerObservation(HttpServletRequest request, Exception ex) {
        Observation observation = (Observation) request.getAttribute(HANDLER_OBSERVATION_ATTR);
        if (observation == null) {
            return;
        }
        closeScope(request, HANDLER_SCOPE_ATTR);
        if (ex != null) {
            observation.error(ex);
        }
        observation.stop();
        request.removeAttribute(HANDLER_OBSERVATION_ATTR);
        log.trace("Stopped handler observation");
    }

    private void closeScope(HttpServletRequest request, String scopeAttribute) {
        Observation.Scope scope = (Observation.Scope) request.getAttribute(scopeAttribute);
        if (scope != null) {
            scope.close();
            request.removeAttribute(scopeAttribute);
        }
    }

    private String resolveHandlerName(Object handler) {
        if (handler instanceof HandlerMethod handlerMethod) {
            return handlerMethod.getBeanType().getSimpleName() + "." + handlerMethod.getMethod().getName();
        }
        return handler.getClass().getSimpleName();
    }
}

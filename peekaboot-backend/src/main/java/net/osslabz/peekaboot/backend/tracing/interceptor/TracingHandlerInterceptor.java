package net.osslabz.peekaboot.backend.tracing.interceptor;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.osslabz.peekaboot.backend.filter.FilterPathMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * Interceptor that creates spans for controller execution and view rendering.
 * Fills the gap between HTTP server span and database/service spans.
 */
public class TracingHandlerInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TracingHandlerInterceptor.class);

    private static final String HANDLER_OBSERVATION_ATTR = TracingHandlerInterceptor.class.getName() + ".handlerObservation";
    private static final String VIEW_OBSERVATION_ATTR = TracingHandlerInterceptor.class.getName() + ".viewObservation";

    private final ObservationRegistry observationRegistry;

    public TracingHandlerInterceptor(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        if (FilterPathMatcher.shouldSkip(path)) {
            return true;
        }

        String spanName = resolveHandlerName(handler);
        Observation observation = Observation.createNotStarted("spring.handler", observationRegistry)
                .lowCardinalityKeyValue("handler.type", handler.getClass().getSimpleName())
                .highCardinalityKeyValue("handler.name", spanName)
                .start();

        request.setAttribute(HANDLER_OBSERVATION_ATTR, observation);
        log.trace("Started handler observation for {}", spanName);

        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
        String path = request.getRequestURI();
        if (FilterPathMatcher.shouldSkip(path)) {
            return;
        }

        // Stop handler observation
        Observation handlerObservation = (Observation) request.getAttribute(HANDLER_OBSERVATION_ATTR);
        if (handlerObservation != null) {
            handlerObservation.stop();
            request.removeAttribute(HANDLER_OBSERVATION_ATTR);
            log.trace("Stopped handler observation");
        }

        // Start view rendering observation only if there's a view to render
        if (modelAndView != null && modelAndView.getViewName() != null) {
            String viewName = modelAndView.getViewName();
            Observation viewObservation = Observation.createNotStarted("spring.view.render", observationRegistry)
                    .lowCardinalityKeyValue("view.type", "template")
                    .highCardinalityKeyValue("view.name", viewName)
                    .start();

            request.setAttribute(VIEW_OBSERVATION_ATTR, viewObservation);
            log.trace("Started view observation for {}", viewName);
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        String path = request.getRequestURI();
        if (FilterPathMatcher.shouldSkip(path)) {
            return;
        }

        // Stop view observation if present
        Observation viewObservation = (Observation) request.getAttribute(VIEW_OBSERVATION_ATTR);
        if (viewObservation != null) {
            if (ex != null) {
                viewObservation.error(ex);
            }
            viewObservation.stop();
            request.removeAttribute(VIEW_OBSERVATION_ATTR);
            log.trace("Stopped view observation");
        }

        // Safety: stop handler observation if still running (exception during handler)
        Observation handlerObservation = (Observation) request.getAttribute(HANDLER_OBSERVATION_ATTR);
        if (handlerObservation != null) {
            if (ex != null) {
                handlerObservation.error(ex);
            }
            handlerObservation.stop();
            request.removeAttribute(HANDLER_OBSERVATION_ATTR);
            log.trace("Stopped handler observation in afterCompletion (exception path)");
        }
    }

    private String resolveHandlerName(Object handler) {
        if (handler instanceof HandlerMethod handlerMethod) {
            return handlerMethod.getBeanType().getSimpleName() + "." + handlerMethod.getMethod().getName();
        }
        return handler.getClass().getSimpleName();
    }
}

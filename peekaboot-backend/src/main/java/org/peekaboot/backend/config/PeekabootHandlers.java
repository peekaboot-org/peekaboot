package org.peekaboot.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Predicate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

/**
 * Tells Peekaboot's own handlers from the host's by what they are. A path cannot: a host's path
 * prefix, a case-insensitive matcher or a second DispatcherServlet serves the same handler at a
 * path Peekaboot never named.
 *
 * <p>The redirects at {@code /peekaboot} are not counted. A redirect to the UI hands over nothing.
 */
public final class PeekabootHandlers {

    private static final HandlerTypePredicate PEEKABOOTS_CONTROLLERS =
            HandlerTypePredicate.forBasePackage("org.peekaboot.backend");

    /** {@link PeekabootPaths#CLASSPATH_ROOT} as a {@link ClassPathResource} spells it, without the leading slash. */
    private static final String CLASSPATH_PREFIX = PeekabootPaths.CLASSPATH_ROOT.substring(1) + "/";

    private PeekabootHandlers() {}

    /** A method of one of Peekaboot's controllers, all of which serve its API. */
    public static boolean isApi(Object handler) {
        return handler instanceof HandlerMethod handlerMethod
                && PEEKABOOTS_CONTROLLERS.test(handlerMethod.getBeanType());
    }

    /** A resource handler that serves Peekaboot's UI off the class path. */
    public static boolean isUi(Object handler) {
        return handler instanceof ResourceHttpRequestHandler resources
                && resources.getLocations().stream().anyMatch(PeekabootHandlers::isPeekabootsUi);
    }

    public static boolean isPeekaboots(Object handler) {
        return isApi(handler) || isUi(handler);
    }

    /** {@code delegate} for the handlers that {@code matches}. Every other handler passes it by. */
    public static HandlerInterceptor whereHandler(Predicate<Object> matches, HandlerInterceptor delegate) {
        return new HandlerInterceptor() {

            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws Exception {
                return !matches.test(handler) || delegate.preHandle(request, response, handler);
            }

            @Override
            public void postHandle(
                    HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView)
                    throws Exception {
                if (matches.test(handler)) {
                    delegate.postHandle(request, response, handler, modelAndView);
                }
            }

            @Override
            public void afterCompletion(
                    HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
                    throws Exception {
                if (matches.test(handler)) {
                    delegate.afterCompletion(request, response, handler, ex);
                }
            }
        };
    }

    private static boolean isPeekabootsUi(Resource location) {
        return location instanceof ClassPathResource onClassPath
                && onClassPath.getPath().startsWith(CLASSPATH_PREFIX);
    }
}

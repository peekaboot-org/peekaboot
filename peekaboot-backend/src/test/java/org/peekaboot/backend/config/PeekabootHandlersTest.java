package org.peekaboot.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.controller.PeekabootApiController;
import org.peekaboot.backend.domain.api.ApiDocument;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

class PeekabootHandlersTest {

    @Test
    void aPeekabootControllerIsAnApiHandler() throws Exception {
        Object handler = new HandlerMethod(
                new PeekabootApiController(ApiDocument.bundled(), PeekabootPaths.defaults()), "index");

        assertThat(PeekabootHandlers.isApi(handler)).isTrue();
        assertThat(PeekabootHandlers.isUi(handler)).isFalse();
        assertThat(PeekabootHandlers.isPeekaboots(handler)).isTrue();
    }

    @Test
    void aHostsControllerIsNotPeekaboots() throws Exception {
        Object handler = new HandlerMethod(new Object(), "toString");

        assertThat(PeekabootHandlers.isApi(handler)).isFalse();
        assertThat(PeekabootHandlers.isPeekaboots(handler)).isFalse();
    }

    /** The two locations {@link PeekabootWebConfig} serves from. */
    @ParameterizedTest
    @ValueSource(strings = {"classpath:/META-INF/peekaboot/ui/", "classpath:/META-INF/peekaboot/ui/vendor/geist/"})
    void peekabootsUiResourceHandlerIsAUiHandler(String location) throws Exception {
        Object handler = resourceHandler(location);

        assertThat(PeekabootHandlers.isUi(handler)).isTrue();
        assertThat(PeekabootHandlers.isApi(handler)).isFalse();
        assertThat(PeekabootHandlers.isPeekaboots(handler)).isTrue();
    }

    /** Boot's own static location, a folder whose name only starts like Peekaboot's, and Peekaboot's path outside the class path. */
    @ParameterizedTest
    @ValueSource(
            strings = {"classpath:/static/", "classpath:/META-INF/peekaboot-extras/", "file:/META-INF/peekaboot/ui/"})
    void aHostsResourceHandlerIsNotPeekaboots(String location) throws Exception {
        Object handler = resourceHandler(location);

        assertThat(PeekabootHandlers.isUi(handler)).isFalse();
        assertThat(PeekabootHandlers.isPeekaboots(handler)).isFalse();
    }

    @Test
    void whereHandlerRunsTheDelegateOnlyForAMatch() throws Exception {
        Object matching = "a handler the predicate matches";
        RecordingInterceptor delegate = new RecordingInterceptor();
        HandlerInterceptor interceptor = PeekabootHandlers.whereHandler(String.class::isInstance, delegate);
        HttpServletRequest request = new MockHttpServletRequest();
        HttpServletResponse response = new MockHttpServletResponse();

        boolean otherProceeds = interceptor.preHandle(request, response, new Object());
        interceptor.postHandle(request, response, new Object(), null);
        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(otherProceeds).isTrue();
        assertThat(delegate.calls).isEmpty();

        boolean matchProceeds = interceptor.preHandle(request, response, matching);
        interceptor.postHandle(request, response, matching, null);
        interceptor.afterCompletion(request, response, matching, null);

        assertThat(matchProceeds).as("the delegate's own answer").isFalse();
        assertThat(delegate.calls).containsExactly("preHandle", "postHandle", "afterCompletion");
    }

    /** Resolved through a web application context, the way Spring MVC resolves a registered location. */
    private static ResourceHttpRequestHandler resourceHandler(String location) throws Exception {
        ResourceHttpRequestHandler handler = new ResourceHttpRequestHandler();
        handler.setLocationValues(List.of(location));
        handler.setApplicationContext(new GenericWebApplicationContext(new MockServletContext()));
        handler.afterPropertiesSet();
        return handler;
    }

    /** Refuses in {@code preHandle}, so a pass-through answer cannot be mistaken for the delegate's. */
    private static final class RecordingInterceptor implements HandlerInterceptor {

        private final List<String> calls = new ArrayList<>();

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            calls.add("preHandle");
            return false;
        }

        @Override
        public void postHandle(
                HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
            calls.add("postHandle");
        }

        @Override
        public void afterCompletion(
                HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
            calls.add("afterCompletion");
        }
    }
}

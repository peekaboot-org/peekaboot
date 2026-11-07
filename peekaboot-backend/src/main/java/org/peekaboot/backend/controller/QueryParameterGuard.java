package org.peekaboot.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.problem.ApiProblemException;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Refuses a query parameter an endpoint does not take, and one sent more than once, before the
 * handler runs. What an endpoint takes is read off its handler method, so each parameter is
 * declared once, where it is bound: a {@code @RequestParam} by its name, a {@code @ModelAttribute}
 * record by its components. Spring joins a repeat with commas before any reader sees it, and a
 * route or a path can carry a comma of its own, so the raw values are counted here. Spring
 * Security's {@code continue} is let through.
 */
public final class QueryParameterGuard implements HandlerInterceptor {

    /**
     * Spring Security's request cache adds it to the URL a login sends a browser back to
     * ({@code HttpSessionRequestCache}), so it reaches every handler and is never refused.
     */
    private static final String LOGIN_RETURN_MARKER = "continue";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod handlerMethod) {
            refuseUndeclaredOrRepeated(request.getParameterMap(), declaredBy(handlerMethod));
        }
        return true;
    }

    private static List<String> declaredBy(HandlerMethod handler) {
        List<String> names = new ArrayList<>();
        for (MethodParameter parameter : handler.getMethodParameters()) {
            names.addAll(namesOf(handler, parameter));
        }
        return names;
    }

    /** Any other argument could read the request itself, so the guard refuses to guess what it takes. */
    private static List<String> namesOf(HandlerMethod handler, MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null && !requestParam.name().isEmpty()) {
            return List.of(requestParam.name());
        }
        Class<?> type = parameter.getParameterType();
        if (parameter.hasParameterAnnotation(ModelAttribute.class) && type.isRecord()) {
            return Arrays.stream(type.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
        }
        if (parameter.hasParameterAnnotation(PathVariable.class)) {
            return List.of();
        }
        throw new IllegalStateException("QueryParameterGuard cannot tell which query parameters "
                + handler.getShortLogMessage() + " reads through its " + type.getSimpleName()
                + " argument. Bind each one with a named @RequestParam, a @ModelAttribute record or a @PathVariable.");
    }

    private static void refuseUndeclaredOrRepeated(Map<String, String[]> sent, List<String> declared) {
        for (Map.Entry<String, String[]> parameter : sent.entrySet()) {
            String name = parameter.getKey();
            if (LOGIN_RETURN_MARKER.equals(name)) {
                continue;
            }
            if (!declared.contains(name)) {
                throw ApiProblemException.badParameter(
                        name,
                        "Unknown parameter '" + name + "'. Accepted: "
                                + (declared.isEmpty() ? "none" : String.join(", ", declared)) + ".");
            }
            int times = parameter.getValue().length;
            if (times > 1) {
                throw ApiProblemException.badParameter(name, name + " was sent " + times + " times. Send it once.");
            }
        }
    }
}

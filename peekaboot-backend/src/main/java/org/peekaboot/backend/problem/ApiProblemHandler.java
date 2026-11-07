package org.peekaboot.backend.problem;

import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Answers a bad or missing parameter, an {@link ApiProblemException} such as the 404 for an
 * unknown trace id, and a {@link ResponseStatusException} such as the insights stream's 503
 * with an {@link ApiProblem}. It runs ahead of any advice the application registers: an
 * application-wide handler must not reshape Peekaboot's errors, and the package scope keeps
 * this one away from the application's.
 */
@RestControllerAdvice(basePackages = ApiProblemHandler.ADVISED_PACKAGE)
public class ApiProblemHandler implements Ordered {

    static final String ADVISED_PACKAGE = "org.peekaboot.backend";

    private static final HandlerTypePredicate ADVISED = HandlerTypePredicate.forBasePackage(ADVISED_PACKAGE);

    private static final ExceptionHandlerMethodResolver HANDLERS =
            new ExceptionHandlerMethodResolver(ApiProblemHandler.class);

    /**
     * Whether this advice answers {@code ex} thrown for {@code handler}, decided the way Spring's
     * {@code ExceptionHandlerExceptionResolver} picks an advice, so a resolver ranked ahead of that
     * one can leave the answer to it. Like Spring's lookup, it follows {@code ex}'s causes without
     * a cycle check.
     */
    public static boolean answers(@Nullable Object handler, Exception ex) {
        return handler instanceof HandlerMethod handlerMethod
                && ADVISED.test(handlerMethod.getBeanType())
                && HANDLERS.resolveMethod(ex) != null;
    }

    @ExceptionHandler(ApiProblemException.class)
    ResponseEntity<ApiProblem> refused(ApiProblemException e) {
        return respond(e.problem());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiProblem> mistyped(MethodArgumentTypeMismatchException e) {
        return respond(ApiProblem.badParameter(
                e.getName(),
                e.getName() + " must be " + expected(e.getRequiredType()) + ", got '" + e.getValue() + "'."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiProblem> missing(MissingServletRequestParameterException e) {
        return respond(ApiProblem.badParameter(e.getParameterName(), e.getParameterName() + " is required."));
    }

    /** The insights stream's 503 past its subscriber cap, and any other status a controller throws. */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiProblem> status(ResponseStatusException e) {
        return respond(ApiProblem.of(e.getStatusCode(), e.getReason() != null ? e.getReason() : e.getMessage()));
    }

    /** The content type is set here, so Spring writes the problem whatever the request's Accept header asked for. */
    public static ResponseEntity<ApiProblem> respond(ApiProblem problem) {
        return ResponseEntity.status(problem.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static String expected(@Nullable Class<?> type) {
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return "an integer";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "true or false";
        }
        return "a valid value";
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }
}

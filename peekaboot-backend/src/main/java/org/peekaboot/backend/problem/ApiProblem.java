package org.peekaboot.backend.problem;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * The body of the errors {@link ApiProblemHandler} and the dashboard guard answer with, an
 * RFC 9457 problem served as {@code application/problem+json}. {@code type} stays
 * {@code about:blank}, so {@code title} is the status's own phrase and {@code detail} says
 * what was wrong with this request.
 *
 * @param parameter the request parameter at fault; null where no single parameter is
 */
public record ApiProblem(
        String type,
        String title,
        int status,
        String detail,
        @Nullable String parameter) {

    private static final String NO_TYPE = "about:blank";

    public static ApiProblem badParameter(String parameter, String detail) {
        return new ApiProblem(NO_TYPE, HttpStatus.BAD_REQUEST.getReasonPhrase(), 400, detail, parameter);
    }

    public static ApiProblem notFound(String detail) {
        return of(HttpStatus.NOT_FOUND, detail);
    }

    public static ApiProblem unauthorized(String detail) {
        return of(HttpStatus.UNAUTHORIZED, detail);
    }

    public static ApiProblem of(HttpStatusCode status, String detail) {
        HttpStatus known = HttpStatus.resolve(status.value());
        String title = known != null ? known.getReasonPhrase() : String.valueOf(status.value());
        return new ApiProblem(NO_TYPE, title, status.value(), detail, null);
    }
}

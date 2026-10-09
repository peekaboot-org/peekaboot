package org.peekaboot.backend.errorpage;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Beats an application's own {@code @ControllerAdvice}, which resolves inside the REQUEST
 * dispatch and so never reaches {@code BasicErrorController} or anything that would consult an
 * {@code ErrorViewResolver} - the mechanism {@link PeekabootErrorView}'s other two paths rely on.
 * Registered only where {@code peekaboot.error-page.override} is set, alongside those paths.
 *
 * <p>Sets the request attributes an ERROR dispatch would have set, so {@link PeekabootErrorView}
 * reads the same status, reason, path and method off this REQUEST dispatch that it would off a
 * real one.
 */
public class PeekabootErrorExceptionResolver implements HandlerExceptionResolver, Ordered {

    /** A cyclic cause chain must not turn an error handler into a StackOverflowError. */
    private static final int MAX_CAUSE_DEPTH = 10;

    private final PeekabootErrorView view;

    public PeekabootErrorExceptionResolver(PeekabootErrorView view) {
        this.view = view;
    }

    @Override
    public ModelAndView resolveException(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // DefaultHandlerExceptionResolver returns an empty ModelAndView for this one on purpose,
        // so nothing is written to a connection the client already dropped. Rendering here would
        // call getWriter() on that same dead connection and raise the same exception again.
        if (ex instanceof AsyncRequestNotUsableException) {
            return null;
        }
        if (!isHtmlTextAccepted(request)) {
            return null;
        }
        HttpStatusCode status = statusOf(ex);
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status.value());
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI());
        request.setAttribute(RequestDispatcher.ERROR_METHOD, request.getMethod());
        ModelAndView modelAndView = new ModelAndView(view);
        modelAndView.setStatus(status);
        return modelAndView;
    }

    /**
     * Not {@code HIGHEST_PRECEDENCE}: Boot's {@code DefaultErrorAttributes} is itself a resolver
     * at that exact value, and a tie there is broken by bean registration order, which nothing
     * here controls - not a value worth leaving non-deterministic. This is the next slot after
     * it, before every other resolver.
     */
    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE + 1;
    }

    /**
     * Walks the cause chain the way {@code ResponseStatusExceptionResolver} does, iteratively
     * rather than recursively and capped at {@link #MAX_CAUSE_DEPTH}: a self-referencing chain
     * would otherwise recurse forever.
     */
    private static HttpStatusCode statusOf(Throwable ex) {
        Throwable current = ex;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH && current != null; depth++) {
            if (current instanceof ErrorResponse errorResponse) {
                return errorResponse.getStatusCode();
            }
            ResponseStatus responseStatus =
                    AnnotatedElementUtils.findMergedAnnotation(current.getClass(), ResponseStatus.class);
            if (responseStatus != null) {
                return responseStatus.code();
            }
            current = current.getCause();
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /**
     * Only a client that names {@code text/html} gets the page. A browser navigating to a page
     * always does; Swagger UI, curl and {@code fetch()} send a bare wildcard or nothing and keep
     * getting the application's own error response, as does an XHR asking for JSON.
     */
    private static boolean isHtmlTextAccepted(HttpServletRequest request) {
        String acceptHeader = request.getHeader("Accept");
        if (!StringUtils.hasText(acceptHeader)) {
            return false;
        }
        try {
            return MediaType.parseMediaTypes(acceptHeader).stream()
                    .anyMatch(each -> each.equalsTypeAndSubtype(MediaType.TEXT_HTML) && each.getQualityValue() > 0);
        } catch (InvalidMediaTypeException ex) {
            return false;
        }
    }
}

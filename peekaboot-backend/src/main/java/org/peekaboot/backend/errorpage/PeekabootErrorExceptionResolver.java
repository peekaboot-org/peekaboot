package org.peekaboot.backend.errorpage;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;

/**
 * Beats an application's own {@code @ControllerAdvice}, which resolves inside the REQUEST
 * dispatch and so never reaches {@code BasicErrorController}. {@link PeekabootErrorView}'s other
 * two paths, the fallback {@code error} view and {@link PeekabootErrorViewResolver}, both go
 * through it. Registered only where {@code peekaboot.error-page.override} is set, alongside
 * {@link PeekabootErrorViewResolver}; the fallback view stands back then.
 *
 * <p>The status, headers and reason are the ones Spring's own resolvers would send, asked in their
 * order: {@link ResponseStatusExceptionResolver}, then {@link DefaultHandlerExceptionResolver},
 * then 500. Running ahead of both, this resolver would otherwise turn every client error Spring
 * knows into a server error. A cyclic cause chain never reaches either. Every answer they give
 * ends in {@code AbstractHandlerExceptionResolver}'s disconnected-client check, which walks the
 * whole chain and never returns from a cycle. Only the status of a
 * {@code ResponseStatusException} or a {@code @ResponseStatus} on it counts, without headers or
 * reason, and anything else is a 500.
 *
 * <p>Sets the request attributes an ERROR dispatch would have set, so {@link PeekabootErrorView}
 * reads the same status, reason, path and method off this REQUEST dispatch that it would off a
 * real one.
 */
public class PeekabootErrorExceptionResolver implements HandlerExceptionResolver, Ordered {

    private static final Logger log = LoggerFactory.getLogger(PeekabootErrorExceptionResolver.class);

    /** Asked rather than copied, so Spring stays the one place its mapping lives. */
    private static final DefaultHandlerExceptionResolver SPRING_MAPPING = new DefaultHandlerExceptionResolver();

    private final PeekabootErrorView view;

    private final ResponseStatusExceptionResolver responseStatusMapping = new ResponseStatusExceptionResolver();

    /**
     * Asks Spring's own resolvers for the status, headers and reason they would send.
     *
     * @param messageSource the application's, so an {@code @ResponseStatus} reason that is a message code resolves as Spring resolves it
     * @param logResolvedException Boot's {@code spring.mvc.log-resolved-exception}, which gives Spring's own resolvers a warn log category
     */
    public PeekabootErrorExceptionResolver(
            PeekabootErrorView view, MessageSource messageSource, boolean logResolvedException) {
        this.view = view;
        this.responseStatusMapping.setMessageSource(messageSource);
        if (logResolvedException) {
            this.responseStatusMapping.setWarnLogCategory(ResponseStatusExceptionResolver.class.getName());
        }
    }

    @Override
    public ModelAndView resolveException(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // Nothing more can be written to a committed response; Spring's own resolvers stand back too.
        if (!isHtmlTextAccepted(request) || response.isCommitted()) {
            return null;
        }
        CauseChain chain = CauseChain.of(ex);
        if (chain.cyclic()) {
            // Spring checks every answer for a disconnected client by walking the whole chain, which never ends here.
            HttpStatusCode annotated = annotatedStatusOf(chain.links());
            return annotated != null ? page(request, annotated) : unmappedPage(request, ex);
        }
        SendErrorRecorder recorder = new SendErrorRecorder(response);
        ModelAndView springAnswer = responseStatusMapping.resolveException(request, recorder, handler, ex);
        if (springAnswer == null) {
            springAnswer = SPRING_MAPPING.resolveException(request, recorder, handler, ex);
        }
        if (springAnswer == null) {
            return unmappedPage(request, ex);
        }
        if (recorder.status == null) {
            // Spring writes nothing where the client already hung up or the response went out
            // meanwhile; neither may the page.
            return springAnswer;
        }
        if (recorder.message != null) {
            request.setAttribute(RequestDispatcher.ERROR_MESSAGE, recorder.message);
        }
        return page(request, HttpStatusCode.valueOf(recorder.status));
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

    /** Unmapped, it would have escaped to the container, which logs it at ERROR under this message. */
    private ModelAndView unmappedPage(HttpServletRequest request, Exception ex) {
        log.error("Request processing failed: {}", ex.toString(), ex);
        return page(request, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ModelAndView page(HttpServletRequest request, HttpStatusCode status) {
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status.value());
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI());
        request.setAttribute(RequestDispatcher.ERROR_METHOD, request.getMethod());
        ModelAndView modelAndView = new ModelAndView(view);
        modelAndView.setStatus(status);
        return modelAndView;
    }

    /** Read the way {@code ResponseStatusExceptionResolver} reads it, for the cyclic chain Spring's own walk cannot take. */
    private static @Nullable HttpStatusCode annotatedStatusOf(List<Throwable> links) {
        for (Throwable link : links) {
            if (link instanceof ResponseStatusException responseStatusException) {
                return responseStatusException.getStatusCode();
            }
            ResponseStatus responseStatus =
                    AnnotatedElementUtils.findMergedAnnotation(link.getClass(), ResponseStatus.class);
            if (responseStatus != null) {
                return responseStatus.code();
            }
        }
        return null;
    }

    /**
     * The cause chain up to its first repeated link. {@code Throwable.initCause} only refuses
     * self-causation, so two exceptions can still cause each other, and every walk that follows
     * {@code getCause()} without remembering its path spins on them.
     */
    private record CauseChain(List<Throwable> links, boolean cyclic) {

        static CauseChain of(Throwable ex) {
            Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            List<Throwable> links = new ArrayList<>();
            for (Throwable link = ex; link != null; link = link.getCause()) {
                if (!seen.add(link)) {
                    return new CauseChain(links, true);
                }
                links.add(link);
            }
            return new CauseChain(links, false);
        }
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

    /**
     * Keeps the status and message Spring's mapping sends instead of sending them, so the page
     * renders with them. Headers the mapping sets, an {@code Allow} on a 405 among them, still
     * reach the real response.
     */
    private static final class SendErrorRecorder extends HttpServletResponseWrapper {

        @Nullable
        private Integer status;

        @Nullable
        private String message;

        SendErrorRecorder(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void sendError(int sc) {
            this.status = sc;
        }

        @Override
        public void sendError(int sc, String msg) {
            this.status = sc;
            this.message = msg;
        }
    }
}

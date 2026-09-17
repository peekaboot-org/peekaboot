package org.peekaboot.backend.errorpage;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.RequestDispatcher;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

/**
 * Beats an application's {@code @ControllerAdvice}, which resolves inside the REQUEST dispatch
 * and never reaches an {@code ErrorViewResolver} at all - the mechanism the other two override
 * paths rely on.
 */
class PeekabootErrorExceptionResolverTest {

    private final PeekabootErrorView view =
            new PeekabootErrorView(new DefaultErrorAttributes(), List.of(), List.of(), false);

    private final PeekabootErrorExceptionResolver resolver = new PeekabootErrorExceptionResolver(view);

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest("GET", "/throwing");
        response = new MockHttpServletResponse();
    }

    /** An XHR or API client asking for JSON must keep getting the application's own error response. */
    @Test
    void fallsThroughForAJsonRequest() {
        request.addHeader("Accept", "application/json");

        ModelAndView modelAndView =
                resolver.resolveException(request, response, null, new IllegalStateException("boom"));

        assertThat(modelAndView).isNull();
    }

    @Test
    void rendersForAnHtmlRequest() {
        request.addHeader("Accept", "text/html");

        ModelAndView modelAndView =
                resolver.resolveException(request, response, null, new IllegalStateException("boom"));

        assertThat(modelAndView).isNotNull();
    }

    @Test
    void rendersForAWildcardAccept() {
        request.addHeader("Accept", "*/*");

        ModelAndView modelAndView =
                resolver.resolveException(request, response, null, new IllegalStateException("boom"));

        assertThat(modelAndView).isNotNull();
    }

    /** curl and a bare fetch() send no Accept header at all; that counts as HTML too. */
    @Test
    void rendersWhereTheRequestCarriesNoAcceptHeader() {
        ModelAndView modelAndView =
                resolver.resolveException(request, response, null, new IllegalStateException("boom"));

        assertThat(modelAndView).isNotNull();
    }

    @Test
    void defaultsToInternalServerErrorForAPlainException() {
        request.addHeader("Accept", "text/html");

        ModelAndView modelAndView =
                resolver.resolveException(request, response, null, new IllegalStateException("boom"));

        assertThat(modelAndView.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    static class AnnotatedException extends RuntimeException {}

    @Test
    void derivesTheStatusFromAResponseStatusAnnotation() {
        request.addHeader("Accept", "text/html");

        ModelAndView modelAndView = resolver.resolveException(request, response, null, new AnnotatedException());

        assertThat(modelAndView.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void derivesTheStatusFromAResponseStatusException() {
        request.addHeader("Accept", "text/html");

        ModelAndView modelAndView =
                resolver.resolveException(request, response, null, new ResponseStatusException(HttpStatus.NOT_FOUND));

        assertThat(modelAndView.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void derivesTheStatusFromTheCauseWhereTheExceptionItselfCarriesNone() {
        request.addHeader("Accept", "text/html");
        Exception wrapper = new RuntimeException("wrapper", new AnnotatedException());

        ModelAndView modelAndView = resolver.resolveException(request, response, null, wrapper);

        assertThat(modelAndView.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * Built without Throwable.initCause's self-causation guard: two exceptions, each other's
     * cause, so walking it without a depth cap would recurse forever. The timeout is the test
     * itself: remove the cap and this spins rather than fails.
     */
    @Test
    @Timeout(5)
    void terminatesOnACyclicCauseChainInsteadOfOverflowing() {
        request.addHeader("Accept", "text/html");
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        ModelAndView modelAndView = resolver.resolveException(request, response, null, first);

        assertThat(modelAndView.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void setsTheErrorDispatchRequestAttributesSoTheSharedViewRendersLikeARealOne() {
        request.addHeader("Accept", "text/html");

        resolver.resolveException(request, response, null, new IllegalStateException("boom"));

        assertThat(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).isEqualTo(500);
        assertThat(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)).isEqualTo("/throwing");
    }

    @Test
    void theModelAndViewCarriesTheStatusAndTheSharedView() {
        request.addHeader("Accept", "text/html");

        ModelAndView modelAndView =
                resolver.resolveException(request, response, null, new ResponseStatusException(HttpStatus.NOT_FOUND));

        assertThat(modelAndView.getView()).isSameAs(view);
        assertThat(modelAndView.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** WebUtils.exposeErrorRequestAttributes never sets this one, so nothing else will. */
    @Test
    void setsTheErrorMethodSoTheRequestLineNamesIt() {
        request = new MockHttpServletRequest("POST", "/orders/42");
        request.addHeader("Accept", "text/html");

        resolver.resolveException(request, response, null, new IllegalStateException("boom"));

        assertThat(request.getAttribute(RequestDispatcher.ERROR_METHOD)).isEqualTo("POST");
    }

    /**
     * DefaultHandlerExceptionResolver returns an empty ModelAndView for this one so that nothing
     * is written to a connection the client already dropped; rendering here would call
     * getWriter() on that same dead connection and raise the same exception again.
     */
    @Test
    void fallsThroughForADeadAsyncConnectionRegardlessOfAccept() {
        request.addHeader("Accept", "text/html");

        ModelAndView modelAndView =
                resolver.resolveException(request, response, null, new AsyncRequestNotUsableException("gone"));

        assertThat(modelAndView).isNull();
    }

    /**
     * A tie with DefaultErrorAttributes is broken by bean registration order, which nothing here
     * controls; asserted as a relationship, not the literal HIGHEST_PRECEDENCE + 1 constant, so
     * the test fails if someone moves this resolver back onto that tie rather than off it.
     */
    @Test
    void ordersItselfStrictlyAfterDefaultErrorAttributes() {
        assertThat(resolver.getOrder()).isGreaterThan(new DefaultErrorAttributes().getOrder());
    }

    /** An exception escaping a resolver while handling an exception is the worst outcome available here. */
    @Test
    void rendersRatherThanThrowingForAnUnparseableAcceptHeader() {
        request.addHeader("Accept", "text/html;;;q=");

        ModelAndView modelAndView =
                resolver.resolveException(request, response, null, new IllegalStateException("boom"));

        assertThat(modelAndView).isNotNull();
    }
}

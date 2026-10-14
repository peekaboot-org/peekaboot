package org.peekaboot.backend.errorpage;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import jakarta.servlet.RequestDispatcher;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.beans.TypeMismatchException;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;

/**
 * Beats an application's {@code @ControllerAdvice}, which resolves inside the REQUEST dispatch
 * and never reaches an {@code ErrorViewResolver} at all - the mechanism the other two override
 * paths rely on.
 */
class PeekabootErrorExceptionResolverTest {

    private final PeekabootErrorView view = new PeekabootErrorView(
            new DefaultErrorAttributes(), List.of(), List.of(), false, PeekabootPaths.defaults());

    private final StaticMessageSource messages = new StaticMessageSource();

    private final PeekabootErrorExceptionResolver resolver = new PeekabootErrorExceptionResolver(view, messages);

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private LogCapture springLogs;
    private LogCapture logs;

    // Most cases here render an unmapped exception, which logs at ERROR, and Spring's own resolver
    // WARNs for each one it maps; both captured so neither prints, and asserted where a test is
    // about them.
    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest("GET", "/throwing");
        response = new MockHttpServletResponse();
        springLogs = LogCapture.attach(DefaultHandlerExceptionResolver.class);
        logs = LogCapture.attach(PeekabootErrorExceptionResolver.class);
    }

    @AfterEach
    void closeLogs() {
        springLogs.close();
        logs.close();
    }

    /** Unmapped, the exception would have reached the container, which logs it at ERROR; the page stands in for that. */
    @Test
    void logsAnExceptionNothingMapsAtErrorTheWayTheContainerWould() {
        request.addHeader("Accept", "text/html");

        resolver.resolveException(request, response, null, new IllegalStateException("boom"));

        assertThat(logs.appender().list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("Request processing failed: java.lang.IllegalStateException: boom");
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
        });
    }

    /** A cyclic chain never reaches Spring's mapping, and is just as unmapped. */
    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void logsACyclicCauseChainAtErrorToo() {
        request.addHeader("Accept", "text/html");
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        resolver.resolveException(request, response, null, first);

        assertThat(logs.appender().list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("Request processing failed: java.lang.RuntimeException: first");
        });
    }

    /** Spring resolves a mapped status without an ERROR line, and so does the page that stands in for it. */
    @Test
    void logsNothingAtErrorForAMappedStatus() {
        request.addHeader("Accept", "text/html");

        resolver.resolveException(request, response, null, new ResponseStatusException(HttpStatus.NOT_FOUND));
        resolver.resolveException(request, response, null, new TypeMismatchException("abc", Long.class));

        assertThat(logs.appender().list).isEmpty();
    }

    /** The application's own handling decides what to log for a request the page leaves alone. */
    @Test
    void logsNothingForARequestItLeavesToTheApplication() {
        request.addHeader("Accept", "application/json");

        resolver.resolveException(request, response, null, new IllegalStateException("boom"));

        assertThat(logs.appender().list).isEmpty();
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

    /** A browser navigating to a page names text/html alongside its wildcard. */
    @Test
    void rendersForABrowserNavigation() {
        request.addHeader(
                "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");

        ModelAndView modelAndView =
                resolver.resolveException(request, response, null, new IllegalStateException("boom"));

        assertThat(modelAndView).isNotNull();
    }

    /** Swagger UI, curl and fetch() send a bare wildcard; they want the application's own response. */
    @Test
    void fallsThroughForAWildcardAccept() {
        request.addHeader("Accept", "*/*");

        ModelAndView modelAndView =
                resolver.resolveException(request, response, null, new IllegalStateException("boom"));

        assertThat(modelAndView).isNull();
    }

    @Test
    void fallsThroughForATextWildcardAccept() {
        request.addHeader("Accept", "text/*");

        ModelAndView modelAndView =
                resolver.resolveException(request, response, null, new IllegalStateException("boom"));

        assertThat(modelAndView).isNull();
    }

    @Test
    void fallsThroughWhereTheRequestCarriesNoAcceptHeader() {
        ModelAndView modelAndView =
                resolver.resolveException(request, response, null, new IllegalStateException("boom"));

        assertThat(modelAndView).isNull();
    }

    @Test
    void fallsThroughWhereHtmlIsExplicitlyRefused() {
        request.addHeader("Accept", "text/html;q=0, application/json");

        ModelAndView modelAndView =
                resolver.resolveException(request, response, null, new IllegalStateException("boom"));

        assertThat(modelAndView).isNull();
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

    @ResponseStatus(code = HttpStatus.CONFLICT, reason = "Already booked")
    static class ReasonedException extends RuntimeException {}

    @ResponseStatus(code = HttpStatus.CONFLICT, reason = "booking.taken")
    static class MessageCodeReasonedException extends RuntimeException {}

    /** Spring resolves a reason through the application's MessageSource; so does the page. */
    @Test
    void resolvesAReasonThatIsAMessageCode() {
        request.addHeader("Accept", "text/html");
        messages.addMessage("booking.taken", Locale.getDefault(), "That slot is already booked");

        resolver.resolveException(request, response, null, new MessageCodeReasonedException());

        assertThat(request.getAttribute(RequestDispatcher.ERROR_MESSAGE)).isEqualTo("That slot is already booked");
    }

    /** Spring's ResponseStatusExceptionResolver sends a ResponseStatusException's headers; so does the page. */
    @Test
    void keepsTheHeadersOfAResponseStatusException() {
        request.addHeader("Accept", "text/html");
        ResponseStatusException tooMany = new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS) {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.add("Retry-After", "30");
                return headers;
            }
        };

        ModelAndView modelAndView = resolver.resolveException(request, response, null, tooMany);

        assertThat(modelAndView.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeader("Retry-After")).isEqualTo("30");
    }

    @Test
    void keepsTheReasonOfAResponseStatusAnnotation() {
        request.addHeader("Accept", "text/html");

        resolver.resolveException(request, response, null, new ReasonedException());

        assertThat(request.getAttribute(RequestDispatcher.ERROR_MESSAGE)).isEqualTo("Already booked");
    }

    /** Nothing more can be written to a committed response, so Spring's resolvers stand back from it, and so does this one. */
    @Test
    void standsBackFromACommittedResponse() {
        request.addHeader("Accept", "text/html");
        response.setCommitted(true);

        assertThat(resolver.resolveException(request, response, null, new IllegalStateException("boom")))
                .isNull();
        assertThat(logs.appender().list).isEmpty();
    }

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
     * cause, so a cause walk without cycle detection never ends. Spring's own walk is one of
     * those, so the chain must never reach it. The timeout is the test itself, on its own
     * thread, because a spinning test thread would otherwise hang the build instead of failing.
     */
    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
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
     * Spring answers a client that already hung up with an empty ModelAndView, so nothing is
     * written to the dead connection; rendering would call getWriter() on it and fail again.
     */
    @Test
    void writesNothingForADeadAsyncConnection() {
        request.addHeader("Accept", "text/html");

        ModelAndView modelAndView =
                resolver.resolveException(request, response, null, new AsyncRequestNotUsableException("gone"));

        assertThat(modelAndView).isNotNull();
        assertThat(modelAndView.isEmpty()).isTrue();
    }

    /** Spring answers a path variable that does not convert with 400; the page must not turn it into a 500. */
    @Test
    void keepsSpringsBadRequestForATypeMismatch() {
        request.addHeader("Accept", "text/html");

        ModelAndView modelAndView =
                resolver.resolveException(request, response, null, new TypeMismatchException("abc", Long.class));

        assertThat(modelAndView.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).isEqualTo(400);
    }

    @Test
    void keepsSpringsBadRequestForAnUnreadableBody() {
        request.addHeader("Accept", "text/html");
        Exception unreadable = new HttpMessageNotReadableException("unreadable", new MockHttpInputMessage(new byte[0]));

        ModelAndView modelAndView = resolver.resolveException(request, response, null, unreadable);

        assertThat(modelAndView.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * Spring's mapping also sets headers and a message. Both reach the page; the error itself is
     * never sent, or the container would commit the response before the page renders.
     */
    @Test
    void keepsTheHeadersAndMessageSpringsMappingSets() {
        request.addHeader("Accept", "text/html");
        Exception notSupported = new HttpRequestMethodNotSupportedException("POST", List.of("GET"));

        ModelAndView modelAndView = resolver.resolveException(request, response, null, notSupported);

        assertThat(modelAndView.getStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeader("Allow")).isEqualTo("GET");
        assertThat(request.getAttribute(RequestDispatcher.ERROR_MESSAGE)).isEqualTo("Method 'POST' is not supported.");
        assertThat(response.isCommitted()).isFalse();
    }

    /** The line Spring's own resolver logs for an exception it maps, had this one not run first. */
    @Test
    void leavesSpringsWarningForAMappedException() {
        request.addHeader("Accept", "text/html");

        resolver.resolveException(request, response, null, new TypeMismatchException("abc", Long.class));

        assertThat(springLogs.appender().list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .startsWith("Resolved [org.springframework.beans.TypeMismatchException");
        });
    }

    /** Spring's mapping reads the exception itself, not its causes, so a wrapped one is a 500 there too. */
    @Test
    void leavesAWrappedSpringExceptionAtInternalServerErrorAsSpringDoes() {
        request.addHeader("Accept", "text/html");
        Exception wrapper = new RuntimeException("wrapper", new TypeMismatchException("abc", Long.class));

        ModelAndView modelAndView = resolver.resolveException(request, response, null, wrapper);

        assertThat(modelAndView.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /** Spring walks causes only for ResponseStatusException and @ResponseStatus; any other ErrorResponse counts at the top alone. */
    @Test
    void leavesAWrappedErrorResponseAtInternalServerErrorAsSpringDoes() {
        request.addHeader("Accept", "text/html");
        Exception wrapper = new RuntimeException("wrapper", new HttpRequestMethodNotSupportedException("POST"));

        ModelAndView modelAndView = resolver.resolveException(request, response, null, wrapper);

        assertThat(modelAndView.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
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
    void fallsThroughRatherThanThrowingForAnUnparseableAcceptHeader() {
        request.addHeader("Accept", "text/html;;;q=");

        ModelAndView modelAndView =
                resolver.resolveException(request, response, null, new IllegalStateException("boom"));

        assertThat(modelAndView).isNull();
    }
}

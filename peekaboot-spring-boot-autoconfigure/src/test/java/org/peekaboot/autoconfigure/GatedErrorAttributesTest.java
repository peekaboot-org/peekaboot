package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.RequestDispatcher;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

class GatedErrorAttributesTest {

    private static final ErrorAttributeOptions EVERYTHING =
            ErrorAttributeOptions.of(ErrorAttributeOptions.Include.values());

    private final IllegalStateException failure = new IllegalStateException("gateway unreachable");

    private final WebRequest failedRequest = new ServletWebRequest(requestThatFailedWith(failure));

    @Test
    void aRefusedRequestGetsStatusAndPathOnly() {
        GatedErrorAttributes gated = new GatedErrorAttributes(new DefaultErrorAttributes(), request -> false);

        Map<String, Object> attributes = gated.getErrorAttributes(failedRequest, EVERYTHING);

        assertThat(attributes)
                .containsEntry("status", 500)
                .containsEntry("path", "/throwing")
                .doesNotContainKeys("trace", "exception", "message");
    }

    @Test
    void aPermittedRequestGetsWhatWasAskedFor() {
        GatedErrorAttributes gated = new GatedErrorAttributes(new DefaultErrorAttributes(), request -> true);

        assertThat(gated.getErrorAttributes(failedRequest, EVERYTHING))
                .containsEntry("exception", "java.lang.IllegalStateException")
                .containsEntry("message", "gateway unreachable")
                .hasEntrySatisfying(
                        "trace",
                        trace -> assertThat(trace.toString())
                                .startsWith("java.lang.IllegalStateException: gateway unreachable"));
    }

    /** The rule reads the servlet request, so a request it cannot read is one it cannot permit. */
    @Test
    void aRequestThatIsNoServletRequestIsRefused() {
        GatedErrorAttributes gated = new GatedErrorAttributes(new DefaultErrorAttributes(), request -> true);
        WebRequest notAServletRequest = mock(WebRequest.class);
        when(notAServletRequest.getAttribute(RequestDispatcher.ERROR_EXCEPTION, RequestAttributes.SCOPE_REQUEST))
                .thenReturn(failure);

        assertThat(gated.getErrorAttributes(notAServletRequest, EVERYTHING))
                .doesNotContainKeys("trace", "exception", "message");
    }

    @Test
    void theErrorIsTheDelegates() {
        GatedErrorAttributes gated = new GatedErrorAttributes(new DefaultErrorAttributes(), request -> false);

        assertThat(gated.getError(failedRequest)).isSameAs(failure);
    }

    private static MockHttpServletRequest requestThatFailedWith(Throwable failure) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, failure);
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/throwing");
        return request;
    }
}

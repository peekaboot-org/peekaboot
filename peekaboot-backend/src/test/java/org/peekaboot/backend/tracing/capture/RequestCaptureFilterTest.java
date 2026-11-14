package org.peekaboot.backend.tracing.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.peekaboot.backend.testsupport.Requests.get;

import ch.qos.logback.classic.Level;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.testsupport.CurrentSpans;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

@ExtendWith(MockitoExtension.class)
class RequestCaptureFilterTest {

    @Mock
    Tracer tracer;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @Mock
    FilterChain chain;

    MockHttpServletRequest request;
    MockHttpServletResponse response;
    RequestCaptureFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestCaptureFilter(tracer, eventPublisher, new MaskingEngine(), PeekabootPaths.defaults());
        request = get("/api/users");
        response = new MockHttpServletResponse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"/static/app.js", "/webjars/jquery.js", "/actuator/health", "/peekaboot/api/traces", "/error"})
    void skipsExcludedPaths(String path) throws Exception {
        request = get(path);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void doesNotPublishEventWithoutTraceId() throws Exception {
        when(tracer.currentSpan()).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void publishesEventWithTraceId() throws Exception {
        CurrentSpans.stub(tracer, "abc123");

        filter.doFilter(request, response, chain);

        assertThat(requestGivenToTheChain().getRequest()).isSameAs(request);
        RequestCompletedEvent event = publishedEvent();
        assertThat(event.traceId()).isEqualTo("abc123");
        assertThat(event.method()).isEqualTo("GET");
        assertThat(event.path()).isEqualTo("/api/users");
        assertThat(event.status()).isEqualTo(200);
    }

    /**
     * A handler that throws answers 500, but nothing has set that status yet when this
     * filter's finally block runs: the container sets it in its own valve, outside every
     * filter, and Spring's ServerHttpObservationFilter sets it one filter further out. So
     * the exchange records the status the client sees, the same one the server span carries.
     */
    @Test
    void aThrownExceptionIsCapturedAsAServerError() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        doThrow(new ServletException("handler failed")).when(chain).doFilter(any(), any());

        assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isInstanceOf(ServletException.class);

        assertThat(publishedEvent().status()).isEqualTo(500);
    }

    /**
     * Once the response is committed, {@code ServerHttpObservationFilter}'s own
     * {@code setStatus(500)} is silently ignored by the container - a committed response
     * cannot change its status line. The exchange has to keep the status that was actually
     * flushed, not the 500 nobody managed to send.
     */
    @Test
    void aThrownExceptionAfterTheResponseIsCommittedKeepsTheFlushedStatus() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        response.setStatus(200);
        response.flushBuffer();
        doThrow(new ServletException("handler failed")).when(chain).doFilter(any(), any());

        assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isInstanceOf(ServletException.class);

        assertThat(publishedEvent().status()).isEqualTo(200);
    }

    @Test
    void capturesRequestHeaders() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.setMethod("POST");
        request.addHeader("Content-Type", "application/json");
        request.addHeader("Accept", "application/json");
        response.setStatus(201);

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.requestHeaders()).containsEntry("Content-Type", "application/json");
        assertThat(event.requestHeaders()).containsEntry("Accept", "application/json");
        assertThat(event.status()).isEqualTo(201);
    }

    @Test
    void masksSensitiveHeaders() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.addHeader("Authorization", "Bearer secret-token");
        request.addHeader("Cookie", "session=xyz");
        request.addHeader("X-Api-Key", "api-key-123");
        request.addHeader("Content-Type", "application/json");

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.requestHeaders()).containsEntry("Authorization", "******");
        assertThat(event.requestHeaders()).containsEntry("Cookie", "******");
        assertThat(event.requestHeaders()).containsEntry("X-Api-Key", "******");
        assertThat(event.requestHeaders()).containsEntry("Content-Type", "application/json");
    }

    /** A header sent more than once (Accept, or several Set-Cookie on the response) is captured whole. */
    @Test
    void joinsTheValuesOfARepeatedHeader() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.addHeader("Accept", "text/html");
        request.addHeader("Accept", "application/json");
        response.addHeader("Vary", "Accept");
        response.addHeader("Vary", "Cookie");

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.requestHeaders()).containsEntry("Accept", "text/html, application/json");
        assertThat(event.responseHeaders()).containsEntry("Vary", "Accept, Cookie");
    }

    /**
     * Header masking goes through the engine's key-name rules rather than a fixed list,
     * so Proxy-Authorization - which carries a credential just as Authorization does - is
     * masked without being named anywhere.
     */
    @Test
    void masksProxyAuthorizationHeader() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.addHeader("Proxy-Authorization", "Basic dXNlcjpwYXNz");

        filter.doFilter(request, response, chain);

        assertThat(publishedEvent().requestHeaders()).containsEntry("Proxy-Authorization", "******");
    }

    @Test
    void capturesQueryParameters() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.setQueryString("page=1&size=10");
        request.setParameter("page", "1");
        request.setParameter("size", "10");

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.queryParams()).containsEntry("page", List.of("1"));
        assertThat(event.queryParams()).containsEntry("size", List.of("10"));
    }

    @Test
    void masksSensitiveQueryParameterValues() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request = get("/search");
        request.setQueryString("api_key=xyz&q=widgets");
        request.setParameter("api_key", "xyz");
        request.setParameter("q", "widgets");

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.queryParams()).containsEntry("api_key", List.of("******"));
        assertThat(event.queryParams()).containsEntry("q", List.of("widgets"));
    }

    @Test
    void masksTheRawQueryStringPerParameter() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request = get("/search");
        request.setQueryString("api_key=xyz&q=widgets");
        request.setParameter("api_key", "xyz");
        request.setParameter("q", "widgets");

        filter.doFilter(request, response, chain);

        assertThat(publishedEvent().queryString()).isEqualTo("api_key=******&q=widgets");
    }

    @Test
    void masksSensitiveFormParameterValues() throws Exception {
        // getParameterMap() merges query-string and form-body parameters; only actual
        // query-string keys belong in queryParams and only body keys in formParams -
        // "password" here has no query-string counterpart, so it lands in formParams.
        CurrentSpans.stub(tracer, "trace1");
        request = get("/login");
        request.setMethod("POST");
        request.setContentType("application/x-www-form-urlencoded");
        request.setParameter("username", "alice");
        request.setParameter("password", "hunter2");
        applicationReads(HttpServletRequest::getParameterMap);

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.formParams()).containsEntry("username", List.of("alice"));
        assertThat(event.formParams()).containsEntry("password", List.of("******"));
    }

    /** Multipart fields reach getParameterMap() too; a parameter absent from the query string came from the body. */
    @Test
    void reportsMultipartFieldsAsFormParameters() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request = get("/upload");
        request.setMethod("POST");
        request.setContentType("multipart/form-data; boundary=----peekaboot");
        request.setParameter("title", "Quarterly report");
        applicationReads(HttpServletRequest::getParameterMap);

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.formParams()).containsEntry("title", List.of("Quarterly report"));
        assertThat(event.queryParams()).isEmpty();
    }

    @Test
    void reportsPatchFormFieldsAsFormParameters() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request = get("/persons/1");
        request.setMethod("PATCH");
        request.setContentType("application/x-www-form-urlencoded");
        request.setParameter("firstName", "Bob");
        applicationReads(HttpServletRequest::getParameterMap);

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.formParams()).containsEntry("firstName", List.of("Bob"));
        assertThat(event.queryParams()).isEmpty();
    }

    @Test
    void preservesAQueryStringPairWithNoValue() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request = get("/search");
        request.setQueryString("debug&q=widgets");
        request.setParameter("debug", "");
        request.setParameter("q", "widgets");

        filter.doFilter(request, response, chain);

        assertThat(publishedEvent().queryString()).isEqualTo("debug&q=widgets");
    }

    /**
     * A query-string key whose percent-encoding is malformed cannot be decoded. Losing the
     * decode to an exception would cost the whole capture - the event, and a WARN per
     * request - so the raw key stands in and the capture completes.
     */
    @ParameterizedTest
    @ValueSource(strings = {"a%zz=1&q=widgets", "100%&q=widgets"})
    void capturesARequestWhoseQueryStringHasAMalformedKey(String queryString) throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request = get("/orders");
        request.setQueryString(queryString);
        request.setParameter("q", "widgets");

        try (LogCapture capture = LogCapture.attach(RequestCaptureFilter.class)) {
            filter.doFilter(request, response, chain);

            assertThat(capture.appender().list).isEmpty();
        }

        assertThat(publishedEvent().queryParams()).containsEntry("q", List.of("widgets"));
    }

    @Test
    void capturesANullQueryStringWhenThereIsNone() throws Exception {
        CurrentSpans.stub(tracer, "trace1");

        filter.doFilter(request, response, chain);

        assertThat(publishedEvent().queryString()).isNull();
    }

    @Test
    void separatesQueryAndFormParameters() throws Exception {
        // getParameterMap() merges query-string and form-body parameters;
        // only actual query-string keys belong in queryParams and only
        // body keys in formParams
        CurrentSpans.stub(tracer, "trace1");
        request.setMethod("POST");
        request.setContentType("application/x-www-form-urlencoded");
        request.setQueryString("page=1");
        request.setParameter("page", "1");
        request.setParameter("firstName", "Bob");
        response.setStatus(201);
        applicationReads(HttpServletRequest::getParameterMap);

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.queryParams()).containsOnlyKeys("page");
        assertThat(event.formParams()).containsOnlyKeys("firstName");
        assertThat(event.formParams()).containsEntry("firstName", List.of("Bob"));
    }

    /**
     * The container parses a form body on the first read of a parameter or a part. A capture
     * that asked would parse the body of a request the application rejected unread.
     */
    @Test
    void aFormBodyTheApplicationNeverReadIsNotParsed() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request = new UnparsedFormRequest("POST", "/login");
        request.setContentType("application/x-www-form-urlencoded");
        request.setQueryString("next=%2Fhome");

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.formParams()).isEmpty();
        assertThat(event.queryParams()).containsEntry("next", List.of("/home"));
    }

    @Test
    void formParametersAreCapturedOnceTheApplicationReadThem() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request = get("/login");
        request.setMethod("POST");
        request.setContentType("application/x-www-form-urlencoded");
        request.setParameter("username", "alice");
        request.setParameter("password", "hunter2");
        applicationReads(read -> read.getParameter("username"));

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.formParams()).containsEntry("username", List.of("alice"));
        assertThat(event.formParams()).containsEntry("password", List.of("******"));
    }

    @Test
    void aMultipartFormIsCapturedOnceTheApplicationReadItsParts() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request = get("/upload");
        request.setMethod("POST");
        request.setContentType("multipart/form-data; boundary=----peekaboot");
        request.setParameter("title", "Quarterly report");
        applicationReads(HttpServletRequest::getParts);

        filter.doFilter(request, response, chain);

        assertThat(publishedEvent().formParams()).containsEntry("title", List.of("Quarterly report"));
    }

    @ParameterizedTest
    @MethodSource("otherParameterReads")
    void everyOtherWayOfReadingParametersCountsAsARead(ThrowingConsumer<HttpServletRequest> read) throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request = get("/login");
        request.setMethod("POST");
        request.setContentType("application/x-www-form-urlencoded");
        request.setParameter("username", "alice");
        applicationReads(read);

        filter.doFilter(request, response, chain);

        assertThat(publishedEvent().formParams()).containsEntry("username", List.of("alice"));
    }

    /** A handler that hands off may read its parameters only on the thread that finishes the async cycle. */
    @Test
    void capturesFormParametersReadDuringTheAsyncCycle() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.setMethod("POST");
        request.setContentType("application/x-www-form-urlencoded");
        request.setParameter("username", "alice");
        MockAsyncContext asyncContext = startAsync();

        filter.doFilter(request, response, chain);
        requestGivenToTheChain().getParameter("username");
        asyncContext.complete();

        assertThat(publishedEvent().formParams()).containsKey("username");
    }

    @Test
    void aRepeatedQueryKeyKeepsEveryValueInOrder() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.setQueryString("tag=a&tag=b&tag=");

        filter.doFilter(request, response, chain);

        assertThat(publishedEvent().queryParams()).containsEntry("tag", List.of("a", "b", ""));
    }

    @Test
    void decodesPlusAndPercentEscapesInQueryParameters() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.setQueryString("q=red+shoes&path=%2Fhome%3Fx");

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.queryParams()).containsEntry("q", List.of("red shoes"));
        assertThat(event.queryParams()).containsEntry("path", List.of("/home?x"));
    }

    @Test
    void aBareQueryKeyHasAnEmptyValue() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.setQueryString("debug&q=widgets");

        filter.doFilter(request, response, chain);

        assertThat(publishedEvent().queryParams()).containsEntry("debug", List.of(""));
    }

    @Test
    void skipsAQueryPairWithoutAKey() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.setQueryString("=1&&q=widgets&");

        filter.doFilter(request, response, chain);

        assertThat(publishedEvent().queryParams()).isEqualTo(Map.of("q", List.of("widgets")));
    }

    /** A value that cannot be decoded cannot be judged either, so it is masked, as the raw query string masks it. */
    @Test
    void aQueryPairThatCannotBeDecodedIsMaskedWhole() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.setQueryString("bad=%zz&q=widgets");

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.queryParams()).containsEntry("bad", List.of("******"));
        assertThat(event.queryParams()).containsEntry("q", List.of("widgets"));
    }

    /** The container merges both sources under one key and cannot say which value came from where. */
    @Test
    void aKeyInBothQueryStringAndBodyListsItsQueryValuesOnly() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.setMethod("POST");
        request.setContentType("application/x-www-form-urlencoded");
        request.setQueryString("page=1");
        request.setParameter("page", "1", "7");
        applicationReads(HttpServletRequest::getParameterMap);

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.queryParams()).containsEntry("page", List.of("1"));
        assertThat(event.formParams()).doesNotContainKey("page");
    }

    @Test
    void capturesControllerInfo() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
                new HandlerMethod(new TestController(), TestController.class.getMethod("getUsers")));

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.controllerClass()).endsWith("TestController");
        assertThat(event.controllerMethod()).isEqualTo("getUsers");
    }

    @Test
    void capturesResponseHeaders() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        response.addHeader("Content-Type", "application/json");
        response.addHeader("X-Request-Id", "req-123");

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.responseHeaders()).containsEntry("Content-Type", "application/json");
        assertThat(event.responseHeaders()).containsEntry("X-Request-Id", "req-123");
    }

    @Test
    void masksSensitiveResponseHeaders() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        response.addHeader("Set-Cookie", "session=abc123");
        response.addHeader("Content-Type", "application/json");

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.responseHeaders()).containsEntry("Set-Cookie", "******");
        assertThat(event.responseHeaders()).containsEntry("Content-Type", "application/json");
    }

    @Test
    void aValueOverTheCapIsCutAtItsLastWhitespaceAndCounted() throws Exception {
        String captured = capturedHeaderValue("word ".repeat(1000));

        assertThat(captured)
                .hasSize(4094 + "... 906 characters omitted".length())
                .endsWith("word... 906 characters omitted");
    }

    @Test
    void aValueOverTheCapWithNoWhitespaceIsNotShownInPart() throws Exception {
        assertThat(capturedHeaderValue("a".repeat(5000))).isEqualTo("... 5000 characters omitted");
    }

    /** Cut at the cap itself, the token's first 16 characters would be left where no pattern recognises them. */
    @Test
    void aCredentialStraddlingTheCapNeverShowsItsHead() throws Exception {
        String token = "ghp" + "_16C7e42F292c6912E7710c838347Ae178B4a";

        String captured = capturedHeaderValue("x ".repeat(2040) + token + " tail");

        assertThat(captured).doesNotContain("ghp_").endsWith("... 46 characters omitted");
    }

    /** Cut inside a quoted secret that holds whitespace, the closing quote is gone and the rest is masked. */
    @Test
    void aQuotedSecretStraddlingTheCapNeverShowsItsHead() throws Exception {
        String captured = capturedHeaderValue("x ".repeat(2035) + "password=\"correct horse battery staple\" tail");

        assertThat(captured).isEqualTo("x ".repeat(2035) + "password=\"******");
    }

    @Test
    void aValueAtTheCapIsKeptWhole() throws Exception {
        String atTheCap = "a".repeat(RequestDetails.MAX_VALUE_LENGTH);

        assertThat(capturedHeaderValue(atTheCap)).isEqualTo(atTheCap);
    }

    @Test
    void aTokenThatEndsAtTheCapIsKept() throws Exception {
        String endsAtTheCap = "a".repeat(RequestDetails.MAX_VALUE_LENGTH);

        assertThat(capturedHeaderValue(endsAtTheCap + " tail")).isEqualTo(endsAtTheCap + "... 5 characters omitted");
    }

    /** A repeated header is captured as one value, so that value is what the cap measures. */
    @Test
    void aRepeatedHeaderIsCappedAsItsJoinedValue() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.addHeader("X-Notes", "word ".repeat(500));
        request.addHeader("X-Notes", "word ".repeat(500));
        response.addHeader("X-Notes", "word ".repeat(500));
        response.addHeader("X-Notes", "word ".repeat(500));

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.requestHeaders().get("X-Notes")).endsWith("word... 906 characters omitted");
        assertThat(event.responseHeaders().get("X-Notes")).endsWith("word... 906 characters omitted");
    }

    @Test
    void capsQueryAndFormParameterValuesAndResponseHeaders() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.setMethod("POST");
        request.setContentType("application/x-www-form-urlencoded");
        request.setQueryString("q=" + "word+".repeat(1000));
        request.setParameter("comment", "word ".repeat(1000));
        response.addHeader("X-Notes", "word ".repeat(1000));
        applicationReads(HttpServletRequest::getParameterMap);

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.queryParams().get("q")).singleElement(STRING).endsWith("word... 906 characters omitted");
        assertThat(event.formParams().get("comment")).singleElement(STRING).endsWith("word... 906 characters omitted");
        assertThat(event.responseHeaders().get("X-Notes")).endsWith("word... 906 characters omitted");
    }

    @Test
    void capsAnOverlongParameterName() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.setMethod("POST");
        request.setContentType("application/x-www-form-urlencoded");
        request.setQueryString("q".repeat(5000) + "=1");
        request.setParameter("f".repeat(6000), "2");
        applicationReads(HttpServletRequest::getParameterMap);

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.queryParams()).containsOnlyKeys("... 5000 characters omitted");
        assertThat(event.formParams()).containsOnlyKeys("... 6000 characters omitted");
    }

    @Test
    void capsAnOverlongQueryKeyThatCannotBeDecoded() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.setQueryString("%zz" + "q".repeat(5000) + "=1");

        filter.doFilter(request, response, chain);

        assertThat(publishedEvent().queryParams()).isEqualTo(Map.of("... 5003 characters omitted", List.of("******")));
    }

    @Test
    void capsAnOverlongHeaderName() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.addHeader("q".repeat(5000), "1");
        response.addHeader("s".repeat(6000), "2");

        filter.doFilter(request, response, chain);

        RequestCompletedEvent event = publishedEvent();
        assertThat(event.requestHeaders()).containsOnlyKeys("... 5000 characters omitted");
        assertThat(event.responseHeaders()).containsKey("... 6000 characters omitted");
    }

    @Test
    void aFormParameterWithANullValueIsCapturedAsNull() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.setMethod("POST");
        request.setContentType("application/x-www-form-urlencoded");
        request.setParameter("flag", (String) null);
        applicationReads(HttpServletRequest::getParameterMap);

        filter.doFilter(request, response, chain);

        assertThat(publishedEvent().formParams().get("flag")).containsExactly((String) null);
    }

    @Test
    void aSensitiveNameStillMasksAnOverlongValueWhole() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.addHeader("Authorization", "Bearer " + "a".repeat(5000));

        filter.doFilter(request, response, chain);

        assertThat(publishedEvent().requestHeaders()).containsEntry("Authorization", "******");
    }

    @Test
    void setsServerTimingHeader() throws Exception {
        CurrentSpans.stub(tracer, "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", true);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("Server-Timing"))
                .isEqualTo("trace;desc=\"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01\"");
    }

    @Test
    void setsServerTimingHeaderWithUnsampledFlag() throws Exception {
        CurrentSpans.stub(tracer, "abc123", "def456", false);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("Server-Timing")).isEqualTo("trace;desc=\"00-abc123-def456-00\"");
    }

    @Test
    void doesNotSetServerTimingHeaderWhenNoSpan() throws Exception {
        when(tracer.currentSpan()).thenReturn(null);

        filter.doFilter(request, response, chain);

        assertThat(response.containsHeader("Server-Timing")).isFalse();
    }

    @Test
    void warnsAndPublishesNothingWhenCaptureFails() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request = new MockHttpServletRequest("GET", "/api/users") {
            @Override
            public Enumeration<String> getHeaderNames() {
                throw new IllegalStateException("boom");
            }
        };
        request.setServletPath("/api/users");

        try (LogCapture capture = LogCapture.attach(RequestCaptureFilter.class)) {
            filter.doFilter(request, response, chain);

            assertThat(requestGivenToTheChain().getRequest()).isSameAs(request);
            verify(eventPublisher, never()).publishEvent(any());
            assertWarnedOnceThatTheCaptureFailed(capture, "boom");
        }
    }

    /**
     * Peekaboot's own endpoints stay excluded behind a context path: the filter matches on
     * the container's mapped path, which is context-relative, not on the raw request URI.
     */
    @Test
    void skipsPeekabootPathsBehindAContextPath() throws Exception {
        request.setContextPath("/app");
        request.setRequestURI("/app/peekaboot/api/traces");
        request.setServletPath("/peekaboot/api/traces");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** The captured path is what the browser addressed - context path included. */
    @Test
    void capturesTheRequestUriWithItsContextPath() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.setContextPath("/app");
        request.setRequestURI("/app/api/users");
        request.setServletPath("/api/users");

        filter.doFilter(request, response, chain);

        assertThat(publishedEvent().path()).isEqualTo("/app/api/users");
    }

    /**
     * A DeferredResult/Callable/SseEmitter handler returns from the initial dispatch before
     * its result exists; the status and duration are only known once the async cycle
     * completes, so capture waits for the container's completion callback.
     */
    @Test
    void capturesAnAsyncRequestOnCompletionRatherThanOnHandOff() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        MockAsyncContext asyncContext = startAsync();

        filter.doFilter(request, response, chain);

        verify(eventPublisher, never()).publishEvent(any());
        assertThat(asyncContext.getListeners()).hasSize(1);

        response.setStatus(500);
        asyncContext.complete();

        assertThat(publishedEvent().status()).isEqualTo(500);
    }

    /** The completion callback runs on a container thread with no current span; the id comes from the request thread. */
    @Test
    void asyncCaptureKeepsTheTraceIdResolvedOnTheRequestThread() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        MockAsyncContext asyncContext = startAsync();
        filter.doFilter(request, response, chain);
        clearInvocations(tracer);

        asyncContext.complete();

        verify(tracer, never()).currentSpan();
        assertThat(publishedEvent().traceId()).isEqualTo("trace1");
    }

    /** A listener is dropped when a new async cycle starts unless it re-registers itself. */
    @Test
    void asyncCaptureFollowsARestartedAsyncCycle() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        MockAsyncContext asyncContext = startAsync();
        filter.doFilter(request, response, chain);
        AsyncListener listener = asyncContext.getListeners().get(0);

        MockAsyncContext restarted = new MockAsyncContext(request, response);
        listener.onStartAsync(new AsyncEvent(restarted));

        assertThat(restarted.getListeners()).containsExactly(listener);
    }

    /** The container signals the timeout, then completes the cycle; the one capture carries the status it ended with. */
    @Test
    void aTimedOutAsyncRequestIsCapturedOnceWithItsFinalStatus() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        MockAsyncContext asyncContext = startAsync();
        filter.doFilter(request, response, chain);
        AsyncListener listener = asyncContext.getListeners().get(0);

        listener.onTimeout(new AsyncEvent(asyncContext));
        verify(eventPublisher, never()).publishEvent(any());
        response.setStatus(503);
        asyncContext.complete();

        assertThat(publishedEvent().status()).isEqualTo(503);
    }

    @Test
    void aFailedAsyncRequestIsCapturedOnceWithItsFinalStatus() throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        MockAsyncContext asyncContext = startAsync();
        filter.doFilter(request, response, chain);
        AsyncListener listener = asyncContext.getListeners().get(0);

        listener.onError(new AsyncEvent(asyncContext, new IllegalStateException("handler failed")));
        verify(eventPublisher, never()).publishEvent(any());
        response.setStatus(500);
        asyncContext.complete();

        assertThat(publishedEvent().status()).isEqualTo(500);
    }

    /** The listener is registered in a finally block, where an exception would replace the one in flight. */
    @ParameterizedTest
    @EnumSource(AsyncCall.class)
    void aListenerTheContainerRefusesDoesNotReplaceTheHandlersException(AsyncCall refused) throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request = new RefusingAsyncRequest(refused);
        doThrow(new ServletException("handler failed")).when(chain).doFilter(any(), any());

        try (LogCapture capture = LogCapture.attach(RequestCaptureFilter.class)) {
            assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                    .isInstanceOf(ServletException.class)
                    .hasMessage("handler failed");

            assertWarnedOnceThatTheCaptureFailed(capture, "async cycle already completed");
        }
    }

    @ParameterizedTest
    @EnumSource(AsyncCall.class)
    void aListenerTheContainerRefusesIsLoggedAndTheRequestGoesOn(AsyncCall refused) throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request = new RefusingAsyncRequest(refused);

        try (LogCapture capture = LogCapture.attach(RequestCaptureFilter.class)) {
            filter.doFilter(request, response, chain);

            assertWarnedOnceThatTheCaptureFailed(capture, "async cycle already completed");
        }

        assertThat(requestGivenToTheChain().getRequest()).isSameAs(request);
        verifyNoInteractions(eventPublisher);
    }

    private RequestCompletedEvent publishedEvent() {
        ArgumentCaptor<RequestCompletedEvent> captor = ArgumentCaptor.forClass(RequestCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    private static void assertWarnedOnceThatTheCaptureFailed(LogCapture capture, String cause) {
        assertThat(capture.appender().list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo("Failed to capture request details");
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo(cause);
        });
    }

    private String capturedHeaderValue(String sent) throws Exception {
        CurrentSpans.stub(tracer, "trace1");
        request.addHeader("X-Notes", sent);

        filter.doFilter(request, response, chain);

        return publishedEvent().requestHeaders().get("X-Notes");
    }

    private ParameterReads requestGivenToTheChain() throws Exception {
        ArgumentCaptor<ServletRequest> captor = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(captor.capture(), any());
        assertThat(captor.getValue()).isInstanceOf(ParameterReads.class);
        return (ParameterReads) captor.getValue();
    }

    /** Stands in for the application: runs {@code read} on the request the chain was given. */
    private void applicationReads(ThrowingConsumer<HttpServletRequest> read) throws Exception {
        doAnswer(invocation -> {
                    read.accept(invocation.getArgument(0));
                    return null;
                })
                .when(chain)
                .doFilter(any(), any());
    }

    static Stream<Arguments> otherParameterReads() {
        return Stream.of(
                parameterRead("getParameterNames", HttpServletRequest::getParameterNames),
                parameterRead("getParameterValues", read -> read.getParameterValues("username")),
                parameterRead("getPart", read -> read.getPart("username")));
    }

    private static Arguments parameterRead(String name, ThrowingConsumer<HttpServletRequest> read) {
        return Arguments.of(Named.of(name, read));
    }

    /** Puts the request into the state a handler leaves it in after handing off asynchronously. */
    private MockAsyncContext startAsync() {
        request.setAsyncSupported(true);
        return (MockAsyncContext) request.startAsync(request, response);
    }

    /** A request whose form body the container has not parsed: asking for a parameter or a part would parse it. */
    private static final class UnparsedFormRequest extends MockHttpServletRequest {

        private UnparsedFormRequest(String method, String path) {
            super(method, path);
            setServletPath(path);
        }

        @Override
        public String getParameter(String name) {
            throw parsed();
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            throw parsed();
        }

        @Override
        public Enumeration<String> getParameterNames() {
            throw parsed();
        }

        @Override
        public String[] getParameterValues(String name) {
            throw parsed();
        }

        @Override
        public Collection<Part> getParts() {
            throw parsed();
        }

        private static AssertionError parsed() {
            return new AssertionError("the capture parsed the body");
        }
    }

    /** The calls the filter makes to register its listener. */
    enum AsyncCall {
        IS_ASYNC_STARTED,
        GET_ASYNC_CONTEXT,
        ADD_LISTENER
    }

    /** A request in async mode whose cycle is over: the container refuses one of the calls that register a listener. */
    private static final class RefusingAsyncRequest extends MockHttpServletRequest {

        private final AsyncCall refused;

        private RefusingAsyncRequest(AsyncCall refused) {
            super("GET", "/api/users");
            setServletPath("/api/users");
            this.refused = refused;
        }

        @Override
        public boolean isAsyncStarted() {
            refuse(AsyncCall.IS_ASYNC_STARTED);
            return true;
        }

        @Override
        public AsyncContext getAsyncContext() {
            refuse(AsyncCall.GET_ASYNC_CONTEXT);
            return new MockAsyncContext(this, null) {
                @Override
                public void addListener(AsyncListener listener) {
                    refuse(AsyncCall.ADD_LISTENER);
                }
            };
        }

        private void refuse(AsyncCall call) {
            if (call == refused) {
                throw new IllegalStateException("async cycle already completed");
            }
        }
    }

    public static class TestController {
        public void getUsers() {}
    }
}

package org.peekaboot.backend.controller;

import static org.peekaboot.backend.testsupport.Spans.jdbcConnection;
import static org.peekaboot.backend.testsupport.Spans.span;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.tracing.Span;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.peekaboot.backend.mapper.trace.SqlFormatter;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.problem.ApiProblemHandler;
import org.peekaboot.backend.service.TraceInsightsService;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real Spring MVC dispatch over a real store, so what is tested is how a request's parameters reach the listing. */
class TraceControllerTest {

    private final InMemoryTraceStore store = TraceStores.withDefaults();

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TraceController(new TraceInsightsService(
                    store,
                    new MaskingEngine(),
                    SqlFormatter.NONE,
                    new PeekabootTracingProperties(),
                    new StackTraceRules(List.of(), List.of()))))
            .addInterceptors(new QueryParameterGuard())
            .setControllerAdvice(new ApiProblemHandler())
            .build();

    /**
     * Each parameter binds by its record component's name. The newest trace is a pool connection
     * outside Errors, the two newest errors are pool connections, and the default view hides
     * maintenance, so leaving any one parameter unbound lists or counts something else.
     */
    @Test
    void bindsEveryListingParameterByName() throws Exception {
        store.addSpan(span("failed-root")
                .in("failed")
                .exception("java.lang.IllegalStateException", "boom")
                .build());
        store.addSpan(jdbcConnection("pool-old-root")
                .in("pool-old")
                .exception("java.sql.SQLException", "refused")
                .build());
        store.addSpan(jdbcConnection("pool-root")
                .in("pool")
                .exception("java.sql.SQLException", "refused")
                .build());
        store.addSpan(span("ok-root").in("ok").build());
        store.addSpan(jdbcConnection("pool-ok-root").in("pool-ok").build());

        mvc.perform(get("/peekaboot/api/traces")
                        .param("limit", "1")
                        .param("bucket", "errors")
                        .param("rootActionType", "*")
                        .param("rootOperation", "connection")
                        .param("view", "full"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traces.length()").value(1))
                .andExpect(jsonPath("$.traces[0].summary.traceId").value("pool"))
                .andExpect(jsonPath("$.traces[0].data.rootSpan.spanId").value("pool-root"))
                .andExpect(jsonPath("$.bucketCounts.errors").value(3))
                .andExpect(jsonPath("$.filteredBucketCounts.all").value(3))
                .andExpect(jsonPath("$.filteredBucketCounts.errors").value(2));
    }

    /** Spring's record binding falls back to a same-named request header, which would put the listing in a client's or proxy's hands. */
    @ParameterizedTest
    @CsvSource({"Limit, 0", "View, full", "bucket, eror", "statusCode, 500", "Cursor, x"})
    void ignoresARequestHeaderNamedLikeAParameter(String header, String value) throws Exception {
        store.addSpan(span("failed-root")
                .in("failed")
                .exception("java.lang.IllegalStateException", "boom")
                .build());
        store.addSpan(span("ok-root").in("ok").build());

        mvc.perform(get("/peekaboot/api/traces").header(header, value))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traces[*].summary.traceId").value(Matchers.contains("ok", "failed")))
                .andExpect(jsonPath("$.traces[*].data")
                        .value(Matchers.contains(Matchers.nullValue(), Matchers.nullValue())));
    }

    /** Three limits name no one page size, so the repeat is refused before any of them is read. */
    @Test
    void refusesARepeatedLimit() throws Exception {
        mvc.perform(get("/peekaboot/api/traces").param("limit", "1", "2", "3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parameter").value("limit"))
                .andExpect(jsonPath("$.detail").value("limit was sent 3 times. Send it once."));
    }

    /** Two cursors that each name a page still name no one page between them. */
    @Test
    void refusesARepeatedCursor() throws Exception {
        mvc.perform(get("/peekaboot/api/traces").param("cursor", "dC43LjEuMS4tMQ", "dC43LjIuMi4tMQ"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parameter").value("cursor"))
                .andExpect(jsonPath("$.detail").value("cursor was sent 2 times. Send it once."));
    }

    /** Joined with a comma, a repeated route or path would read as one value that names neither. */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {"statusCode | 500 | 503", "route | /a | /b", "path | /a | b"})
    void refusesARepeatedFilter(String parameter, String first, String second) throws Exception {
        mvc.perform(get("/peekaboot/api/traces").param(parameter, first, second))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parameter").value(parameter))
                .andExpect(jsonPath("$.detail").value(parameter + " was sent 2 times. Send it once."));
    }

    /** A route pattern and a path can each carry a comma of their own. */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {"path | /a,b", "route | /items/{id:\\d{1,3}}"})
    void bindsASingleValueWithACommaAsWritten(String parameter, String value) throws Exception {
        store.addSpan(span("comma-root")
                .in("comma")
                .kind(Span.Kind.SERVER)
                .tag("http.route", "/items/{id:\\d{1,3}}")
                .tag("url.path", "/a,b/1")
                .build());
        store.addSpan(span("plain-root")
                .in("plain")
                .kind(Span.Kind.SERVER)
                .tag("http.route", "/items/{id}")
                .tag("url.path", "/a/1")
                .build());

        mvc.perform(get("/peekaboot/api/traces").param(parameter, value))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traces[*].summary.traceId").value(Matchers.contains("comma")));
    }

    /** The refusal lists what the listing takes, in its record's order. */
    @Test
    void refusesAMisspelledParameterNamingWhatItTakes() throws Exception {
        mvc.perform(get("/peekaboot/api/traces").param("buckett", "errors"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parameter").value("buckett"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "Unknown parameter 'buckett'. Accepted: limit, cursor, bucket, rootActionType,"
                                                + " rootOperation, route, path, statusCode, outcome, minDurationMs, since, until, view."));
    }

    @Test
    void refusesABadLimitWithAProblemNamingIt() throws Exception {
        mvc.perform(get("/peekaboot/api/traces").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.parameter").value("limit"))
                .andExpect(jsonPath("$.detail").value("limit must be an integer from 1 to 10000, got '0'."));
    }

    @Test
    void servesAStoredTrace() throws Exception {
        store.addSpan(span("ok-root").in("ok").build());

        mvc.perform(get("/peekaboot/api/traces/ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.traceId").value("ok"))
                .andExpect(jsonPath("$.data.rootSpan.spanId").value("ok-root"));
    }

    @Test
    void answersAnUnknownTraceWithANotFoundProblem() throws Exception {
        mvc.perform(get("/peekaboot/api/traces/nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("nope")))
                .andExpect(jsonPath("$.parameter").value(Matchers.nullValue()));
    }

    @Test
    void servesTheRoutes() throws Exception {
        store.addSpan(span("ok-root")
                .in("ok")
                .kind(Span.Kind.SERVER)
                .tag("http.request.method", "GET")
                .tag("http.route", "/r")
                .tag("http.response.status_code", "200")
                .build());

        mvc.perform(get("/peekaboot/api/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window.traceCount").value(1))
                .andExpect(jsonPath("$.routes[0].route").value("/r"))
                .andExpect(jsonPath("$.routes[0].latestErrorTraceId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.routes[0].issueCounts.CLIENT_ERROR").value(0));
    }
}

package org.peekaboot.backend.controller;

import static org.peekaboot.backend.testsupport.Spans.jdbcConnection;
import static org.peekaboot.backend.testsupport.Spans.span;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    @CsvSource({"Limit, 0", "View, full", "bucket, eror"})
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
}

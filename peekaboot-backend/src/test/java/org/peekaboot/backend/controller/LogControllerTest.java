package org.peekaboot.backend.controller;

import static org.peekaboot.backend.testsupport.Logs.untraced;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.problem.ApiProblemHandler;
import org.peekaboot.backend.service.LogSearchService;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real Spring MVC dispatch over a real store, so what is tested is how a request's parameters reach the search. */
class LogControllerTest {

    private final InMemoryTraceStore store = TraceStores.withDefaults();

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new LogController(
                    new LogSearchService(store, new MaskingEngine(), new StackTraceRules(List.of(), List.of()))))
            .addInterceptors(new QueryParameterGuard())
            .setControllerAdvice(new ApiProblemHandler())
            .build();

    /** Spring's record binding falls back to a same-named request header, which would put the search in a client's or proxy's hands. */
    @ParameterizedTest
    @CsvSource({"Limit, 0", "level, error", "Q, nothing", "Cursor, x"})
    void ignoresARequestHeaderNamedLikeAParameter(String header, String value) throws Exception {
        store.addLog(untraced().saying("first").build());
        store.addLog(untraced().saying("second").build());

        mvc.perform(get("/peekaboot/api/logs").header(header, value))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs[*].message").value(Matchers.contains("second", "first")));
    }

    /** Two texts name no one search between them. */
    @Test
    void refusesARepeatedParameter() throws Exception {
        mvc.perform(get("/peekaboot/api/logs").param("q", "first", "second"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parameter").value("q"))
                .andExpect(jsonPath("$.detail").value("q was sent 2 times. Send it once."));
    }
}

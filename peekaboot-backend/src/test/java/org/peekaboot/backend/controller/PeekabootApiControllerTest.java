package org.peekaboot.backend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.domain.api.ApiDocument;
import org.peekaboot.backend.problem.ApiProblemHandler;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real Spring MVC dispatch behind a context path, through the guard every API request passes. */
class PeekabootApiControllerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new PeekabootApiController(ApiDocument.bundled(), new PeekabootPaths("/actuator", "/ctx", "")))
            .addInterceptors(new QueryParameterGuard())
            .setControllerAdvice(new ApiProblemHandler())
            .build();

    @Test
    void servesTheDocumentWithTheRequestsContextPathAsItsServer() throws Exception {
        mvc.perform(get("/ctx/peekaboot/api/openapi.json").contextPath("/ctx"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.openapi").value("3.1.1"))
                .andExpect(jsonPath("$.servers[0].url").value("/ctx"));
    }

    /** The guard reads what a handler takes off its signature, and this one takes nothing. */
    @Test
    void refusesAParameterTheEndpointDoesNotTake() throws Exception {
        mvc.perform(get("/ctx/peekaboot/api/openapi.json").contextPath("/ctx").param("format", "yaml"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parameter").value("format"))
                .andExpect(jsonPath("$.detail").value("Unknown parameter 'format'. Accepted: none."));
    }
}

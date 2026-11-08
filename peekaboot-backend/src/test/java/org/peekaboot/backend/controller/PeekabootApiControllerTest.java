package org.peekaboot.backend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Test
    void servesTheIndexWithEveryPathBehindTheContextPath() throws Exception {
        mvc.perform(get("/ctx/peekaboot/api").contextPath("/ctx"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.openapi").value("/ctx/peekaboot/api/openapi.json"))
                .andExpect(jsonPath("$.endpoints[0].path").value("/ctx/peekaboot/api"));
    }

    /** The guard reads what a handler takes off its signature; neither handler takes anything. */
    @ParameterizedTest
    @ValueSource(strings = {"/ctx/peekaboot/api/openapi.json", "/ctx/peekaboot/api"})
    void refusesAParameterNeitherEndpointTakes(String path) throws Exception {
        mvc.perform(get(path).contextPath("/ctx").param("format", "yaml"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parameter").value("format"))
                .andExpect(jsonPath("$.detail").value("Unknown parameter 'format'. Accepted: none."));
    }
}

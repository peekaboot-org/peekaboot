package org.peekaboot.backend.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.problem.ApiProblemHandler;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Real Spring MVC dispatch through the guard, over a controller that binds each kind of argument Peekaboot's controllers do. */
class QueryParameterGuardTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FixtureController())
            .addInterceptors(new QueryParameterGuard())
            .setControllerAdvice(new ApiProblemHandler())
            .build();

    record FixtureParameters(
            @Nullable String alpha, @Nullable String beta) {}

    @RestController
    @RequestMapping("/peekaboot/api/fixture")
    static class FixtureController {

        @GetMapping("/record")
        String record(@ModelAttribute FixtureParameters parameters) {
            return parameters.alpha() + "," + parameters.beta();
        }

        @GetMapping("/{id}")
        String one(@PathVariable(name = "id") String id, @RequestParam(name = "gamma", required = false) String gamma) {
            return id + "," + gamma;
        }

        @GetMapping("/none")
        String none() {
            return "none";
        }

        @GetMapping("/raw")
        String raw(HttpServletRequest request) {
            return request.getQueryString();
        }
    }

    @Test
    void takesTheComponentsOfTheRecordItBinds() throws Exception {
        mvc.perform(get("/peekaboot/api/fixture/record").param("alpha", "a").param("beta", "b"))
                .andExpect(status().isOk())
                .andExpect(content().string("a,b"));
    }

    /**
     * Spring binds alpha by its exact name, and takes !alpha as alpha's value when alpha is absent;
     * only the name the endpoint lists is accepted, so each of these is refused.
     */
    @ParameterizedTest
    @ValueSource(strings = {"alpah", "Alpha", "_alpha", "!alpha"})
    void refusesANameTheRecordDoesNotDeclare(String name) throws Exception {
        mvc.perform(get("/peekaboot/api/fixture/record").param(name, "a"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.parameter").value(name))
                .andExpect(jsonPath("$.detail").value("Unknown parameter '" + name + "'. Accepted: alpha, beta."));
    }

    @Test
    void takesANamedRequestParameterBesideAPathVariable() throws Exception {
        mvc.perform(get("/peekaboot/api/fixture/x").param("gamma", "g"))
                .andExpect(status().isOk())
                .andExpect(content().string("x,g"));
    }

    /** A path variable is part of the path; sent as a query parameter it is one the endpoint does not take. */
    @Test
    void refusesAPathVariableSentAsAParameter() throws Exception {
        mvc.perform(get("/peekaboot/api/fixture/x").param("id", "y"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parameter").value("id"))
                .andExpect(jsonPath("$.detail").value("Unknown parameter 'id'. Accepted: gamma."));
    }

    @Test
    void anEndpointWithoutParametersRefusesAny() throws Exception {
        mvc.perform(get("/peekaboot/api/fixture/none").param("x", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parameter").value("x"))
                .andExpect(jsonPath("$.detail").value("Unknown parameter 'x'. Accepted: none."));
    }

    @Test
    void anEndpointWithoutParametersAnswersARequestWithout() throws Exception {
        mvc.perform(get("/peekaboot/api/fixture/none")).andExpect(status().isOk());
    }

    @Test
    void refusesADeclaredParameterSentTwice() throws Exception {
        mvc.perform(get("/peekaboot/api/fixture/x").param("gamma", "g", "h"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parameter").value("gamma"))
                .andExpect(jsonPath("$.detail").value("gamma was sent 2 times. Send it once."));
    }

    /** The first parameter at fault in the query is the one named. */
    @Test
    void namesTheFirstParameterAtFault() throws Exception {
        mvc.perform(get("/peekaboot/api/fixture/record?alpha=a&alpha=b&zeta=z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parameter").value("alpha"));
    }

    /** A form login sends the browser back with Spring Security's marker, so a login never ends in a 400. */
    @Test
    void letsSpringSecuritysLoginReturnMarkerThrough() throws Exception {
        mvc.perform(get("/peekaboot/api/fixture/none").param("continue", "")).andExpect(status().isOk());
        mvc.perform(get("/peekaboot/api/fixture/record").param("alpha", "a").param("continue", ""))
                .andExpect(status().isOk())
                .andExpect(content().string("a,null"));
    }

    /** A handler that reads the request itself could take anything, so the guard refuses to guess. */
    @Test
    void failsAHandlerWhoseArgumentsItCannotRead() {
        assertThatThrownBy(() -> mvc.perform(get("/peekaboot/api/fixture/raw")))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("QueryParameterGuard cannot tell which query parameters ")
                .hasMessageContaining("HttpServletRequest");
    }
}

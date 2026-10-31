package org.peekaboot.backend.problem;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.peekaboot.problemfixture.ApplicationEndpoint;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Real Spring MVC dispatch, so the advice's ordering and scoping are what is tested. */
class ApiProblemHandlerTest {

    @RestController
    static class PeekabootEndpoint {
        @GetMapping("/peekaboot/api/test")
        String get(@RequestParam("limit") int limit) {
            if (limit == 0) {
                throw new ApiProblemException(ApiProblem.notFound("Nothing at 0."));
            }
            if (limit == 503) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Too many streams.");
            }
            return "ok";
        }
    }

    /** An application-wide advice of the kind an application registers: it would answer everything. */
    @RestControllerAdvice
    static class ApplicationWideAdvice {
        @ExceptionHandler(Exception.class)
        ResponseEntity<String> any(Exception e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("the application's");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PeekabootEndpoint(), new ApplicationEndpoint())
            .setControllerAdvice(new ApplicationWideAdvice(), new ApiProblemHandler())
            .build();

    @Test
    void answersAMistypedParameterNamingIt() throws Exception {
        mvc.perform(get("/peekaboot/api/test").param("limit", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.parameter").value("limit"))
                .andExpect(jsonPath("$.detail").value("limit must be an integer, got 'abc'."));
    }

    @Test
    void answersAMissingParameterNamingIt() throws Exception {
        mvc.perform(get("/peekaboot/api/test"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.parameter").value("limit"))
                .andExpect(jsonPath("$.detail").value("limit is required."));
    }

    @Test
    void answersARefusalWithItsOwnProblemAndANullParameter() throws Exception {
        mvc.perform(get("/peekaboot/api/test").param("limit", "0"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("Nothing at 0."))
                .andExpect(jsonPath("$.parameter").value(Matchers.nullValue()));
    }

    /** Asked the way the insights stream is asked, so the problem is written against the request's Accept header. */
    @Test
    void answersAStatusExceptionWithItsStatusAndReason() throws Exception {
        mvc.perform(get("/peekaboot/api/test").param("limit", "503").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Service Unavailable"))
                .andExpect(jsonPath("$.detail").value("Too many streams."));
    }

    @Test
    void leavesTheApplicationsControllersAlone() throws Exception {
        mvc.perform(get("/app/test").param("limit", "abc"))
                .andExpect(status().isConflict())
                .andExpect(content().string("the application's"));
    }
}

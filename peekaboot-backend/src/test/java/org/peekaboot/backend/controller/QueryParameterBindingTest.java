package org.peekaboot.backend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.peekaboot.problemfixture.ApplicationEndpoint;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

/** Real Spring MVC dispatch, so the advice's reach and scoping are what is tested. */
class QueryParameterBindingTest {

    /** Declares no binder of its own. */
    @RestController
    static class PeekabootEndpoint {
        @GetMapping("/peekaboot/api/test")
        String get(@ModelAttribute ApplicationEndpoint.Page page) {
            return String.valueOf(page.limit());
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PeekabootEndpoint(), new ApplicationEndpoint())
            .setControllerAdvice(new QueryParameterBinding())
            .build();

    @Test
    void bindsAPeekabootControllersRecordFromTheQueryStringAlone() throws Exception {
        mvc.perform(get("/peekaboot/api/test").header("Limit", "5"))
                .andExpect(status().isOk())
                .andExpect(content().string("null"));
    }

    /** Spring's header fallback is the application's to keep or turn off for its own records. */
    @Test
    void leavesTheApplicationsControllersAlone() throws Exception {
        mvc.perform(get("/app/page").header("Limit", "5"))
                .andExpect(status().isOk())
                .andExpect(content().string("5"));
    }
}

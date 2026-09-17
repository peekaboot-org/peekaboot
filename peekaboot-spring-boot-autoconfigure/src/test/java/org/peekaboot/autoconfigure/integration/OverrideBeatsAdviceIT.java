package org.peekaboot.autoconfigure.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.client.RestClient;

/**
 * Reproduces waw's shape: an application whose branded error page comes from a
 * {@code @ControllerAdvice} completes the response inside the REQUEST dispatch, so the container
 * never starts an ERROR dispatch and no {@code ErrorViewResolver} is ever consulted - the
 * mechanism the override's other path depends on. The
 * {@code HandlerExceptionResolver} registered alongside it beats the advice directly, inside
 * that same REQUEST dispatch, for an HTML request; a JSON request still falls through to it
 * untouched.
 */
@SpringBootTest(
        classes = {TestApplication.class, OverrideBeatsAdviceIT.AppErrorPage.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"peekaboot.error-page.enabled=true", "peekaboot.error-page.override=true"})
@ActiveProfiles("integration")
class OverrideBeatsAdviceIT {

    @LocalServerPort
    private int port;

    @Test
    void peekabootsPageWinsOverTheApplicationsAdviceForAnHtmlRequest() {
        ResponseEntity<String> response = client().get()
                .uri("/throwing")
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).contains("pk-error__frame");
        assertThat(response.getBody()).doesNotContain("THE APPLICATION'S OWN PROBLEM");
    }

    /**
     * The HTML check's other side: an API client asking for JSON keeps the application's own
     * response - an untouched problem-detail body, not an HTML page repurposed as JSON.
     */
    @Test
    void theApplicationsAdviceStillAnswersAJsonRequestWithItsOwnProblemDetail() {
        ResponseEntity<String> response = client().get()
                .uri("/throwing")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).contains("THE APPLICATION'S OWN PROBLEM");
        assertThat(response.getBody()).doesNotContain("pk-error__frame");
    }

    private RestClient client() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> {})
                .build();
    }

    @Controller
    @ControllerAdvice
    static class AppErrorPage {

        @Bean
        AppErrorPage self() {
            return this;
        }

        @ExceptionHandler(Exception.class)
        ResponseEntity<ProblemDetail> render() {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(ProblemDetail.forStatusAndDetail(
                            HttpStatus.INTERNAL_SERVER_ERROR, "THE APPLICATION'S OWN PROBLEM DETAIL"));
        }
    }
}

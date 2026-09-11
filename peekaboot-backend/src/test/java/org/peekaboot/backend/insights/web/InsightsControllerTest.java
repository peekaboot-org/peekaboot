package org.peekaboot.backend.insights.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class InsightsControllerTest {

    /** A record rather than a Map, so the body is a Peekaboot type the Peekaboot converter serves. */
    @Test
    void aBadRequestBodyIsAPeekabootTypeWithTheErrorMessage() {
        // the handler under test reads neither collaborator
        InsightsController controller = new InsightsController(null, null);

        ResponseEntity<InsightsController.ErrorResponse> response =
                controller.badRequest(new IllegalArgumentException("Unknown insights level: 9"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().error()).isEqualTo("Unknown insights level: 9");
    }
}

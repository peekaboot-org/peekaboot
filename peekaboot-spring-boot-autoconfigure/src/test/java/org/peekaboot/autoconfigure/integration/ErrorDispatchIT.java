package org.peekaboot.autoconfigure.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * The error dispatch end to end on a real container: Peekaboot's page renders where Boot's
 * whitelabel page would, and the bar on it reports the request that failed rather than the
 * /error dispatch that rendered it.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class ErrorDispatchIT {

    /** RequestCaptureFilter answers every captured request with its trace id in Server-Timing. */
    private static final Pattern SERVER_TIMING_TRACE_ID = Pattern.compile("trace;desc=\"00-([0-9a-f]+)-");

    @LocalServerPort
    private int port;

    private LocalServer server;

    @BeforeEach
    void setUp() {
        server = new LocalServer(port);
    }

    @Test
    void aThrownExceptionRendersPeekabootsPageWithTheStackTrace() {
        String page = server.html("/throwing");

        assertThat(page)
                .contains("java.lang.IllegalStateException")
                .contains("gateway unreachable")
                .contains("<span class=\"pk-stack-trace__frame")
                .contains("500");
    }

    @Test
    void theBarOnTheErrorPageReportsTheRequestThatFailed() {
        ResponseEntity<String> response = server.client()
                .get()
                .uri("/throwing")
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .toEntity(String.class);

        JsonNode data = LocalServer.toolbarData(response.getBody());
        assertThat(data.path("path").asString()).isEqualTo("/throwing");
        assertThat(data.path("method").asString()).isEqualTo("GET");
        assertThat(data.path("status").asInt()).isEqualTo(500);
        assertThat(data.path("traceId").asString()).isEqualTo(traceIdOf(response));
    }

    /**
     * A GET original request would pass {@link #theBarOnTheErrorPageReportsTheRequestThatFailed}'s
     * method assertion even if the filter read the ERROR dispatch's own method instead of the
     * stashed original - Tomcat's error dispatch is a GET too. Only a non-GET original discriminates.
     */
    @Test
    void theBarReportsAPostOriginalRequestsMethod() {
        String page = server.client()
                .post()
                .uri("/throwing-post")
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .body(String.class);

        assertThat(LocalServer.toolbarData(page).path("method").asString()).isEqualTo("POST");
    }

    /** An unmapped path is a 404 through the same dispatch, with no exception of its own to show. */
    @Test
    void anUnmappedPathRendersThePageWithItsStatus() {
        String page = server.html("/no-such-page");

        assertThat(page).contains("404").contains("pk-error__");
        assertThat(LocalServer.toolbarData(page).path("path").asString()).isEqualTo("/no-such-page");
    }

    /** Peekaboot's own paths are excluded on the first dispatch, so their error pages carry no bar either. */
    @Test
    void anErrorUnderPeekabootsOwnPrefixGetsNoBar() {
        assertThat(server.html("/peekaboot/api/no-such-endpoint")).doesNotContain("Peekaboot Dev Toolbar");
    }

    /** An XHR is excluded on the first dispatch too, so its error page carries no bar either. */
    @Test
    void anXhrErrorGetsNoBar() {
        String body = server.client()
                .get()
                .uri("/throwing")
                .header("X-Requested-With", "XMLHttpRequest")
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .body(String.class);

        assertThat(body).contains("pk-error__").doesNotContain("Peekaboot Dev Toolbar");
    }

    /** The trace id the same response's Server-Timing header names, so it is checked against the trace actually assigned. */
    private static String traceIdOf(ResponseEntity<String> response) {
        String serverTiming = response.getHeaders().getFirst("Server-Timing");
        Matcher matcher = SERVER_TIMING_TRACE_ID.matcher(serverTiming == null ? "" : serverTiming);
        assertThat(matcher.find())
                .as("Server-Timing must carry the trace id: " + serverTiming)
                .isTrue();
        return matcher.group(1);
    }
}

package org.peekaboot.testingapp.profile;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Looks a person up on the profile service, go-httpbin in the compose run, so the person page's
 * trace carries a CLIENT span to a server outside this application.
 *
 * <p>Built from the auto-configured {@code RestClient.Builder}, like {@code CustomerClient}, for
 * its observation instrumentation. A blank {@code app.profile-service.url} means there is no
 * service: the test profile sets it so, and then no call is made.
 */
@Component
public class ProfileClient {

    private static final Logger log = LoggerFactory.getLogger(ProfileClient.class);

    private final boolean configured;
    private final RestClient restClient;

    public ProfileClient(RestClient.Builder restClientBuilder, @Value("${app.profile-service.url:}") String url) {

        this.configured = !url.isBlank();
        this.restClient = restClientBuilder.baseUrl(url).build();
    }

    public ProfileLookup lookup(long personId, String email, ProfileCall call) {

        if (!configured) {
            return ProfileLookup.notConfigured();
        }
        try {
            JsonNode echo = requireNonNull(
                    restClient
                            .get()
                            .uri(call.uriTemplate(), Map.of("id", personId, "email", Objects.toString(email, "")))
                            .retrieve()
                            .body(JsonNode.class),
                    "the profile service answered with no body");
            return ProfileLookup.answered(new ProfileEcho(
                    echo.path("method").asString(""),
                    echo.path("url").asString(""),
                    echo.path("args").path("email").path(0).asString(""),
                    // go-httpbin echoes header names in Go's canonical case
                    echo.path("headers").path("Traceparent").path(0).asString("")));
        } catch (RuntimeException e) {
            // A failing profile service costs the page its profile section, never the page.
            log.warn("profile lookup for person {} failed: {}", personId, e.getMessage());
            return ProfileLookup.unreachable();
        }
    }
}

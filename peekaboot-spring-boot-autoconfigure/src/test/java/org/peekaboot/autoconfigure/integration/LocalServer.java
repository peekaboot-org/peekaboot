package org.peekaboot.autoconfigure.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The application under test on its random port, asked the way a browser would ask. */
final class LocalServer {

    /** The blob ToolbarShell embeds for toolbar.js, which reads it before it calls home. */
    private static final Pattern TOOLBAR_DATA =
            Pattern.compile("<script id=\"peekaboot-toolbar-data\" type=\"application/json\">(.*?)</script>");

    private final RestClient client;

    LocalServer(int port) {
        this.client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> {})
                .build();
    }

    /** A client that hands back every response, error statuses included, for the test to assert on. */
    RestClient client() {
        return client;
    }

    /** The body of a GET that accepts HTML. */
    String html(String uri) {
        return client.get().uri(uri).accept(MediaType.TEXT_HTML).retrieve().body(String.class);
    }

    /** The toolbar data blob in an injected page, parsed, so a blob that is no valid JSON fails. */
    static JsonNode toolbarData(String html) {
        Matcher matcher = TOOLBAR_DATA.matcher(html);
        assertThat(matcher.find())
                .as("the injected page carries the toolbar data blob")
                .isTrue();
        return JsonMapper.builder().build().readTree(matcher.group(1));
    }
}

package org.peekaboot.testingapp.integration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Peekaboot's HTTP surface on the running app, read the way the dashboard reads it. */
final class PeekabootApi {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient restClient;

    PeekabootApi(int port) {
        this(RestClient.builder().baseUrl("http://localhost:" + port).build());
    }

    private PeekabootApi(RestClient restClient) {
        this.restClient = restClient;
    }

    /** The same app read as {@code user}, with Basic credentials on every request. */
    PeekabootApi withBasicAuth(String user, String password) {
        return new PeekabootApi(restClient
                .mutate()
                .defaultHeaders(headers -> headers.setBasicAuth(user, password))
                .build());
    }

    RestClient restClient() {
        return restClient;
    }

    /** The body of a successful GET; anything but a 2xx throws, so a mistyped path fails here rather than at an assertion. */
    String get(String path) {
        return restClient.get().uri(path).accept(MediaType.ALL).retrieve().body(String.class);
    }

    /** The response headers of a GET, whatever its status: a refused or failing request answers with headers too. */
    HttpHeaders headersOf(String path) {
        return restClient
                .get()
                .uri(path)
                .accept(MediaType.ALL)
                .exchange((request, response) -> HttpHeaders.copyOf(response.getHeaders()));
    }

    /** The status of a GET, whatever it is. */
    HttpStatusCode statusOf(String path) {
        return restClient
                .get()
                .uri(path)
                .accept(MediaType.ALL)
                .exchange((request, response) -> response.getStatusCode());
    }

    JsonNode getJson(String path, Object... uriVariables) {
        String body = restClient
                .get()
                .uri(path, uriVariables)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
        return readJson(body);
    }

    static JsonNode readJson(String json) {
        return JSON.readTree(json);
    }
}

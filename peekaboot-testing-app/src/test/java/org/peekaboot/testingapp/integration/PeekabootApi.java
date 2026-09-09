package org.peekaboot.testingapp.integration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Peekaboot's JSON API on the running app, read the way the dashboard reads it. */
final class PeekabootApi {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient restClient;

    PeekabootApi(int port) {
        this.restClient =
                RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    RestClient restClient() {
        return restClient;
    }

    /** The response headers of a GET, whatever its status: a refused or failing request answers with headers too. */
    HttpHeaders headersOf(String path) {
        return restClient
                .get()
                .uri(path)
                .accept(MediaType.ALL)
                .exchange((request, response) -> HttpHeaders.copyOf(response.getHeaders()));
    }

    JsonNode getJson(String path, Object... uriVariables) {
        String body = restClient
                .get()
                .uri(path, uriVariables)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
        return JSON.readTree(body);
    }
}

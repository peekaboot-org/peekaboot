package org.peekaboot.testingapp.integration;

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

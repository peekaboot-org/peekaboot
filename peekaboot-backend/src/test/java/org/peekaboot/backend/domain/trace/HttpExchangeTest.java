package org.peekaboot.backend.domain.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.testsupport.RequestCompletedEvents;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;

class HttpExchangeTest {

    @Test
    void from_mapsAllFieldsWhenPresent() {
        RequestCompletedEvent event = RequestCompletedEvents.request("trace-1")
                .method("POST")
                .path("/api/users")
                .queryString("sort=name")
                .requestHeaders(Map.of("Content-Type", "application/json"))
                .requestBody("{\"name\":\"joe\"}", false)
                .controller("UserController", "create")
                .queryParams(Map.of("sort", List.of("name")))
                .formParams(Map.of("field", List.of("value")))
                .uploadedFiles(List.of(new RequestCompletedEvent.UploadedFile("file", "photo.png", "image/png", 1024L)))
                .status(201)
                .responseHeaders(Map.of("Location", "/api/users/1"))
                .durationMs(50L)
                .build();

        HttpExchange exchange = HttpExchange.from(event);

        assertThat(exchange.request().method()).isEqualTo("POST");
        assertThat(exchange.request().path()).isEqualTo("/api/users");
        assertThat(exchange.request().query()).isEqualTo("sort=name");
        assertThat(exchange.request().headers()).containsEntry("Content-Type", "application/json");
        assertThat(exchange.request().body().truncated()).isFalse();
        assertThat(exchange.request().body().content()).isEqualTo("{\"name\":\"joe\"}");
        assertThat(exchange.request().controller().className()).isEqualTo("UserController");
        assertThat(exchange.request().controller().method()).isEqualTo("create");
        assertThat(exchange.request().params().query()).containsEntry("sort", List.of("name"));
        assertThat(exchange.request().params().form()).containsEntry("field", List.of("value"));
        assertThat(exchange.request().params().upload()).hasSize(1);
        assertThat(exchange.request().params().upload().getFirst().fieldName()).isEqualTo("file");
        assertThat(exchange.request().params().upload().getFirst().originalFilename())
                .isEqualTo("photo.png");
        assertThat(exchange.request().params().upload().getFirst().contentType())
                .isEqualTo("image/png");
        assertThat(exchange.request().params().upload().getFirst().size()).isEqualTo(1024L);
        assertThat(exchange.response().status()).isEqualTo(201);
        assertThat(exchange.response().headers()).containsEntry("Location", "/api/users/1");
    }

    @Test
    void from_defaultsNullQueryParamsToEmptyMap() {
        HttpExchange exchange = HttpExchange.from(
                RequestCompletedEvents.request("trace-1").queryParams(null).build());

        assertThat(exchange.request().params().query()).isEmpty();
    }

    @Test
    void from_defaultsNullFormParamsToEmptyMap() {
        HttpExchange exchange = HttpExchange.from(
                RequestCompletedEvents.request("trace-1").formParams(null).build());

        assertThat(exchange.request().params().form()).isEmpty();
    }

    @Test
    void from_defaultsNullUploadedFilesToEmptyList() {
        HttpExchange exchange = HttpExchange.from(
                RequestCompletedEvents.request("trace-1").uploadedFiles(null).build());

        assertThat(exchange.request().params().upload()).isEmpty();
    }

    @Test
    void from_defaultsNullRequestHeadersToEmptyMap() {
        HttpExchange exchange = HttpExchange.from(
                RequestCompletedEvents.request("trace-1").requestHeaders(null).build());

        assertThat(exchange.request().headers()).isEmpty();
    }

    @Test
    void from_defaultsNullResponseHeadersToEmptyMap() {
        HttpExchange exchange = HttpExchange.from(
                RequestCompletedEvents.request("trace-1").responseHeaders(null).build());

        assertThat(exchange.response().headers()).isEmpty();
    }
}

package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.HttpExchange;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.testsupport.RequestCompletedEvents;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;

class HttpExchangeMapperTest {

    private final HttpExchangeMapper mapper = new HttpExchangeMapper(new MaskingEngine());

    @Test
    void mapMapsAllFieldsWhenPresent() {
        RequestCompletedEvent event = RequestCompletedEvents.request("trace-1")
                .method("POST")
                .path("/api/users")
                .queryString("sort=name")
                .requestHeaders(Map.of("Content-Type", "application/json"))
                .controller("UserController", "create")
                .queryParams(Map.of("sort", List.of("name")))
                .formParams(Map.of("field", List.of("value")))
                .status(201)
                .responseHeaders(Map.of("Location", "/api/users/1"))
                .build();

        HttpExchange exchange = mapper.map(event);

        assertThat(exchange.request().method()).isEqualTo("POST");
        assertThat(exchange.request().path()).isEqualTo("/api/users");
        assertThat(exchange.request().query()).isEqualTo("sort=name");
        assertThat(exchange.request().headers()).containsEntry("Content-Type", "application/json");
        assertThat(exchange.request().controller().className()).isEqualTo("UserController");
        assertThat(exchange.request().controller().method()).isEqualTo("create");
        assertThat(exchange.request().params().query()).containsEntry("sort", List.of("name"));
        assertThat(exchange.request().params().form()).containsEntry("field", List.of("value"));
        assertThat(exchange.response().status()).isEqualTo(201);
        assertThat(exchange.response().headers()).containsEntry("Location", "/api/users/1");
    }

    @Test
    void mapMasksACredentialInThePath() {
        HttpExchange exchange = mapper.map(
                RequestCompletedEvents.request("t1").path("/reset;token=abc123").build());

        assertThat(exchange.request().path()).isEqualTo("/reset;token=******");
    }

    @Test
    void mapDefaultsNullQueryParamsToEmptyMap() {
        HttpExchange exchange = mapper.map(
                RequestCompletedEvents.request("trace-1").queryParams(null).build());

        assertThat(exchange.request().params().query()).isEmpty();
    }

    @Test
    void mapDefaultsNullFormParamsToEmptyMap() {
        HttpExchange exchange = mapper.map(
                RequestCompletedEvents.request("trace-1").formParams(null).build());

        assertThat(exchange.request().params().form()).isEmpty();
    }

    @Test
    void mapDefaultsNullRequestHeadersToEmptyMap() {
        HttpExchange exchange = mapper.map(
                RequestCompletedEvents.request("trace-1").requestHeaders(null).build());

        assertThat(exchange.request().headers()).isEmpty();
    }

    @Test
    void mapDefaultsNullResponseHeadersToEmptyMap() {
        HttpExchange exchange = mapper.map(
                RequestCompletedEvents.request("trace-1").responseHeaders(null).build());

        assertThat(exchange.response().headers()).isEmpty();
    }
}

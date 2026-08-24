package org.peekaboot.backend.actuator.parsed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class ActuatorResponseParserTest {

    private static Map<String, Object> rawData;
    private final ActuatorResponseParser parser = new ActuatorResponseParser();

    @BeforeAll
    static void loadSampleData() throws Exception {
        JsonMapper jsonMapper = JsonMapper.builder().build();
        try (InputStream is = ActuatorResponseParserTest.class.getResourceAsStream("/sample_actuator_all.json")) {
            rawData = jsonMapper.readValue(is, new TypeReference<>() {});
        }
    }

    @Test
    void mapsFullResponse() {
        ActuatorParsedData response = parser.parse(rawData);

        assertThat(response).isNotNull();
        assertThat(response.spring()).isNotNull();
        assertThat(response.health()).isNotNull();
        assertThat(response.info()).isNotNull();
        assertThat(response.env()).isNotNull();
        assertThat(response.loggers()).isNotNull();
        assertThat(response.flyway()).isNotNull();
        assertThat(response.configprops()).isNotNull();
        assertThat(response.scheduledtasks()).isNotNull();
    }

    @Test
    void toleratesErrorPlaceholderForSingleEndpoint() {
        // PeekabootActuatorService stores "Error: ..." strings for endpoints
        // that failed to invoke; one broken endpoint must not break parsing
        // of all the others.
        Map<String, Object> data = new java.util.LinkedHashMap<>(rawData);
        data.put("env", "Error: env endpoint failed");

        ActuatorParsedData response = parser.parse(data);

        assertThat(response.env()).isNull();
        assertThat(response.health()).isNotNull();
        assertThat(response.info()).isNotNull();
        assertThat(response.loggers()).isNotNull();
    }

    @Test
    void parsesPojoEndpointResults() {
        // At runtime the invoked operations return POJOs (WebEndpointResponse,
        // descriptor objects), not Maps - they must be converted, not dropped.
        record HealthBody(String status, Map<String, Object> components) {}
        record HealthPojo(int status, HealthBody body) {}
        Map<String, Object> data = Map.of("health", new HealthPojo(200, new HealthBody("UP", Map.of())));

        ActuatorParsedData response = parser.parse(data);

        assertThat(response.health()).isNotNull();
        assertThat(response.health().status()).isEqualTo(200);
        assertThat(response.health().body().status()).isEqualTo("UP");
    }

    @Test
    void handlesNullInput() {
        ActuatorParsedData response = parser.parse(null);

        assertThat(response).isNotNull();
        assertThat(response.spring()).isNull();
        assertThat(response.health()).isNull();
        assertThat(response.info()).isNull();
        assertThat(response.scheduledtasks()).isNull();
    }
}

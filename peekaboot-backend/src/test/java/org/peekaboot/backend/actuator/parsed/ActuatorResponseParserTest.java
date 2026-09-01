package org.peekaboot.backend.actuator.parsed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.LinkedHashMap;
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
    void parsesAnAbsentEndpointAsNull() {
        // PeekabootActuatorService leaves out an endpoint that failed to invoke;
        // the others must still parse.
        Map<String, Object> data = new LinkedHashMap<>(rawData);
        data.remove("env");

        ActuatorParsedData response = parser.parse(data);

        assertThat(response.env()).isNull();
        assertThat(response.health()).isNotNull();
        assertThat(response.info()).isNotNull();
        assertThat(response.loggers()).isNotNull();
    }

    @Test
    void parsesPojoEndpointResults() {
        // At runtime the invoked operations return POJOs (HealthEndpoint's descriptor,
        // the other endpoints' descriptor objects), not Maps - they must be converted, not
        // dropped.
        record HealthPojo(String status, Map<String, Object> components) {}
        Map<String, Object> data = Map.of("health", new HealthPojo("UP", Map.of()));

        ActuatorParsedData response = parser.parse(data);

        assertThat(response.health()).isNotNull();
        assertThat(response.health().status()).isEqualTo("UP");
    }

    /**
     * The fixture's health entry is the bare descriptor {@code HealthEndpoint.health()}
     * returns - aggregate status at the top, indicators under {@code components} - not the
     * {@code WebEndpointResponse} the web extension wraps it in.
     */
    @Test
    void parsesTheHealthDescriptorsComponents() {
        ActuatorParsedData response = parser.parse(rawData);

        assertThat(response.health().status()).isEqualTo("UP");
        assertThat(response.health().groups()).containsExactly("liveness", "readiness");
        assertThat(response.health().components()).containsKeys("db", "diskSpace", "ping");
        assertThat(response.health().components().get("db").status()).isEqualTo("UP");
        assertThat(response.health().components().get("db").details()).containsEntry("database", "PostgreSQL");
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

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

    /**
     * One value per section, read through the typed records, so a binding that silently
     * drops a section (a renamed component, a wrong nesting) fails here rather than as an
     * empty tab. The fixture is a trimmed /actuator/* dump in the shape Spring Boot 4.1
     * produces: the eight sections this parser binds, one entry each where the real
     * response carries many.
     */
    @Test
    void mapsFullResponse() {
        ActuatorParsedData response = parser.parse(rawData);

        assertThat(response.spring().bootVersion()).isEqualTo("4.1.1");
        assertThat(response.health().status()).isEqualTo("UP");
        assertThat(response.info().build()).containsEntry("artifact", "sample-app");
        assertThat(response.env().activeProfiles()).containsExactly("local");
        assertThat(response.env().propertySources())
                .extracting(EnvResponse.PropertySource::name)
                .contains("systemEnvironment");
        assertThat(response.loggers().loggers())
                .isNotEmpty()
                .extractingByKey("ROOT")
                .extracting(LoggersResponse.LoggerInfo::effectiveLevel)
                .isEqualTo("INFO");
        assertThat(response.flyway()
                        .contexts()
                        .get("sample-app")
                        .flywayBeans()
                        .get("flyway")
                        .migrations())
                .extracting(FlywayResponse.Migration::version)
                .containsExactly("1");
        assertThat(response.configprops().contexts().get("sample-app").beans().values())
                .extracting(ConfigPropsResponse.ConfigBean::prefix)
                .contains("server");
        assertThat(response.scheduledtasks().cron())
                .extracting(ScheduledTasksResponse.CronTask::expression)
                .containsExactly("0 0 * * * *");
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

    /**
     * Two DataSources make Spring's {@code db} contributor a composite whose children sit
     * under a nested {@code components} map, one level below the top-level indicators.
     */
    @Test
    void parsesACompositeComponentsChildren() throws Exception {
        JsonMapper jsonMapper = JsonMapper.builder().build();
        Map<String, Object> health;
        try (InputStream is =
                ActuatorResponseParserTest.class.getResourceAsStream("/sample_health_two_datasources.json")) {
            health = jsonMapper.readValue(is, new TypeReference<>() {});
        }

        ActuatorParsedData response = parser.parse(Map.of("health", health));

        HealthResponse.HealthComponent db = response.health().components().get("db");
        assertThat(db.status()).isEqualTo("DOWN");
        assertThat(db.details()).isNull();
        assertThat(db.components()).containsOnlyKeys("primary", "reporting");
        assertThat(db.components().get("primary").status()).isEqualTo("UP");
        assertThat(db.components().get("reporting").details())
                .containsEntry("error", "org.postgresql.util.PSQLException: Connection refused");
        assertThat(response.health().components().get("ping").components()).isNull();
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

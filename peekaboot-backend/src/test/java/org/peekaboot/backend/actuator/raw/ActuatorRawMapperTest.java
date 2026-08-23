package org.peekaboot.backend.actuator.raw;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorRawMapperTest {

    private static Map<String, Object> rawData;
    private final ActuatorRawMapper mapper = new ActuatorRawMapper();

    @BeforeAll
    static void loadSampleData() throws Exception {
        JsonMapper jsonMapper = JsonMapper.builder().build();
        try (InputStream is = ActuatorRawMapperTest.class.getResourceAsStream("/sample_actuator_all_raw.json")) {
            rawData = jsonMapper.readValue(is, new TypeReference<>() {});
        }
    }

    @Test
    void mapsSpringInfo() {
        SpringInfo spring = mapper.mapSpring(rawData);

        assertThat(spring).isNotNull();
        assertThat(spring.bootVersion()).isEqualTo("4.0.1");
        assertThat(spring.frameworkVersion()).isEqualTo("7.0.2");
    }

    @Test
    void mapsHealthResponse() {
        HealthResponse health = mapper.mapHealth(rawData);

        assertThat(health).isNotNull();
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.body()).isNotNull();
        assertThat(health.body().status()).isEqualTo("UP");
        assertThat(health.body().components()).isNotEmpty();
        assertThat(health.body().components()).containsKey("db");
        assertThat(health.body().components()).containsKey("diskSpace");

        // Verify db component
        HealthResponse.HealthComponent db = health.body().components().get("db");
        assertThat(db.status()).isEqualTo("UP");
        assertThat(db.details()).containsKey("database");
        assertThat(db.details().get("database")).isEqualTo("PostgreSQL");

        // Verify diskSpace component
        HealthResponse.HealthComponent diskSpace = health.body().components().get("diskSpace");
        assertThat(diskSpace.status()).isEqualTo("UP");
        assertThat(diskSpace.details()).containsKey("total");
        assertThat(diskSpace.details()).containsKey("free");
    }

    @Test
    void mapsInfoResponse() {
        InfoResponse info = mapper.mapInfo(rawData);

        assertThat(info).isNotNull();

        // Java info
        assertThat(info.java()).isNotNull();
        assertThat(info.java().version()).isEqualTo("21.0.7");
        assertThat(info.java().vendor()).isNotNull();
        assertThat(info.java().vendor().name()).isEqualTo("Eclipse Adoptium");

        // OS info
        assertThat(info.os()).isNotNull();
        assertThat(info.os().name()).isEqualTo("Mac OS X");
        assertThat(info.os().version()).isEqualTo("15.7.3");
        assertThat(info.os().arch()).isEqualTo("aarch64");

        // Process/memory info
        assertThat(info.process()).isNotNull();
        assertThat(info.process().memory()).isNotNull();
        assertThat(info.process().memory().heap()).isNotNull();
        assertThat(info.process().memory().heap().used()).isGreaterThan(0);
        assertThat(info.process().memory().heap().max()).isGreaterThan(0);
        assertThat(info.process().memory().nonHeap()).isNotNull();
        assertThat(info.process().memory().nonHeap().used()).isGreaterThan(0);

        // Git info
        assertThat(info.git()).isNotNull();
        assertThat(info.git().branch()).isEqualTo("dev");
        assertThat(info.git().commit()).isNotNull();
        assertThat(info.git().commit().id()).isEqualTo("3585fa3");

        // Build info
        assertThat(info.build()).isNotNull();
        assertThat(info.build().get("artifact")).isEqualTo("peekaboot-example-app");
    }

    @Test
    void mapsEnvResponse() {
        EnvResponse env = mapper.mapEnv(rawData);

        assertThat(env).isNotNull();
        assertThat(env.activeProfiles()).isNotNull();
        assertThat(env.propertySources()).isNotNull();
        assertThat(env.propertySources()).isNotEmpty();
    }

    @Test
    void mapsLoggersResponse() {
        LoggersResponse loggers = mapper.mapLoggers(rawData);

        assertThat(loggers).isNotNull();
        assertThat(loggers.levels()).isNotNull();
        assertThat(loggers.loggers()).isNotNull();
        assertThat(loggers.loggers()).isNotEmpty();
    }

    @Test
    void mapsFlywayResponse() {
        FlywayResponse flyway = mapper.mapFlyway(rawData);

        assertThat(flyway).isNotNull();
        assertThat(flyway.contexts()).isNotNull();
        assertThat(flyway.contexts()).containsKey("peekaboot-example-app");

        FlywayResponse.FlywayContext context = flyway.contexts().get("peekaboot-example-app");
        assertThat(context.flywayBeans()).containsKey("flyway");

        FlywayResponse.FlywayBean bean = context.flywayBeans().get("flyway");
        assertThat(bean.migrations()).hasSize(2);
        assertThat(bean.migrations().get(0).description()).isEqualTo("initial-schema");
        assertThat(bean.migrations().get(0).state()).isEqualTo("SUCCESS");
    }

    @Test
    void mapsConfigPropsResponse() {
        ConfigPropsResponse configProps = mapper.mapConfigProps(rawData);

        assertThat(configProps).isNotNull();
        assertThat(configProps.contexts()).isNotNull();
        assertThat(configProps.contexts()).containsKey("peekaboot-example-app");

        ConfigPropsResponse.ConfigContext context = configProps.contexts().get("peekaboot-example-app");
        assertThat(context.beans()).isNotNull();
        assertThat(context.beans()).isNotEmpty();
    }

    @Test
    void mapsScheduledTasksResponse() {
        ScheduledTasksResponse scheduledTasks = mapper.mapScheduledTasks(rawData);

        assertThat(scheduledTasks).isNotNull();
        assertThat(scheduledTasks.cron()).isNotNull();
        assertThat(scheduledTasks.cron()).hasSize(3);
        assertThat(scheduledTasks.fixedDelay()).hasSize(1);
        assertThat(scheduledTasks.fixedRate()).hasSize(1);

        // Verify cron task structure
        var cronTask = scheduledTasks.cron().get(0);
        assertThat(cronTask.expression()).isEqualTo("0 0 * * * *");
        assertThat(cronTask.runnable().target()).isEqualTo("org.peekaboot.example.Scheduler.cron1");

        // Verify fixed delay task with execution status
        var fixedDelayTask = scheduledTasks.fixedDelay().get(0);
        assertThat(fixedDelayTask.interval()).isEqualTo(5000L);
        assertThat(fixedDelayTask.lastExecution().status()).isEqualTo("SUCCESS");
    }

    @Test
    void mapsFullResponse() {
        ActuatorParsedData response = mapper.map(rawData);

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

        ActuatorParsedData response = mapper.map(data);

        assertThat(response.env()).isNull();
        assertThat(response.health()).isNotNull();
        assertThat(response.info()).isNotNull();
        assertThat(response.loggers()).isNotNull();
    }

    @Test
    void singleKeyMappersReturnNullForErrorPlaceholder() {
        Map<String, Object> data = Map.of(
                "health", "Error: boom",
                "spring", "Error: boom",
                "info", "Error: boom",
                "env", "Error: boom",
                "loggers", "Error: boom",
                "flyway", "Error: boom",
                "configprops", "Error: boom",
                "scheduledtasks", "Error: boom");

        assertThat(mapper.mapHealth(data)).isNull();
        assertThat(mapper.mapSpring(data)).isNull();
        assertThat(mapper.mapInfo(data)).isNull();
        assertThat(mapper.mapEnv(data)).isNull();
        assertThat(mapper.mapLoggers(data)).isNull();
        assertThat(mapper.mapFlyway(data)).isNull();
        assertThat(mapper.mapConfigProps(data)).isNull();
        assertThat(mapper.mapScheduledTasks(data)).isNull();
    }

    @Test
    void parsesPojoEndpointResults() {
        // At runtime the invoked operations return POJOs (WebEndpointResponse,
        // descriptor objects), not Maps - they must be converted, not dropped.
        record HealthBody(String status, Map<String, Object> components) {}
        record HealthPojo(int status, HealthBody body) {}
        Map<String, Object> data = Map.of("health", new HealthPojo(200, new HealthBody("UP", Map.of())));

        ActuatorParsedData response = mapper.map(data);

        assertThat(response.health()).isNotNull();
        assertThat(response.health().status()).isEqualTo(200);
        assertThat(response.health().body().status()).isEqualTo("UP");

        assertThat(mapper.mapHealth(data)).isNotNull();
        assertThat(mapper.mapHealth(data).body().status()).isEqualTo("UP");
    }

    @Test
    void handlesNullInput() {
        ActuatorParsedData response = mapper.map(null);

        assertThat(response).isNotNull();
        assertThat(response.spring()).isNull();
        assertThat(response.health()).isNull();
        assertThat(response.info()).isNull();
        assertThat(response.scheduledtasks()).isNull();
    }

    @Test
    void maskRawData_shouldMaskTopLevelSensitiveKeyEntirely() {
        Map<String, Object> data = Map.of("apiKey", "AKIAABCDEFGHIJKLMNOP");

        Map<String, Object> masked = mapper.maskRawData(data);

        assertThat(masked).containsEntry("apiKey", "******");
    }

    @Test
    void maskRawData_shouldRecurseIntoNestedMapsAndMaskTheWholeValueForASensitiveKey() {
        // Mirrors PeekabootActuatorService.buildDataSourcesInfo()'s connectionParams
        // shape: {"password": {"value": "...", "source": "..."}}.
        Map<String, Object> data = Map.of(
            "dataSources", java.util.List.of(Map.of(
                "connectionParams", Map.of(
                    "password", Map.of("value", "hunter2", "source", "QUERY"),
                    "ApplicationName", Map.of("value", "peekaboot-example-app", "source", "QUERY")
                )
            ))
        );

        Map<String, Object> masked = mapper.maskRawData(data);

        @SuppressWarnings("unchecked")
        var dataSources = (java.util.List<Map<String, Object>>) masked.get("dataSources");
        @SuppressWarnings("unchecked")
        var connectionParams = (Map<String, Object>) dataSources.get(0).get("connectionParams");
        assertThat(connectionParams).containsEntry("password", "******");
        assertThat(connectionParams).containsEntry("ApplicationName", Map.of("value", "peekaboot-example-app", "source", "QUERY"));
    }

    @Test
    void maskRawData_shouldApplyValuePatternRulesToStringValuesUnderNonSensitiveKeys() {
        Map<String, Object> data = Map.of(
            "env", Map.of("propertySources", java.util.List.of(Map.of(
                "properties", Map.of("spring.datasource.url",
                    Map.of("value", "jdbc:postgresql://admin:hunter2@localhost/db"))
            )))
        );

        Map<String, Object> masked = mapper.maskRawData(data);

        @SuppressWarnings("unchecked")
        var env = (Map<String, Object>) masked.get("env");
        @SuppressWarnings("unchecked")
        var sources = (java.util.List<Map<String, Object>>) env.get("propertySources");
        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) sources.get(0).get("properties");
        @SuppressWarnings("unchecked")
        var urlProperty = (Map<String, Object>) properties.get("spring.datasource.url");
        assertThat(urlProperty.get("value")).isEqualTo("jdbc:postgresql://******@localhost/db");
    }

    @Test
    void maskRawData_shouldLeaveNonSensitiveScalarsUntouched() {
        Map<String, Object> data = Map.of("port", 8080, "enabled", true, "name", "peekaboot-example-app");

        Map<String, Object> masked = mapper.maskRawData(data);

        assertThat(masked).containsEntry("port", 8080);
        assertThat(masked).containsEntry("enabled", true);
        assertThat(masked).containsEntry("name", "peekaboot-example-app");
    }

    @Test
    void maskRawData_shouldNotMaskNegativeCaseKeysThatMerelyContainKey() {
        Map<String, Object> data = Map.of(
            "spring.jpa.key-generator", "sequence",
            "server.ssl.key-store", "classpath:keystore.p12"
        );

        Map<String, Object> masked = mapper.maskRawData(data);

        assertThat(masked).containsEntry("spring.jpa.key-generator", "sequence");
        assertThat(masked).containsEntry("server.ssl.key-store", "classpath:keystore.p12");
    }

    @Test
    void maskRawData_shouldToleratePojoEndpointResultsNotJustMaps() {
        // At runtime, endpoint results are POJOs (WebEndpointResponse, descriptor
        // objects), not pre-parsed Maps - maskRawData must normalise these before
        // it can recurse, exactly like map() already does. "account" (not
        // "credentials") is used as the nesting key so this test actually exercises
        // recursion into a POJO field rather than the sensitive-key whole-value-replace
        // branch a key like "credentials" would hit one level up.
        record Account(String username, String password) {}
        record HealthPojo(int status, Account account) {}
        Map<String, Object> data = Map.of("health", new HealthPojo(200, new Account("admin", "hunter2")));

        Map<String, Object> masked = mapper.maskRawData(data);

        @SuppressWarnings("unchecked")
        var health = (Map<String, Object>) masked.get("health");
        @SuppressWarnings("unchecked")
        var account = (Map<String, Object>) health.get("account");
        assertThat(account.get("username")).isEqualTo("admin");
        assertThat(account.get("password")).isEqualTo("******");
    }

    @Test
    void maskRawData_shouldHandleNullInput() {
        assertThat(mapper.maskRawData(null)).isEmpty();
    }

    @Test
    void maskRawData_shouldToleratesErrorPlaceholderStringValues() {
        Map<String, Object> data = Map.of("env", "Error: env endpoint failed");

        Map<String, Object> masked = mapper.maskRawData(data);

        assertThat(masked).containsEntry("env", "Error: env endpoint failed");
    }

    @Test
    void maskRawData_shouldReturnRealValueWhenUnmaskIsTrue() {
        Map<String, Object> data = Map.of("apiKey", "AKIAABCDEFGHIJKLMNOP");

        Map<String, Object> masked = mapper.maskRawData(data, true);

        assertThat(masked).containsEntry("apiKey", "AKIAABCDEFGHIJKLMNOP");
    }

    @Test
    void maskRawData_shouldStillMaskWhenUnmaskIsFalse() {
        Map<String, Object> data = Map.of("apiKey", "AKIAABCDEFGHIJKLMNOP");

        Map<String, Object> masked = mapper.maskRawData(data, false);

        assertThat(masked).containsEntry("apiKey", "******");
    }

    @Test
    void maskRawData_shouldMaskEntireFixtureWithoutBlowingUp() {
        // The full sample payload must round-trip without error; spot-check a couple
        // of the deeply-nested sensitive property names known to be present in it.
        Map<String, Object> masked = mapper.maskRawData(rawData);

        assertThat(masked).containsKeys("env", "configprops", "dataSources");
    }
}

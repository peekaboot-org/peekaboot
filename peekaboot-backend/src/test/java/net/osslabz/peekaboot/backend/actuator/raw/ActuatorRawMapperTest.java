package net.osslabz.peekaboot.backend.actuator.raw;

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
        assertThat(cronTask.runnable().target()).isEqualTo("net.osslabz.example.Scheduler.cron1");

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
    void handlesNullInput() {
        ActuatorParsedData response = mapper.map(null);

        assertThat(response).isNotNull();
        assertThat(response.spring()).isNull();
        assertThat(response.health()).isNull();
        assertThat(response.info()).isNull();
        assertThat(response.scheduledtasks()).isNull();
    }
}

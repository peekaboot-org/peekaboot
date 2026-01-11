package net.osslabz.peekaboot.backend.actuator.raw;

import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Maps the raw Map response from PeekabookActuatorService to typed actuator beans.
 */
@Component
public class ActuatorRawMapper {

    private final ObjectMapper objectMapper;

    public ActuatorRawMapper() {
        this.objectMapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    }

    public ActuatorRawResponse map(Map<String, Object> rawData) {
        if (rawData == null) {
            return new ActuatorRawResponse(null, null, null, null, null, null, null);
        }
        return objectMapper.convertValue(rawData, ActuatorRawResponse.class);
    }

    public SpringInfo mapSpring(Map<String, Object> rawData) {
        Object spring = rawData != null ? rawData.get("spring") : null;
        return spring != null ? objectMapper.convertValue(spring, SpringInfo.class) : null;
    }

    public HealthResponse mapHealth(Map<String, Object> rawData) {
        Object health = rawData != null ? rawData.get("health") : null;
        return health != null ? objectMapper.convertValue(health, HealthResponse.class) : null;
    }

    public InfoResponse mapInfo(Map<String, Object> rawData) {
        Object info = rawData != null ? rawData.get("info") : null;
        return info != null ? objectMapper.convertValue(info, InfoResponse.class) : null;
    }

    public EnvResponse mapEnv(Map<String, Object> rawData) {
        Object env = rawData != null ? rawData.get("env") : null;
        return env != null ? objectMapper.convertValue(env, EnvResponse.class) : null;
    }

    public LoggersResponse mapLoggers(Map<String, Object> rawData) {
        Object loggers = rawData != null ? rawData.get("loggers") : null;
        return loggers != null ? objectMapper.convertValue(loggers, LoggersResponse.class) : null;
    }

    public FlywayResponse mapFlyway(Map<String, Object> rawData) {
        Object flyway = rawData != null ? rawData.get("flyway") : null;
        return flyway != null ? objectMapper.convertValue(flyway, FlywayResponse.class) : null;
    }

    public ConfigPropsResponse mapConfigProps(Map<String, Object> rawData) {
        Object configprops = rawData != null ? rawData.get("configprops") : null;
        return configprops != null ? objectMapper.convertValue(configprops, ConfigPropsResponse.class) : null;
    }
}

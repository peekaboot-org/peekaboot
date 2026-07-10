package net.osslabz.peekaboot.backend.actuator.raw;

import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps the raw Map response from PeekabootActuatorService to typed actuator beans.
 * Entries that are not JSON objects (e.g. the "Error: ..." placeholders stored
 * for endpoints that failed to invoke) are treated as absent, so one broken
 * endpoint never breaks parsing of the others.
 */
@Component
public class ActuatorRawMapper {

    private final ObjectMapper objectMapper;

    public ActuatorRawMapper() {
        this.objectMapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    }

    public ActuatorParsedData map(Map<String, Object> rawData) {
        if (rawData == null) {
            return new ActuatorParsedData(null, null, null, null, null, null, null, null);
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        rawData.forEach((key, value) -> {
            if (value instanceof Map) {
                sanitized.put(key, value);
            }
        });
        return objectMapper.convertValue(sanitized, ActuatorParsedData.class);
    }

    public SpringInfo mapSpring(Map<String, Object> rawData) {
        return mapKey(rawData, "spring", SpringInfo.class);
    }

    public HealthResponse mapHealth(Map<String, Object> rawData) {
        return mapKey(rawData, "health", HealthResponse.class);
    }

    public InfoResponse mapInfo(Map<String, Object> rawData) {
        return mapKey(rawData, "info", InfoResponse.class);
    }

    public EnvResponse mapEnv(Map<String, Object> rawData) {
        return mapKey(rawData, "env", EnvResponse.class);
    }

    public LoggersResponse mapLoggers(Map<String, Object> rawData) {
        return mapKey(rawData, "loggers", LoggersResponse.class);
    }

    public FlywayResponse mapFlyway(Map<String, Object> rawData) {
        return mapKey(rawData, "flyway", FlywayResponse.class);
    }

    public ConfigPropsResponse mapConfigProps(Map<String, Object> rawData) {
        return mapKey(rawData, "configprops", ConfigPropsResponse.class);
    }

    public ScheduledTasksResponse mapScheduledTasks(Map<String, Object> rawData) {
        return mapKey(rawData, "scheduledtasks", ScheduledTasksResponse.class);
    }

    private <T> T mapKey(Map<String, Object> rawData, String key, Class<T> type) {
        Object value = rawData != null ? rawData.get(key) : null;
        return value instanceof Map ? objectMapper.convertValue(value, type) : null;
    }
}

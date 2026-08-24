package org.peekaboot.backend.actuator.raw;

import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.masking.TreeMasker;
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
    private final TreeMasker treeMasker = new TreeMasker(new MaskingEngine());

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
            if (isConvertible(value)) {
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
        return isConvertible(value) ? objectMapper.convertValue(value, type) : null;
    }

    /**
     * Endpoint results are Maps (from JSON) or POJOs (WebEndpointResponse,
     * descriptor objects at runtime) - both convert; only the String error
     * placeholders don't.
     */
    private static boolean isConvertible(Object value) {
        return value != null && !(value instanceof String);
    }

    /**
     * Masks the raw actuator response tree for {@code /peekaboot/api/actuator/all/raw} -
     * the one caller ({@link org.peekaboot.backend.service.PeekabootActuatorService#getData(boolean)})
     * that bypasses every typed mapper's masking by returning the raw payload as-is.
     * Unlike {@link #map(Map)}, whose target shape is known upfront (the fixed set of
     * actuator endpoints the insights mappers consume), the raw endpoint reaches every
     * exposed actuator endpoint - "beans", "conditions", "mappings", "sbom", ... - whose
     * shape is arbitrary and not modelled by any type here. So masking it can't dispatch
     * to the domain mappers; it has to walk the generic tree instead.
     *
     * <p>Endpoint results may be POJOs at runtime (same as {@link #map(Map)} handles), so
     * the whole tree is first normalised to plain Map/List/scalar via Jackson before the
     * masking recursion runs.
     */
    public Map<String, Object> maskRawData(Map<String, Object> rawData) {
        return maskRawData(rawData, false);
    }

    /**
     * Same as {@link #maskRawData(Map)}, except when {@code unmask} is true, in which case
     * masking is bypassed entirely and the normalised tree is returned verbatim. See
     * {@link org.peekaboot.backend.masking.MaskingEngine#mask(String, String, boolean)} for
     * why this shape.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> maskRawData(Map<String, Object> rawData, boolean unmask) {
        if (rawData == null) {
            return Map.of();
        }
        Object normalized = objectMapper.convertValue(rawData, Object.class);
        Object masked = treeMasker.mask(normalized, unmask);
        return masked instanceof Map ? (Map<String, Object>) masked : Map.of();
    }
}

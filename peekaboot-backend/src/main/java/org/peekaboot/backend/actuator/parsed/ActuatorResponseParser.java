package org.peekaboot.backend.actuator.parsed;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses the raw Map response from PeekabootActuatorService into typed actuator beans.
 * An endpoint the service could not invoke is absent from that map and parses as null;
 * the others are unaffected. Each section binds on its own, so one that does not fit its
 * record parses as null too, logged like a failed read, and cannot take the others with it.
 * Inside a section, a collection the response left out binds as empty, normalised once by
 * the records' compact constructors so no mapper has to guard for it. Properties the records
 * do not declare are ignored here, for every record at once: the records bind only what the
 * mappers read, and a Boot release adding a field must not break the parse.
 */
public class ActuatorResponseParser {

    private static final Logger log = LoggerFactory.getLogger(ActuatorResponseParser.class);

    private final ObjectMapper objectMapper;

    /** Sections whose failure was reported at WARN; their later failures log at DEBUG. */
    private final Set<String> reportedFailures = ConcurrentHashMap.newKeySet();

    public ActuatorResponseParser() {
        this.objectMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    public ActuatorParsedData parse(Map<String, Object> rawData) {
        return new ActuatorParsedData(
                section(rawData, "spring", SpringInfo.class),
                section(rawData, "health", HealthResponse.class),
                section(rawData, "info", InfoResponse.class),
                section(rawData, "env", EnvResponse.class),
                section(rawData, "loggers", LoggersResponse.class),
                section(rawData, "flyway", FlywayResponse.class),
                section(rawData, "liquibase", LiquibaseResponse.class),
                section(rawData, "configprops", ConfigPropsResponse.class),
                section(rawData, "scheduledtasks", ScheduledTasksResponse.class));
    }

    private <T> T section(Map<String, Object> rawData, String id, Class<T> type) {
        Object raw = rawData.get(id);
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(raw, type);
        } catch (JacksonException e) {
            if (reportedFailures.add(id)) {
                log.warn("Actuator endpoint '{}' did not parse", id, e);
            } else {
                log.debug("Actuator endpoint '{}' did not parse again: {}", id, e.toString());
            }
            return null;
        }
    }
}

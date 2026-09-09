package org.peekaboot.backend.actuator.parsed;

import java.util.List;
import java.util.Map;

/** Absent collections bind as empty (see {@link ActuatorResponseParser}). */
public record EnvResponse(List<String> activeProfiles, List<PropertySource> propertySources) {

    public EnvResponse {
        activeProfiles = Absent.orEmpty(activeProfiles);
        propertySources = Absent.orEmpty(propertySources);
    }

    public record PropertySource(String name, Map<String, PropertyValue> properties) {
        public PropertySource {
            properties = Absent.orEmpty(properties);
        }
    }

    public record PropertyValue(Object value, String origin) {}
}

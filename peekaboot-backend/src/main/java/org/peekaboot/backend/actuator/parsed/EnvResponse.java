package org.peekaboot.backend.actuator.parsed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnvResponse(List<String> activeProfiles, List<PropertySource> propertySources) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PropertySource(String name, Map<String, PropertyValue> properties) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PropertyValue(Object value, String origin) {}
}

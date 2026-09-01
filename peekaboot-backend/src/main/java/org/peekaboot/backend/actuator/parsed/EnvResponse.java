package org.peekaboot.backend.actuator.parsed;

import java.util.List;
import java.util.Map;

public record EnvResponse(List<String> activeProfiles, List<PropertySource> propertySources) {

    public record PropertySource(String name, Map<String, PropertyValue> properties) {}

    public record PropertyValue(Object value, String origin) {}
}

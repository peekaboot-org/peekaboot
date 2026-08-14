package org.peekaboot.backend.mapper.actuator;

import org.peekaboot.backend.actuator.raw.EnvResponse;
import org.peekaboot.backend.domain.environment.EnvironmentInfo;
import org.peekaboot.backend.domain.environment.PropertySourceGroup;
import org.peekaboot.backend.domain.environment.PropertyValue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class EnvironmentMapper {

    public EnvironmentInfo map(EnvResponse env) {
        if (env == null) {
            return new EnvironmentInfo(List.of(), List.of());
        }

        List<String> activeProfiles = env.activeProfiles() != null ? env.activeProfiles() : List.of();
        List<PropertySourceGroup> propertySources = extractPropertySources(env);

        return new EnvironmentInfo(activeProfiles, propertySources);
    }

    private List<PropertySourceGroup> extractPropertySources(EnvResponse env) {
        if (env.propertySources() == null) {
            return List.of();
        }

        List<PropertySourceGroup> result = new ArrayList<>();
        for (EnvResponse.PropertySource source : env.propertySources()) {
            String name = source.name() != null ? source.name() : "unknown";
            List<PropertyValue> properties = extractProperties(source);
            result.add(new PropertySourceGroup(name, properties));
        }
        return result;
    }

    private List<PropertyValue> extractProperties(EnvResponse.PropertySource source) {
        if (source.properties() == null) {
            return List.of();
        }

        List<PropertyValue> result = new ArrayList<>();
        for (Map.Entry<String, EnvResponse.PropertyValue> entry : source.properties().entrySet()) {
            String key = entry.getKey();
            EnvResponse.PropertyValue pv = entry.getValue();
            String value = pv.value() != null ? pv.value().toString() : null;
            String origin = pv.origin();
            result.add(new PropertyValue(key, value, origin));
        }
        return result;
    }
}

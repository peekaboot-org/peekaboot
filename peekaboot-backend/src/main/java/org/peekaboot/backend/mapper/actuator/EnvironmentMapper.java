package org.peekaboot.backend.mapper.actuator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.actuator.parsed.EnvResponse;
import org.peekaboot.backend.domain.environment.EnvironmentInfo;
import org.peekaboot.backend.domain.environment.PropertySourceGroup;
import org.peekaboot.backend.domain.environment.PropertyValue;
import org.peekaboot.backend.masking.MaskingEngine;

public class EnvironmentMapper {

    private final MaskingEngine maskingEngine;

    public EnvironmentMapper(MaskingEngine maskingEngine) {
        this.maskingEngine = maskingEngine;
    }

    public EnvironmentInfo map(EnvResponse env, boolean unmask) {
        if (env == null) {
            return new EnvironmentInfo(List.of(), List.of());
        }

        List<String> activeProfiles = env.activeProfiles() != null ? env.activeProfiles() : List.of();
        List<PropertySourceGroup> propertySources = extractPropertySources(env, unmask);

        return new EnvironmentInfo(activeProfiles, propertySources);
    }

    private List<PropertySourceGroup> extractPropertySources(EnvResponse env, boolean unmask) {
        if (env.propertySources() == null) {
            return List.of();
        }

        List<PropertySourceGroup> result = new ArrayList<>();
        for (EnvResponse.PropertySource source : env.propertySources()) {
            String name = source.name() != null ? source.name() : "unknown";
            List<PropertyValue> properties = extractProperties(source, unmask);
            result.add(new PropertySourceGroup(name, properties));
        }
        return result;
    }

    private List<PropertyValue> extractProperties(EnvResponse.PropertySource source, boolean unmask) {
        if (source.properties() == null) {
            return List.of();
        }

        List<PropertyValue> result = new ArrayList<>();
        for (Map.Entry<String, EnvResponse.PropertyValue> entry :
                source.properties().entrySet()) {
            String key = entry.getKey();
            EnvResponse.PropertyValue pv = entry.getValue();
            String value = pv.value() != null ? pv.value().toString() : null;
            String origin = pv.origin();
            result.add(new PropertyValue(key, maskingEngine.mask(key, value, unmask), origin));
        }
        return result;
    }
}

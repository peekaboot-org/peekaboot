package org.peekaboot.backend.mapper.actuator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.actuator.parsed.EnvResponse;
import org.peekaboot.backend.domain.environment.EnvironmentInfo;
import org.peekaboot.backend.domain.property.Property;
import org.peekaboot.backend.domain.property.PropertyGroup;
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

        return new EnvironmentInfo(env.activeProfiles(), extractPropertySources(env, unmask));
    }

    private List<PropertyGroup> extractPropertySources(EnvResponse env, boolean unmask) {
        List<PropertyGroup> result = new ArrayList<>();
        for (EnvResponse.PropertySource source : env.propertySources()) {
            String name = source.name() != null ? source.name() : "unknown";
            List<Property> properties = extractProperties(source, unmask);
            result.add(new PropertyGroup(name, properties));
        }
        return result;
    }

    private List<Property> extractProperties(EnvResponse.PropertySource source, boolean unmask) {
        List<Property> result = new ArrayList<>();
        for (Map.Entry<String, EnvResponse.PropertyValueDescriptor> entry :
                source.properties().entrySet()) {
            String key = entry.getKey();
            EnvResponse.PropertyValueDescriptor pv = entry.getValue();
            String value = pv.value() != null ? pv.value().toString() : null;
            result.add(new Property(key, maskingEngine.mask(key, value, unmask)));
        }
        return result;
    }
}

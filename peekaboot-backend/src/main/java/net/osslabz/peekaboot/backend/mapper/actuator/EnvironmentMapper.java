package net.osslabz.peekaboot.backend.mapper.actuator;

import net.osslabz.peekaboot.backend.domain.environment.EnvironmentInfo;
import net.osslabz.peekaboot.backend.domain.environment.PropertySourceGroup;
import net.osslabz.peekaboot.backend.domain.environment.PropertyValue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class EnvironmentMapper {

    @SuppressWarnings("unchecked")
    public EnvironmentInfo map(Map<String, Object> env) {
        if (env == null) {
            return new EnvironmentInfo(List.of(), List.of());
        }

        List<String> activeProfiles = extractActiveProfiles(env);
        List<PropertySourceGroup> propertySources = extractPropertySources(env);

        return new EnvironmentInfo(activeProfiles, propertySources);
    }

    @SuppressWarnings("unchecked")
    private List<String> extractActiveProfiles(Map<String, Object> env) {
        Object profiles = env.get("activeProfiles");
        if (profiles instanceof List<?> list) {
            return list.stream().filter(p -> p != null).map(Object::toString).toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<PropertySourceGroup> extractPropertySources(Map<String, Object> env) {
        Object sources = env.get("propertySources");
        if (!(sources instanceof List<?> list)) {
            return List.of();
        }

        List<PropertySourceGroup> result = new ArrayList<>();
        for (Object source : list) {
            if (source instanceof Map<?, ?> sourceMap) {
                String name = sourceMap.get("name") != null ? sourceMap.get("name").toString() : "unknown";
                List<PropertyValue> properties = extractProperties((Map<String, Object>) sourceMap);
                result.add(new PropertySourceGroup(name, properties));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<PropertyValue> extractProperties(Map<String, Object> sourceMap) {
        Object propsObj = sourceMap.get("properties");
        if (!(propsObj instanceof Map<?, ?> props)) {
            return List.of();
        }

        List<PropertyValue> result = new ArrayList<>();
        for (Map.Entry<?, ?> entry : props.entrySet()) {
            String key = entry.getKey().toString();
            if (entry.getValue() instanceof Map<?, ?> valueMap) {
                String value = valueMap.get("value") != null ? valueMap.get("value").toString() : null;
                String origin = valueMap.get("origin") != null ? valueMap.get("origin").toString() : null;
                result.add(new PropertyValue(key, value, origin));
            }
        }
        return result;
    }
}

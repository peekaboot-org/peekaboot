package net.osslabz.peekaboot.backend.mapper.actuator;

import net.osslabz.peekaboot.backend.domain.config.ConfigGroup;
import net.osslabz.peekaboot.backend.domain.config.ConfigInfo;
import net.osslabz.peekaboot.backend.domain.config.ConfigProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class ConfigMapper {

    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
        "password|secret|key|token|credential|credentials", Pattern.CASE_INSENSITIVE
    );

    @SuppressWarnings("unchecked")
    public ConfigInfo map(Map<String, Object> configprops) {
        if (configprops == null) {
            return new ConfigInfo(List.of());
        }

        Map<String, List<ConfigProperty>> byPrefix = new LinkedHashMap<>();

        Object contextsObj = configprops.get("contexts");
        if (!(contextsObj instanceof Map<?, ?> contexts)) {
            return new ConfigInfo(List.of());
        }

        for (Object contextValue : contexts.values()) {
            if (!(contextValue instanceof Map<?, ?> context)) continue;

            Object beansObj = context.get("beans");
            if (!(beansObj instanceof Map<?, ?> beans)) continue;

            for (Object beanValue : beans.values()) {
                if (!(beanValue instanceof Map<?, ?> bean)) continue;

                String prefix = bean.get("prefix") != null ? bean.get("prefix").toString() : "unknown";
                Object propsObj = bean.get("properties");

                if (propsObj instanceof Map<?, ?> props) {
                    List<ConfigProperty> properties = byPrefix.computeIfAbsent(prefix, k -> new ArrayList<>());

                    for (Map.Entry<?, ?> entry : props.entrySet()) {
                        String key = entry.getKey().toString();
                        String value = entry.getValue() != null ? entry.getValue().toString() : null;

                        if (SENSITIVE_PATTERN.matcher(key).find()) {
                            value = "********";
                        }

                        properties.add(new ConfigProperty(key, value, null));
                    }
                }
            }
        }

        List<ConfigGroup> groups = byPrefix.entrySet().stream()
            .map(e -> new ConfigGroup(e.getKey(), e.getValue()))
            .toList();

        return new ConfigInfo(groups);
    }
}

package org.peekaboot.backend.mapper.actuator;

import org.peekaboot.backend.actuator.raw.ConfigPropsResponse;
import org.peekaboot.backend.domain.config.ConfigGroup;
import org.peekaboot.backend.domain.config.ConfigInfo;
import org.peekaboot.backend.domain.config.ConfigProperty;
import org.peekaboot.backend.masking.MaskingEngine;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ConfigMapper {

    private final MaskingEngine maskingEngine = new MaskingEngine();

    public ConfigInfo map(ConfigPropsResponse configprops) {
        return map(configprops, false);
    }

    /**
     * Same as {@link #map(ConfigPropsResponse)}, except when {@code unmask} is true, in
     * which case every property value is returned verbatim. See
     * {@link MaskingEngine#mask(String, String, boolean)} for why this shape.
     */
    public ConfigInfo map(ConfigPropsResponse configprops, boolean unmask) {
        if (configprops == null || configprops.contexts() == null) {
            return new ConfigInfo(List.of());
        }

        Map<String, List<ConfigProperty>> byPrefix = new LinkedHashMap<>();

        for (ConfigPropsResponse.ConfigContext context : configprops.contexts().values()) {
            if (context.beans() == null) continue;

            for (ConfigPropsResponse.ConfigBean bean : context.beans().values()) {
                String prefix = bean.prefix() != null ? bean.prefix() : "unknown";

                if (bean.properties() != null) {
                    List<ConfigProperty> properties = byPrefix.computeIfAbsent(prefix, k -> new ArrayList<>());

                    for (Map.Entry<String, Object> entry : bean.properties().entrySet()) {
                        String key = entry.getKey();
                        String value = entry.getValue() != null ? entry.getValue().toString() : null;
                        value = maskingEngine.mask(key, value, unmask);

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

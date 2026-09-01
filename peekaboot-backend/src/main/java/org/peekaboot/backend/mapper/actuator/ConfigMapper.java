package org.peekaboot.backend.mapper.actuator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.actuator.parsed.ConfigPropsResponse;
import org.peekaboot.backend.domain.config.ConfigGroup;
import org.peekaboot.backend.domain.config.ConfigInfo;
import org.peekaboot.backend.domain.config.ConfigProperty;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.masking.TreeMasker;
import org.springframework.stereotype.Component;

@Component
public class ConfigMapper {

    private final TreeMasker treeMasker;

    public ConfigMapper(MaskingEngine maskingEngine) {
        this.treeMasker = new TreeMasker(maskingEngine);
    }

    public ConfigInfo map(ConfigPropsResponse configprops, boolean unmask) {
        if (configprops == null || configprops.contexts() == null) {
            return new ConfigInfo(List.of());
        }

        Map<String, List<ConfigProperty>> byPrefix = new LinkedHashMap<>();

        for (ConfigPropsResponse.ConfigContext context : configprops.contexts().values()) {
            if (context.beans() == null) {
                continue;
            }

            for (ConfigPropsResponse.ConfigBean bean : context.beans().values()) {
                collectBeanProperties(bean, byPrefix, unmask);
            }
        }

        List<ConfigGroup> groups = byPrefix.entrySet().stream()
                .map(e -> new ConfigGroup(e.getKey(), e.getValue()))
                .toList();

        return new ConfigInfo(groups);
    }

    private void collectBeanProperties(
            ConfigPropsResponse.ConfigBean bean, Map<String, List<ConfigProperty>> byPrefix, boolean unmask) {
        if (bean.properties() == null) {
            return;
        }
        String prefix = bean.prefix() != null ? bean.prefix() : "unknown";
        List<ConfigProperty> properties = byPrefix.computeIfAbsent(prefix, k -> new ArrayList<>());
        for (Map.Entry<String, Object> entry : bean.properties().entrySet()) {
            properties.add(mapProperty(entry.getKey(), entry.getValue(), unmask));
        }
    }

    private ConfigProperty mapProperty(String key, Object rawValue, boolean unmask) {
        Object maskedValue = rawValue != null ? treeMasker.mask(key, rawValue, unmask) : null;
        String value = maskedValue != null ? maskedValue.toString() : null;
        return new ConfigProperty(key, value);
    }
}

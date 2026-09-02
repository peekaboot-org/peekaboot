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
            Object rawValue = entry.getValue();
            Object masked = rawValue != null ? treeMasker.mask(entry.getKey(), rawValue, unmask) : null;
            flattenInto(properties, entry.getKey(), masked);
        }
    }

    /**
     * One property per leaf, under a dotted key ({@code hikari.maximumPoolSize}), with list
     * elements indexed the way Spring's own property syntax writes them ({@code servers[0]}) -
     * so the Config tab's filter matches nested keys and values instead of one opaque
     * {@code Map.toString()} blob. The tree is masked before flattening, so a sensitive key
     * anywhere in it has already collapsed its whole subtree to the mask, and arrives here
     * as the single leaf it became. An empty container stays a property of its own rather
     * than vanishing.
     */
    private void flattenInto(List<ConfigProperty> out, String key, Object masked) {
        if (masked instanceof Map<?, ?> map && !map.isEmpty()) {
            for (Map.Entry<?, ?> child : map.entrySet()) {
                flattenInto(out, key + "." + child.getKey(), child.getValue());
            }
        } else if (masked instanceof List<?> list && !list.isEmpty()) {
            for (int i = 0; i < list.size(); i++) {
                flattenInto(out, key + "[" + i + "]", list.get(i));
            }
        } else {
            out.add(new ConfigProperty(key, masked != null ? masked.toString() : null));
        }
    }
}
